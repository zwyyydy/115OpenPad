package com.open115.pad.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.sessionDataStore by preferencesDataStore(name = "session")

/** 登录会话：token 的存取、内存缓存与自动刷新。 */
class Session(private val context: Context) {

    private val store = context.sessionDataStore
    private val refreshMutex = Mutex()

    /** 由 AppContainer 注入，避免循环依赖 */
    @Volatile
    var refresher: (suspend (String) -> ApiEnvelope<TokenData>?)? = null

    @Volatile
    var cachedAccessToken: String? = null
        private set

    @Volatile
    private var cachedExpiresAt: Long = 0L

    @Volatile
    private var cachedRefreshToken: String? = null

    /**
     * 被强制登出的原因（终态授权码触发时设置），登录页据此展示提示。
     * 重新登录成功（saveTokens）后清空。
     */
    private val _logoutNotice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val logoutNotice: kotlinx.coroutines.flow.StateFlow<String?> = _logoutNotice

    val clientIdFlow: Flow<String> = store.data.map { it[KEY_CLIENT_ID] ?: "" }

    /**
     * 登录态。
     *
     * **必须 distinctUntilChanged**：DataStore 的 `data` 是"这个偏好文件任何一处变了就发一次"，
     * 而 `map` 不会去重 —— token 刷新写一次 store，就会把 true 再发一遍。下游是
     * "登录后自动跑一轮增量扫描 + 捡回扫描队列"那种一次性动作（见 App115），
     * 多发一次就多扫一轮（间隔 0 = 不限的库每次都中招）。
     */
    val loggedInFlow: Flow<Boolean> =
        store.data.map { !it[KEY_ACCESS_TOKEN].isNullOrBlank() }.distinctUntilChanged()

    suspend fun currentClientId(): String = clientIdFlow.first()

    suspend fun currentAccessToken(): String? {
        cachedAccessToken?.let { token ->
            if (System.currentTimeMillis() < cachedExpiresAt - EXPIRE_MARGIN_MS) return token
            return refreshBlocking()
        }
        val access = store.data.first()[KEY_ACCESS_TOKEN]
        if (access.isNullOrBlank()) return null
        cachedAccessToken = access
        cachedExpiresAt = store.data.first()[KEY_EXPIRES_AT] ?: 0L
        cachedRefreshToken = store.data.first()[KEY_REFRESH_TOKEN]
        if (System.currentTimeMillis() < cachedExpiresAt - EXPIRE_MARGIN_MS) return access
        return refreshBlocking()
    }

    /** 401 或即将过期时刷新；并发下只允许一次真实请求。 */
    suspend fun refreshBlocking(): String? = refreshMutex.withLock {
        cachedAccessToken?.let {
            if (System.currentTimeMillis() < cachedExpiresAt - 60_000) return it
        }
        val refreshToken = cachedRefreshToken ?: store.data.first()[KEY_REFRESH_TOKEN]
        if (refreshToken.isNullOrBlank()) return null
        val resp = try {
            refresher?.invoke(refreshToken)
        } catch (e: Exception) {
            null
        } ?: return null
        val token = resp.data
        if (!resp.ok) {
            // 文档 §05：refresh_token 类错误是终态或会演进成终态——
            // 继续用旧值重试会被服务端标记为永久失效（40140137）。
            // 这里清除会话踢回登录页，并停止后续所有重试。
            if (resp.code != null && isTerminalRefreshCode(resp.code!!)) {
                logout(
                    when (resp.code) {
                        40140137 -> "授权已永久失效，请重新扫码登录"
                        40140116 -> "授权已在别处解除，请重新扫码登录"
                        40140119 -> "授权已过期，请重新扫码登录"
                        else -> "授权校验失败，请重新扫码登录"
                    },
                )
            }
            return null
        }
        if (token?.access_token.isNullOrBlank()) return null
        saveTokens(
            access = token!!.access_token!!,
            refresh = token.refresh_token ?: refreshToken,
            expiresIn = token.expires_in,
        )
        return cachedAccessToken
    }

    suspend fun saveClientId(clientId: String) {
        store.edit { it[KEY_CLIENT_ID] = clientId.trim() }
    }

    suspend fun saveTokens(access: String, refresh: String, expiresIn: Long) {
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000
        cachedAccessToken = access
        cachedExpiresAt = expiresAt
        cachedRefreshToken = refresh
        _logoutNotice.value = null // 重新登录成功，清掉上次的登出提示
        store.edit {
            it[KEY_ACCESS_TOKEN] = access
            it[KEY_REFRESH_TOKEN] = refresh
            it[KEY_EXPIRES_AT] = expiresAt
        }
    }

    suspend fun logout(reason: String? = null) {
        _logoutNotice.value = reason
        cachedAccessToken = null
        cachedExpiresAt = 0L
        cachedRefreshToken = null
        store.edit {
            it.remove(KEY_ACCESS_TOKEN)
            it.remove(KEY_REFRESH_TOKEN)
            it.remove(KEY_EXPIRES_AT)
        }
    }

    private companion object {
        val KEY_CLIENT_ID = stringPreferencesKey("client_id")
        val KEY_ACCESS_TOKEN = stringPreferencesKey("access_token")
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val KEY_EXPIRES_AT = longPreferencesKey("expires_at")
        const val EXPIRE_MARGIN_MS = 5 * 60 * 1000L

        /**
         * 文档 §05：这些 refresh_token 错误是终态或会演进成终态——
         * 继续用旧值重试会被服务端标记为永久失效（40140137）。
         */
        val TERMINAL_REFRESH_CODES = setOf(40140116, 40140119, 40140120, 40140137)

        fun isTerminalRefreshCode(code: Int): Boolean = code in TERMINAL_REFRESH_CODES
    }
}

class AuthInterceptor(private val session: Session) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val req = chain.request()
        // 授权相关接口（passportapi）不需要 Bearer 头
        val host = req.url.host
        val token = if (host.startsWith("passportapi")) null
        else kotlinx.coroutines.runBlocking { session.currentAccessToken() }
        val newReq = if (token.isNullOrBlank()) req
        else req.newBuilder().header("Authorization", "Bearer $token").build()
        return chain.proceed(newReq)
    }
}

class TokenAuthenticator(private val session: Session) : okhttp3.Authenticator {
    override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
        if (responseCount(response) >= 2) return null
        val token = kotlinx.coroutines.runBlocking { session.refreshBlocking() } ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    }

    private fun responseCount(response: okhttp3.Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

/**
 * 115 的"token 失效"不走 HTTP 401，而是 HTTP 200 + body 里 code=40140125，
 * OkHttp Authenticator 感知不到。这里全局拦截：识别后自动刷新令牌并重放请求。
 * 必须注册在 AuthInterceptor 之前，重放时会再次经过它拿到新 token。
 *
 * 文档 §05 补充：
 * - 40140126 = access_token 与授权记录不匹配：禁止用原 token 重试，
 *   刷新后重放（重放会重新走 AuthInterceptor 拿到新 token）
 * - 40140116/19/20/37（refresh_token 终态/准终态）：不再刷新重试，
 *   直接清会话踢回登录页——继续重试只会被服务端标记为永久失效
 */
class Logical401Interceptor(private val session: Session) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.header("Authorization").isNullOrBlank()) return response
        val peeked = runCatching { response.peekBody(65536).string() }.getOrNull() ?: return response

        // refresh_token 终态：清会话踢回登录页，原样返回（调用方会拿到失败并自行提示）
        if (TERMINAL_BODY_REGEX.containsMatchIn(peeked)) {
            kotlinx.coroutines.runBlocking {
                session.logout("授权已失效，请重新扫码登录")
            }
            return response
        }

        // access_token 类失效：刷新后重放一次
        if (!peeked.contains("40140125") && !peeked.contains("40140126")) return response
        val refreshed = kotlinx.coroutines.runBlocking { session.refreshBlocking() } ?: return response
        response.close()
        return chain.proceed(request)
    }

    private companion object {
        val TERMINAL_BODY_REGEX = Regex("40140116|40140119|40140120|40140137")
    }
}

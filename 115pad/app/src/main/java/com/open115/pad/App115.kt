package com.open115.pad

import android.app.Application
import android.content.Context
import com.open115.pad.data.AuthApi
import com.open115.pad.data.AuthInterceptor
import com.open115.pad.data.OpenApi
import com.open115.pad.data.QrApi
import com.open115.pad.data.Session
import com.open115.pad.data.TokenAuthenticator
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class App115 : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    val session = Session(context)
    val playerPrefs = com.open115.pad.data.PlayerPrefs(context)
    val filesPrefs = com.open115.pad.data.FilesPrefs(context)
    val downloadPrefs = com.open115.pad.data.DownloadPrefs(context)

    /** 传输中心的历史记录（本机下载 + 上传） */
    val transferLog = com.open115.pad.data.TransferLog(context)

    /** 剪贴板 / 外部唤起的下载链接汇聚点，由 MainActivity 投递、AppRoot 消费 */
    val downloadLinks = com.open115.pad.data.DownloadLinkBus(context, downloadPrefs)

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", com.open115.pad.util.APP_USER_AGENT)
                    .build()
            )
        }
        .addInterceptor(com.open115.pad.data.Logical401Interceptor(session))
        .addInterceptor(AuthInterceptor(session))
        .authenticator(TokenAuthenticator(session))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // 二维码状态接口是长轮询
        .build()

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val authApi: AuthApi = retrofit("https://passportapi.115.com/").create(AuthApi::class.java)
    val qrApi: QrApi = retrofit("https://qrcodeapi.115.com/").create(QrApi::class.java)
    val openApi: OpenApi = retrofit("https://proapi.115.com/").create(OpenApi::class.java)

    /** 图片直链三级解析链（uo → downurl → thumb）+ downurl 防频控缓存 */
    val imageUrlResolver = com.open115.pad.data.ImageUrlResolver(openApi, okHttpClient)

    /** 高级文件过滤：方案存储 + 文件页右上角总开关 */
    val filterPrefs = com.open115.pad.data.FilterPrefs(context)

    /** 云下载提交（含持久化的保存位置），手动添加/剪贴板/外部唤起共用 */
    val downloadSubmitter = com.open115.pad.data.DownloadSubmitter(downloadPrefs)

    init {
        session.refresher = { refreshToken ->
            try {
                authApi.refreshToken(refreshToken)
            } catch (e: Exception) {
                null
            }
        }
        // 图片加载走同一套 OkHttpClient：User-Agent / 鉴权 / 401 刷新重试全链一致
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(context)
                .okHttpClient(okHttpClient)
                .crossfade(true)
                .build()
        )
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as App115).container

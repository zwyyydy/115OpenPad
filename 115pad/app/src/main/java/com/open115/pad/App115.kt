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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /** 文件夹置顶（本机生效，115 开放平台无对应接口） */
    val pinnedPrefs = com.open115.pad.data.PinnedPrefs(context)

    /**
     * 目录列表缓存（进程级、纯内存、LRU）。
     * 放在 AppContainer 而不是 ViewModel 里：ViewModel 会随导航条目被清掉，
     * 放里面收益只剩"目录间切换"，太薄。
     */
    val dirCache = com.open115.pad.data.DirCache()

    /** 只用于极少数与 UI 无关的长期观察（目前只有"登出后清缓存"） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 上传/恢复协程的作用域：不随页面销毁（切页/换目录不会中断上传），
     * SupervisorJob 防止单个上传失败连坐。暂停/取消由传输中心显式发起。
     */
    val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 传输中心的历史记录（本机下载 + 上传） */
    val transferLog = com.open115.pad.data.TransferLog(context)

    /** 用户操作记录（复制/移动/删除/上传/下载/云离线/预览/播放），侧栏展示 */
    val opLog = com.open115.pad.data.OpLog(context)

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

    /** 批量重命名的持久化任务队列：任务逐条落库，进程被杀后重启能精确续跑 */
    val renameQueue = com.open115.pad.data.RenameQueue(context)

    /**
     * 队列执行器：一条常驻协程把队列里的任务**顺序**跑完。挂在容器上（而不是面板或
     * ViewModel 里）—— 关面板、切页、切任务页都不该中断它，跟上传一样由 [transferScope] 承载。
     */
    val renameWorker = com.open115.pad.data.RenameQueueWorker(openApi, renameQueue, opLog)

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
        // 登出（含因终态授权码被强制登出）后，本机缓存的目录列表必须作废：
        // 不清的话，换个账号登录会直接看到上一个账号的目录内容
        scope.launch {
            session.loggedInFlow.collect { if (!it) dirCache.clear() }
        }

        // 改名队列按登录态启停。**启动即恢复**：worker 一跑起来就会捡起队列里所有
        // 未完成的任务，不需要额外的扫描步骤（也就不依赖用户先打开哪个页面）。
        // 登出必须停 —— 没有 token 还继续跑，只会把剩下的文件全跑成失败。
        scope.launch {
            session.loggedInFlow.collect { loggedIn ->
                if (loggedIn) renameWorker.start(transferScope) else renameWorker.stop()
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as App115).container

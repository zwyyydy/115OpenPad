package com.open115.tv.receiver

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 接收服务（后台）与播放界面（前台）之间的唯一通道。
 *
 * 为什么是进程级单例：投屏指令由服务在**界面不存在时**也可能收到（App 在待机但没打开界面），
 * 而播放状态又必须由界面里的 ExoPlayer 提供——两边分属不同生命周期，只能靠这个中间层。
 */
internal object PlayBus {

    /** 服务收到待播请求后放这里；界面在就消费它、不在就走通知兜底 */
    private val _pending = MutableStateFlow<PlayRequest?>(null)
    val pending: StateFlow<PlayRequest?> = _pending.asStateFlow()

    /** 播放状态（/status 与常驻通知都读它）；由播放界面持续更新 */
    private val _status = MutableStateFlow(StatusResponse())
    val status: StateFlow<StatusResponse> = _status.asStateFlow()

    /** 平板发的遥控指令（play/pause/seek/stop）；服务收到转投这里，播放界面收集执行 */
    private val _control = MutableSharedFlow<ControlRequest>(extraBufferCapacity = 16)
    val control: SharedFlow<ControlRequest> = _control.asSharedFlow()

    /**
     * 连播队列的补推（平板解析好后续几集的直链后异步送过来）。
     * StateFlow：界面可能晚于队列到达才组合（比如正在系统播放器/待机），要能读到最近一份；
     * 是否采纳由播放页按锚点判断（见 mergeQueue）。
     */
    private val _queue = MutableStateFlow<QueueRequest?>(null)
    val queue: StateFlow<QueueRequest?> = _queue.asStateFlow()

    /**
     * 平板推来的外挂字幕内容（在线字幕库代下载好的 base64）。
     * SharedFlow 即发即弃：没有播放页时收到就是没人放——字幕跟着"当前这一集"走，
     * 没在播就不存在该挂给谁的问题。
     */
    private val _subsPush = MutableSharedFlow<SubsPushRequest>(extraBufferCapacity = 4)
    val subsPush: SharedFlow<SubsPushRequest> = _subsPush.asSharedFlow()

    fun post(req: PlayRequest) {
        // 新一场投屏：上一场的队列不能留着（锚点虽然也会挡，但别给脏数据机会）
        _queue.value = null
        _pending.value = req
    }

    /** 界面已经接手这条请求 */
    fun consume() {
        _pending.value = null
    }

    fun updateStatus(s: StatusResponse) {
        _status.value = s
    }

    fun postControl(c: ControlRequest) {
        _control.tryEmit(c)
    }

    fun postQueue(q: QueueRequest) {
        _queue.value = q
    }

    fun postSubsPush(s: SubsPushRequest) {
        _subsPush.tryEmit(s)
    }

    /** 播放结束/退出播放页：回到待机语义（/status 变 idle，通知文案跟着回去） */
    fun clearPlayback() {
        _status.value = StatusResponse(state = TvProtocol.STATE_IDLE)
    }
}

package com.open115.pad.data

import com.open115.pad.util.Torrent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 云盘上的 `.torrent` → 115 云下载（离线）。
 *
 * 开放平台**没有**"上传种子文件建 BT 任务"的接口（只有 `add_task_urls`，文档 §40），
 * 所以流程只能是：取种子文件的 115 直链 → 把那几十 KB 读回本地 → 算出 info_hash →
 * 拼磁力链 → 交给云下载。种子里的 tracker 由 [Torrent.buildMagnet] 一并带进磁力链，
 * 私有种子只能靠它找到 peer。
 *
 * 失败一律抛 [IllegalArgumentException]，消息是能直接给用户看的人话（调用方展示即可）。
 */
object CloudTorrent {

    /** 种子文件本身几十~几百 KB。超过这个数说明拿到的不是种子（比如被重定向到了 HTML 页面） */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * 读直链并解析出种子信息。
     *
     * @param url 115 直链，由 ImageUrlResolver.downloadUrl(pickCode) 解析得到
     */
    suspend fun read(client: OkHttpClient, url: String): Torrent.TorrentInfo = withContext(Dispatchers.IO) {
        val resp = try {
            client.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: Exception) {
            throw IllegalArgumentException("读取种子失败：${e.message ?: "网络错误"}")
        }
        resp.use { r ->
            if (!r.isSuccessful) throw IllegalArgumentException("读取种子失败（HTTP ${r.code}）")
            val body = r.body ?: throw IllegalArgumentException("读取种子失败：响应为空")
            // 多读 1 字节用于判断"是否还有后续"，服务端不给 Content-Length 时也能截住
            val buf = ByteArray(MAX_BYTES + 1)
            var read = 0
            body.byteStream().use { ins ->
                while (read < buf.size) {
                    val n = ins.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
            }
            if (read > MAX_BYTES) {
                throw IllegalArgumentException("文件超过 ${MAX_BYTES / 1024 / 1024}MB，不像是种子文件")
            }
            Torrent.parse(buf.copyOf(read))
        }
    }
}

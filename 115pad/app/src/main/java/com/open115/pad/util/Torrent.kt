package com.open115.pad.util

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 极简 bencode 解析器，只为 .torrent 提取 info_hash / 名称 / 文件数 / 总大小。
 * info_hash = SHA-1(info 字典的原始字节)，解析时记录 info 字段的字节区间。
 */
object Torrent {

    data class TorrentInfo(
        val infoHash: String, // 40 位十六进制小写
        val name: String?, // UTF-8 名称
        val totalSize: Long,
        val fileCount: Int,
    )

    fun parse(bytes: ByteArray): TorrentInfo {
        if (bytes.size < 10 || bytes[0] != 'd'.code.toByte()) {
            throw IllegalArgumentException("不是有效的种子文件")
        }
        val dec = BDecoder(bytes)
        val root = try {
            dec.parseRoot()
        } catch (e: Exception) {
            throw IllegalArgumentException("种子解析失败")
        }
        if (dec.infoStart < 0 || dec.infoEnd <= dec.infoStart) {
            throw IllegalArgumentException("种子缺少 info 字段")
        }
        @Suppress("UNCHECKED_CAST")
        val info = root["info"] as? Map<String, Any?>
            ?: throw IllegalArgumentException("种子缺少 info 字段")
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(bytes.copyOfRange(dec.infoStart, dec.infoEnd))
        val infoHash = hash.joinToString("") { String.format("%02x", it) }

        val name = (info["name"] as? ByteArray)?.toString(Charsets.UTF_8)
        val files = info["files"] as? List<*>
        val totalSize: Long
        val fileCount: Int
        if (files != null) {
            var sum = 0L
            files.forEach { f -> sum += (f as? Map<*, *>)?.get("length").asBencodeLong() }
            totalSize = sum
            fileCount = files.size
        } else {
            totalSize = info["length"].asBencodeLong()
            fileCount = 1
        }
        if (totalSize <= 0) throw IllegalArgumentException("种子内容为空")
        return TorrentInfo(infoHash, name?.takeIf { it.isNotBlank() }, totalSize, fileCount)
    }

    /** 生成 115 云下载可接受的磁力链 */
    fun buildMagnet(info: TorrentInfo): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(info.infoHash)
        info.name?.let {
            sb.append("&dn=")
                .append(URLEncoder.encode(it, "UTF-8").replace("+", "%20"))
        }
        return sb.toString()
    }

    private fun Any?.asBencodeLong(): Long = when (this) {
        is Long -> this
        is Int -> toLong()
        is ByteArray -> String(this, Charsets.US_ASCII).toLongOrNull() ?: 0L
        else -> 0L
    }

    private class BDecoder(val b: ByteArray) {
        var pos = 0
        var infoStart = -1
        var infoEnd = -1

        fun parseRoot(): Map<String, Any?> = parseDict()

        private fun peek(): Char = b[pos].toInt().toChar()

        private fun parseDict(): MutableMap<String, Any?> {
            expect('d')
            val map = LinkedHashMap<String, Any?>()
            while (peek() != 'e') {
                val key = parseBytes()
                val valueStart = pos
                val value = parseValue()
                if (String(key, Charsets.US_ASCII) == "info") {
                    infoStart = valueStart
                    infoEnd = pos
                }
                map[String(key, Charsets.UTF_8)] = value
            }
            expect('e')
            return map
        }

        private fun parseList(): MutableList<Any?> {
            expect('l')
            val list = ArrayList<Any?>()
            while (peek() != 'e') list.add(parseValue())
            expect('e')
            return list
        }

        private fun parseValue(): Any? = when (peek()) {
            'd' -> parseDict()
            'l' -> parseList()
            'i' -> parseInt()
            else -> parseBytes()
        }

        private fun parseInt(): Long {
            expect('i')
            var v = 0L
            var neg = false
            if (peek() == '-') {
                neg = true
                pos++
            }
            while (peek() != 'e') {
                val c = peek()
                if (c < '0' || c > '9') throw IllegalArgumentException("非法整数")
                v = v * 10 + (c - '0')
                pos++
            }
            pos++ // 跳过 'e'
            return if (neg) -v else v
        }

        private fun parseBytes(): ByteArray {
            var len = 0
            while (peek() != ':') {
                val c = peek()
                if (c < '0' || c > '9') throw IllegalArgumentException("非法字符串长度")
                len = len * 10 + (c - '0')
                pos++
            }
            pos++ // 跳过 ':'
            if (len < 0 || pos + len > b.size) throw IllegalArgumentException("字符串越界")
            val arr = b.copyOfRange(pos, pos + len)
            pos += len
            return arr
        }

        private fun expect(c: Char) {
            if (pos >= b.size || b[pos].toInt().toChar() != c) {
                throw IllegalArgumentException("bencode 结构错误")
            }
            pos++
        }
    }
}

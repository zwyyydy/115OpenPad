package com.open115.pad.data

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface AuthApi {
    @FormUrlEncoded
    @POST("open/authDeviceCode")
    suspend fun authDeviceCode(
        @Field("client_id") clientId: String,
        @Field("code_challenge") codeChallenge: String,
        @Field("code_challenge_method") method: String = "sha256",
    ): ApiEnvelope<DeviceCodeData>

    @FormUrlEncoded
    @POST("open/deviceCodeToToken")
    suspend fun deviceCodeToToken(
        @Field("uid") uid: String,
        @Field("code_verifier") codeVerifier: String,
    ): ApiEnvelope<TokenData>

    @FormUrlEncoded
    @POST("open/refreshToken")
    suspend fun refreshToken(@Field("refresh_token") refreshToken: String): ApiEnvelope<TokenData>
}

interface QrApi {
    @GET("get/status/")
    suspend fun qrStatus(
        @Query("uid") uid: String,
        @Query("time") time: String,
        @Query("sign") sign: String,
    ): ApiEnvelope<QrStatusData>
}

interface OpenApi {
    // 全部端点返回原始 JsonObject：115 的错误响应里 data 可能是 []，
    // 强类型模型会在报错时解析崩溃、掩盖真实 message；统一在 RawParsers 手动解析。

    @GET("open/user/info")
    suspend fun userInfo(): JsonObject

    @GET("open/ufile/files")
    suspend fun files(
        @Query("cid") cid: String = "0",
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("o") order: String? = null,
        @Query("asc") asc: Int? = null,
        // 必须显式传 1，否则排序会被服务端忽略：custom_order 默认 0 = "使用记忆排序，
        // 自定义排序失效"，此时 o/asc 完全不生效（官方文档枚举，已用真实接口复验）。
        // 1 = 用自定义排序且保持文件夹置顶（2 会打乱置顶）。
        @Query("custom_order") customOrder: Int = 1,
        @Query("type") type: Int? = null,
        @Query("suffix") suffix: String? = null,
        @Query("star") star: Int? = null,
        @Query("show_dir") showDir: Int = 1,
    ): JsonObject

    @GET("open/ufile/search")
    suspend fun search(
        @Query("search_value") keyword: String,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("type") type: Int? = null,
        // 目录范围：0/不传 = 全盘；具体 cid = 只搜该目录（含子目录）
        @Query("cid") cid: String? = null,
        // 时间范围（YYYY-MM-DD）；只传开始日期即可，服务端按">= gte_day"
        @Query("gte_day") gteDay: String? = null,
        @Query("lte_day") lteDay: String? = null,
        // 1=只文件夹 2=只文件 0=全部
        @Query("fc") fc: Int? = null,
    ): JsonObject

    @FormUrlEncoded
    @POST("open/folder/add")
    suspend fun addFolder(@Field("pid") pid: String, @Field("file_name") name: String): JsonObject

    @FormUrlEncoded
    @POST("open/ufile/update")
    suspend fun updateFile(
        @Field("file_id") fileId: String,
        @Field("file_name") newName: String? = null,
        @Field("star") star: Int? = null,
    ): JsonObject

    @FormUrlEncoded
    @POST("open/ufile/move")
    suspend fun moveFiles(@Field("file_ids") fileIds: String, @Field("to_cid") toCid: String): JsonObject

    @FormUrlEncoded
    @POST("open/ufile/copy")
    suspend fun copyFiles(
        @Field("pid") pid: String,
        @Field("file_id") fileIds: String,
        @Field("nodupli") noDupli: Int = 1,
    ): JsonObject

    @FormUrlEncoded
    @POST("open/ufile/delete")
    suspend fun deleteFiles(
        @Field("file_ids") fileIds: String,
        @Field("parent_id") parentId: String? = null,
    ): JsonObject

    @FormUrlEncoded
    @POST("open/ufile/downurl")
    suspend fun downUrl(@Field("pick_code") pickCode: String): JsonObject

    @GET("open/video/play")
    suspend fun videoPlay(@Query("pick_code") pickCode: String): JsonObject

    @GET("open/video/history")
    suspend fun videoHistoryGet(@Query("pick_code") pickCode: String): JsonObject

    @FormUrlEncoded
    @POST("open/video/history")
    suspend fun videoHistorySave(
        @Field("pick_code") pickCode: String,
        @Field("time") time: Long,
        @Field("watch_end") watchEnd: Int = 0,
    ): JsonObject

    @GET("open/video/subtitle")
    suspend fun videoSubtitles(@Query("pick_code") pickCode: String): JsonObject

    // ---------------- 云下载 ----------------

    @GET("open/offline/get_task_list")
    suspend fun offlineTasks(@Query("page") page: Int = 1): JsonObject

    @FormUrlEncoded
    @POST("open/offline/add_task_urls")
    suspend fun offlineAddUrls(
        @Field("urls") urls: String,
        @Field("wp_path_id") wpPathId: String = "0",
    ): JsonObject

    @FormUrlEncoded
    @POST("open/offline/del_task")
    suspend fun offlineDelete(
        @Field("info_hash") infoHash: String,
        @Field("del_source_file") delSourceFile: Int = 0,
    ): JsonObject

    @GET("open/offline/get_quota_info")
    suspend fun offlineQuota(): JsonObject

    /** 清空任务。flag：0 已完成 / 1 全部 / 2 失败 / 3 进行中 / 4 已完成并删源文件 / 5 全部并删源文件 */
    @FormUrlEncoded
    @POST("open/offline/clear_task")
    suspend fun offlineClear(@Field("flag") flag: Int): JsonObject

    /**
     * 解析已上传到云盘的种子：返回文件名、总大小、info_hash 与**完整文件清单**。
     * `torrent_sha1` 是种子文件本身的 SHA1（不是 info_hash），必须与上传的字节一致。
     */
    @FormUrlEncoded
    @POST("open/offline/torrent")
    suspend fun offlineTorrent(
        @Field("torrent_sha1") torrentSha1: String,
        @Field("pick_code") pickCode: String,
    ): JsonObject

    /**
     * 按已解析的种子建 BT 任务。
     * `wanted` 是**从 0 开始**的文件索引，逗号分隔；`save_path` 是相对 `wp_path_id` 的路径。
     */
    @FormUrlEncoded
    @POST("open/offline/add_task_bt")
    suspend fun offlineAddBt(
        @Field("info_hash") infoHash: String,
        @Field("wanted") wanted: String,
        @Field("save_path") savePath: String,
        @Field("torrent_sha1") torrentSha1: String,
        @Field("pick_code") pickCode: String,
        @Field("wp_path_id") wpPathId: String,
    ): JsonObject

    // ---------------- 上传 ----------------

    @GET("open/upload/get_token")
    suspend fun uploadGetToken(): JsonObject

    @FormUrlEncoded
    @POST("open/upload/init")
    suspend fun uploadInit(
        @Field("file_name") fileName: String,
        @Field("file_size") fileSize: Long,
        @Field("target") target: String,
        @Field("fileid") fileId: String,
        @Field("preid") preid: String? = null,
        @Field("pick_code") pickCode: String? = null,
        @Field("sign_key") signKey: String? = null,
        @Field("sign_val") signVal: String? = null,
    ): JsonObject

    /** 断点续传调度：按 pick_code 换回新的 bucket/object/callback（原调度可能已过期） */
    @FormUrlEncoded
    @POST("open/upload/resume")
    suspend fun uploadResume(
        @Field("file_size") fileSize: Long,
        @Field("target") target: String,
        @Field("fileid") fileId: String,
        @Field("pick_code") pickCode: String,
    ): JsonObject

    // ---------------- 回收站 ----------------

    @GET("open/rb/list")
    suspend fun recycleList(
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
    ): JsonObject

    @FormUrlEncoded
    @POST("open/rb/revert")
    suspend fun recycleRevert(@Field("tid") tids: String): JsonObject

    @FormUrlEncoded
    @POST("open/rb/del")
    suspend fun recycleDelete(@Field("tid") tids: String?): JsonObject
}

/** 方便类型引用的别名 */
typealias JsonObject = kotlinx.serialization.json.JsonObject
typealias JsonElement = kotlinx.serialization.json.JsonElement
typealias JsonPrimitive = kotlinx.serialization.json.JsonPrimitive
typealias JsonArray = kotlinx.serialization.json.JsonArray
typealias JsonNull = kotlinx.serialization.json.JsonNull

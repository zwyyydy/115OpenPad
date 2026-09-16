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

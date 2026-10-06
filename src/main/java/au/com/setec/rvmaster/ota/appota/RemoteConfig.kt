package au.com.setec.rvmaster.ota.appota

import com.google.gson.annotations.SerializedName


data class RemoteConfigResponse(
    @SerializedName("code")
    val code: Int?,
    @SerializedName("version")
    var version: String?,
    @SerializedName("package")
    var packageName: String?,
    @SerializedName("apkFileName")
    var apkFileName: String?,
    @SerializedName("type")
    var type: String = RemoteConfigType.CRITICAL.name,
    @SerializedName("checksum")
    var checksum: String?,
    @SerializedName("storageType")
    var storageType: String? = RemoteConfigStorageType.APPWRITE.name,
    @SerializedName("r2DevUrl")
    var r2DevUrl: String?
)

enum class RemoteConfigType { CRITICAL, MAINTENANCE, PROMPT }

enum class RemoteConfigStorageType { APPWRITE, CLOUDFLARE, FIREBASE }

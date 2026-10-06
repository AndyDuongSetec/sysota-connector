package au.com.setec.rvmaster

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.security.MessageDigest

object Util {
    @JvmStatic
    fun isInternetConnected(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }
}

fun File.calculateChecksum(algorithm: String = "MD5"): String {
    val md = MessageDigest.getInstance(algorithm)
    this.inputStream().use { fis ->
        val buffer = ByteArray(32768)
        var bytesRead: Int
        while (fis.read(buffer).also { bytesRead = it } != -1) {
            md.update(buffer, 0, bytesRead)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

package au.com.setec.rvmaster.autodatetime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import au.com.setec.rvmaster.TAG_AUTO_DATE_TIME
import au.com.setec.rvmaster.Util
import au.com.setec.rvmaster.logD
import au.com.setec.rvmaster.logE
import au.com.setec.rvmaster.logI
import au.com.setec.rvmaster.logW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.net.ntp.NTPUDPClient
import org.json.JSONObject
import java.net.InetAddress
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class AutoDateTimeUseCase @Inject constructor(
    private val context: Context,
    client: OkHttpClient = OkHttpClient(),
    @param:Named("COMMANDER_PACKAGE_PREFIX") val commanderPackagePrefix: String = DEFAULT_COMMANDER_PACKAGE_PREFIX,
) {

    private val client: OkHttpClient = client.newBuilder()
        .connectTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val commanderReceiverClass: String
        get() = "$commanderPackagePrefix.SystemCommandReceiver"

    private val actionSetTimeZone: String
        get() = "$commanderPackagePrefix.SET_TIME_ZONE"

    private val actionSetTime: String
        get() = "$commanderPackagePrefix.SET_TIME"

    private companion object {
        private const val DEFAULT_COMMANDER_PACKAGE_PREFIX = "com.system.nexussyscommander"
        private const val IP_API_URL = "http://ip-api.com/json/"
        private val NTP_HOSTS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
        private const val NTP_TIMEOUT_MS = 3000
        private const val HTTP_TIMEOUT_MS = 5000L

        private const val EXTRA_TIMEZONE = "timezone"
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_YEAR = "year"
        private const val EXTRA_MONTH = "month"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_HOUR = "hour"
        private const val EXTRA_MINUTE = "minute"
        private const val EXTRA_SECOND = "second"
    }

    data class SystemTime(
        val year: Int,
        val month: Int, // 1–12
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
    ) {
        companion object {
            fun fromCalendar(calendar: Calendar): SystemTime = SystemTime(
                year = calendar.get(Calendar.YEAR),
                month = calendar.get(Calendar.MONTH) + 1,
                day = calendar.get(Calendar.DAY_OF_MONTH),
                hour = calendar.get(Calendar.HOUR_OF_DAY),
                minute = calendar.get(Calendar.MINUTE),
                second = calendar.get(Calendar.SECOND),
            )
        }

        fun toFormattedString(): String =
            "%04d-%02d-%02d %02d:%02d:%02d".format(year, month, day, hour, minute, second)
    }

    /**
     * Entry point: auto update both system timezone and system time.
     * @param packageName Package name sent as [EXTRA_PACKAGE_NAME] extra (defaults to [Context.getPackageName]).
     * @return [Boolean] indicating whether both timezone and time updates succeeded.
     */
    suspend operator fun invoke(packageName: String = context.packageName): Boolean = withContext(Dispatchers.IO) {
        try {
            logI("Auto-updating Date & Time (Package: '$packageName')...", TAG_AUTO_DATE_TIME)
            if (!Util.isInternetConnected(context)) {
                logW("Internet is not accessible; skipping auto Date & Time update", TAG_AUTO_DATE_TIME)
                return@withContext false
            }

            val timezone = getTimeZone()
            val timezoneUpdated = if (!timezone.isNullOrBlank()) {
                changeSystemTimeZone(timezone, packageName)
                true
            } else {
                logW("Failed to retrieve a valid timezone from API", TAG_AUTO_DATE_TIME)
                false
            }

            val calendar = getNetworkTime(timezone)
            val timeUpdated = if (calendar != null) {
                val systemTime = SystemTime.fromCalendar(calendar)
                changeSystemTime(systemTime, packageName)
                true
            } else {
                logW("Failed to retrieve network time from NTP hosts", TAG_AUTO_DATE_TIME)
                false
            }

            logI("Auto Date & Time sync finished (Timezone updated: $timezoneUpdated, Time updated: $timeUpdated)", TAG_AUTO_DATE_TIME)
            timezoneUpdated && timeUpdated
        } catch (ex: Throwable) {
            logE("Exception during auto Date & Time update operation", ex, TAG_AUTO_DATE_TIME)
            false
        }
    }

    /** API call to get timezone based on public IP */
    private suspend fun getTimeZone(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(IP_API_URL)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logW("IP API request failed with HTTP status: ${response.code}", TAG_AUTO_DATE_TIME)
                    return@use null
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    logW("IP API response body is null or empty", TAG_AUTO_DATE_TIME)
                    return@use null
                }

                val json = JSONObject(body)
                if (json.optString("status") == "fail") {
                    logW("IP API status fail: '${json.optString("message")}'", TAG_AUTO_DATE_TIME)
                    return@use null
                }

                val timezone = json.optString("timezone").takeIf { it.isNotBlank() && it != "null" }
                if (timezone != null) {
                    logD("Fetched timezone: '$timezone'", TAG_AUTO_DATE_TIME)
                }
                timezone
            }
        } catch (ex: Throwable) {
            logW("Exception while fetching timezone from IP API", ex, TAG_AUTO_DATE_TIME)
            null
        }
    }

    /** Retrieves time from NTP servers with automatic failover */
    private suspend fun getNetworkTime(timezone: String? = null): Calendar? = withContext(Dispatchers.IO) {
        val tz = timezone?.takeIf { it.isNotBlank() }?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        for (host in NTP_HOSTS) {
            val calendar = fetchNtpTimeFromHost(host, tz)
            if (calendar != null) {
                logD("Fetched network time from host: $host (TimeZone: ${tz.id})", TAG_AUTO_DATE_TIME)
                return@withContext calendar
            }
        }
        logW("Exhausted all NTP hosts without obtaining time", TAG_AUTO_DATE_TIME)
        null
    }

    @Suppress("DEPRECATION")
    private fun fetchNtpTimeFromHost(host: String, timeZone: TimeZone): Calendar? {
        var ntpClient: NTPUDPClient? = null
        return try {
            ntpClient = NTPUDPClient().apply {
                defaultTimeout = NTP_TIMEOUT_MS
            }
            ntpClient.open()
            val address = InetAddress.getByName(host)
            val response = ntpClient.getTime(address)
            val utcMillis = response.message.transmitTimeStamp.time
            Calendar.getInstance(timeZone).apply { timeInMillis = utcMillis }
        } catch (ex: Throwable) {
            logW("Failed to fetch time from $host: ${ex.message}", TAG_AUTO_DATE_TIME)
            null
        } finally {
            runCatching { ntpClient?.close() }
        }
    }

    private fun changeSystemTimeZone(timezone: String, packageName: String) {
        logI("Broadcasting timezone change to '$timezone'", TAG_AUTO_DATE_TIME)
        val intent = Intent(actionSetTimeZone).apply {
            component = ComponentName(commanderPackagePrefix, commanderReceiverClass)
            putExtra(EXTRA_TIMEZONE, timezone)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        context.sendBroadcast(intent)
    }

    private fun changeSystemTime(systemTime: SystemTime, packageName: String) {
        logI("Broadcasting system time change to ${systemTime.toFormattedString()}", TAG_AUTO_DATE_TIME)
        val intent = Intent(actionSetTime).apply {
            component = ComponentName(commanderPackagePrefix, commanderReceiverClass)
            putExtra(EXTRA_YEAR, systemTime.year)
            putExtra(EXTRA_MONTH, systemTime.month - 1) // SystemCommander expects 0–11 (Calendar.MONTH)
            putExtra(EXTRA_DAY, systemTime.day)
            putExtra(EXTRA_HOUR, systemTime.hour)
            putExtra(EXTRA_MINUTE, systemTime.minute)
            putExtra(EXTRA_SECOND, systemTime.second)
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        context.sendBroadcast(intent)
    }

}

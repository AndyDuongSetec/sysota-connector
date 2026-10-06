package au.com.setec.rvmaster

import android.util.Log
import timber.log.Timber

const val TAG_APP_OTA = "AppOTA"
const val TAG_AUTO_DATE_TIME = "AutoDateTime"

fun logD(message: String, tag: String = TAG_APP_OTA) {
    if (Timber.treeCount > 0) {
        Timber.tag(tag).d(message)
    } else {
        Log.d(tag, message)
    }
}

fun logI(message: String, tag: String = TAG_APP_OTA) {
    if (Timber.treeCount > 0) {
        Timber.tag(tag).i(message)
    } else {
        Log.i(tag, message)
    }
}

fun logW(message: String, throwable: Throwable? = null, tag: String = TAG_APP_OTA) {
    if (Timber.treeCount > 0) {
        if (throwable != null) {
            Timber.tag(tag).w(throwable, message)
        } else {
            Timber.tag(tag).w(message)
        }
    } else {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }
}

fun logW(message: String, tag: String) {
    logW(message, null, tag)
}

fun logE(message: String, throwable: Throwable? = null, tag: String = TAG_APP_OTA) {
    if (Timber.treeCount > 0) {
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message)
        } else {
            Timber.tag(tag).e(message)
        }
    } else {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}

fun logE(message: String, tag: String) {
    logE(message, null, tag)
}

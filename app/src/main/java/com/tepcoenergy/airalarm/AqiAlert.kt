package com.tepcoenergy.airalarm

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

enum class AqiProfile(val title: String, val maxAqi: Int) {
    CHILD("کودکان و نوجوانان", 100),
    PREGNANT("زنان باردار", 100),
    OLDER_ADULT("سالمندان", 100),
    RESPIRATORY("افراد مبتلا به آسم یا بیماری ریوی", 100),
    HEART("افراد مبتلا به بیماری قلبی", 100),
    GENERAL("سایر بزرگسالان", 150);

    val label get() = "$title · بازهٔ تنظیم هشدار: ۰ تا ${maxAqi.faDigits()}"
}

fun Int.faDigits(): String = toString().map {
    if (it in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it
}.joinToString("")

object AqiProfiles {
    const val KEY = "aqi_alert_profile"

    fun selected(prefs: SharedPreferences): AqiProfile {
        val saved = prefs.getString(KEY, null)
        return AqiProfile.values().firstOrNull { it.name == saved } ?: AqiProfile.CHILD
    }

    fun choose(activity: Activity, prefs: SharedPreferences, onSelected: (AqiProfile) -> Unit) {
        val profiles = AqiProfile.values()
        AlertDialog.Builder(activity)
            .setTitle("گروه شما و بازهٔ تنظیم هشدار")
            .setSingleChoiceItems(profiles.map { it.label }.toTypedArray(), selected(prefs).ordinal) {
                dialog, index ->
                val profile = profiles[index]
                prefs.edit().putString(KEY, profile.name).apply()
                dialog.dismiss()
                onSelected(profile)
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    fun requestNotificationPermission(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestCode)
        }
    }
}

data class AqiAlertStatus(val profile: AqiProfile, val aqi: Int?) {
    val outsideRange = aqi != null && aqi > profile.maxAqi

    val message: String
        get() = when {
            aqi == null -> "هنوز دادهٔ معتبر کیفیت هوا برای این ایستگاه دریافت نشده است."
            outsideRange -> "کیفیت هوا خارج از بازهٔ تنظیم هشدار شماست."
            else -> "آخرین شاخص کیفیت هوا در بازهٔ تنظیم هشدار شماست."
        }

    val details: String
        get() = if (aqi == null) profile.label else
            "${profile.label}\nآخرین AQI ایستگاه: ${aqi.faDigits()}"
}

/** Keep one instance in AirQualityService. Invoke and dispose on the main thread. */
class AqiAlert(private val context: Context, private val prefs: SharedPreferences) {
    private var player: MediaPlayer? = null

    fun evaluate(lastValidAqi: Int?, allowSound: Boolean): AqiAlertStatus {
        val status = AqiAlertStatus(AqiProfiles.selected(prefs), lastValidAqi?.takeIf { it >= 0 })
        if (!status.outsideRange) stop()
        if (status.outsideRange && allowSound && player == null && !inCooldown()) play()
        return status
    }

    fun close() = stop()

    private fun bootCount(): Int = if (Build.VERSION.SDK_INT >= 24) {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } else -1

    private fun inCooldown(): Boolean {
        val boot = bootCount()
        if (boot >= 0 && boot == prefs.getInt(LAST_BOOT, -1)) {
            return SystemClock.elapsedRealtime() < prefs.getLong(NEXT_ELAPSED, 0L)
        }

        val remaining = prefs.getLong(NEXT_WALL, 0L) - System.currentTimeMillis()
        return remaining > 0L
    }

    private fun play() {
        val media = MediaPlayer()
        player = media
        try {
            val audio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            media.setAudioAttributes(audio)
            media.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            context.resources.openRawResourceFd(R.raw.aqi_alarm).use { recording ->
                media.setDataSource(recording.fileDescriptor, recording.startOffset, recording.length)
            }
            media.isLooping = false
            media.setOnCompletionListener { completed ->
                if (player === completed) {
                    saveCooldown(COOLDOWN_MS)
                    stop()
                }
            }
            media.setOnErrorListener { failed, what, extra ->
                Log.w(TAG, "Alarm playback failed: $what/$extra")
                if (player === failed) stop()
                true
            }
            media.setOnPreparedListener { prepared ->
                if (player === prepared) startPrepared(prepared)
            }
            media.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to prepare AQI alarm", error)
            stop()
        }
    }

    private fun startPrepared(media: MediaPlayer) {
        try {
            val durationMs = media.duration.toLong().coerceAtLeast(0L)
            media.start()
            // Persist a deadline even if the process ends before completion.
            saveCooldown(durationMs + COOLDOWN_MS)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to start AQI alarm", error)
            stop()
        }
    }

    private fun saveCooldown(delayMs: Long) {
        prefs.edit()
            .putInt(LAST_BOOT, bootCount())
            .putLong(NEXT_ELAPSED, SystemClock.elapsedRealtime() + delayMs)
            .putLong(NEXT_WALL, System.currentTimeMillis() + delayMs)
            .apply()
    }

    private fun stop() {
        val active = player
        player = null
        active?.release()
    }

    private companion object {
        const val TAG = "AqiAlert"
        const val COOLDOWN_MS = 3_600_000L
        const val LAST_BOOT = "aqi_alarm_last_boot"
        const val NEXT_ELAPSED = "aqi_alarm_next_elapsed"
        const val NEXT_WALL = "aqi_alarm_next_wall"
    }
}

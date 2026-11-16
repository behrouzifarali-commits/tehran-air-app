package com.tepcoenergy.airalarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class AirQualityService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private val intervalMillis = 10 * 60 * 1000L // 10 دقیقه

    private val periodicTask = object : Runnable {
        override fun run() {
            fetchDataFromTepco(forceRefresh = false)
            handler.postDelayed(this, intervalMillis)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_SERVICE" -> {
                stopAlarmAndService()
                return START_NOT_STICKY
            }
            "REFRESH_NOW" -> {
                fetchDataFromTepco(forceRefresh = true)
                return START_STICKY
            }
        }

        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("service_running", true).apply()

        startForeground(101, buildNotification("پایش هوای تهران فعال است (بروزرسانی هر ۱۰ دقیقه)"))

        handler.removeCallbacks(periodicTask)
        handler.post(periodicTask)

        return START_STICKY
    }

    private fun fetchDataFromTepco(forceRefresh: Boolean) {
        thread {
            try {
                val baseUrl = "https://tepcoenergy.com/wp-json/tepco-air/v1/aqi"
                val urlString = if (forceRefresh) "$baseUrl?refresh=1&_t=${System.currentTimeMillis()}" else "$baseUrl?_t=${System.currentTimeMillis()}"
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    if (json.optString("status") == "ok") {
                        val updatedAt = json.optString("updated_at", "")
                        val dataArray = json.getJSONArray("data")

                        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
                        val selectedStation = prefs.getString("selected_station", "") ?: ""
                        val threshold = prefs.getInt("threshold", 150)

                        var targetStation = ""
                        var targetAqi = -1

                        for (i in 0 until dataArray.length()) {
                            val obj = dataArray.getJSONObject(i)
                            val stName = obj.getString("station")
                            val stAqi = obj.getInt("aqi")
                            if (i == 0) {
                                targetStation = stName
                                targetAqi = stAqi
                            }
                            if (selectedStation.isNotEmpty() && stName == selectedStation) {
                                targetStation = stName
                                targetAqi = stAqi
                                break
                            }
                        }

                        val isAlarm = targetAqi > threshold
                        prefs.edit()
                            .putString("stations_json", dataArray.toString())
                            .putString("updated_at", updatedAt)
                            .putInt("current_aqi", targetAqi)
                            .putString("active_station", targetStation)
                            .putBoolean("is_alarm", isAlarm)
                            .apply()

                        sendBroadcast(Intent("TEPCO_AQI_UPDATED"))

                        if (isAlarm) {
                            triggerAlarm(targetStation, targetAqi, threshold)
                        } else {
                            stopSirenOnly()
                            updateNotification("$targetStation: شاخص $targetAqi (حد مجاز: $threshold)")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun triggerAlarm(station: String, aqi: Int, threshold: Int) {
        handler.post {
            updateNotification("⚠️ هشدار آلودگی! $station به شاخص $aqi رسید (مجاز: $threshold)")
            try {
                if (ringtone == null) {
                    var alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    if (alarmUri == null) {
                        alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    }
                    ringtone = RingtoneManager.getRingtone(applicationContext, alarmUri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ringtone?.audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    }
                }
                if (ringtone?.isPlaying == false) {
                    ringtone?.play()
                }
                vibratePhone()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun vibratePhone() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(1000)
        }
    }

    private fun stopSirenOnly() {
        handler.post {
            ringtone?.stop()
        }
    }

    private fun stopAlarmAndService() {
        handler.removeCallbacks(periodicTask)
        ringtone?.stop()
        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("service_running", false).putBoolean("is_alarm", false).apply()
        sendBroadcast(Intent("TEPCO_AQI_UPDATED"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(101, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "TEPCO_AIR_CHANNEL")
            .setContentTitle("پایش آلودگی هوای تهران - TEPCO")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "TEPCO_AIR_CHANNEL",
                "سرویس پایش هوای تهران",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

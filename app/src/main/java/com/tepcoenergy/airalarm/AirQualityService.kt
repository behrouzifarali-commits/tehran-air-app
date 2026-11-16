package com.tepcoenergy.airalarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class AirQualityService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var aqiAlert: AqiAlert
    private var monitoring = false
    private var requestNumber = 0
    private val intervalMillis = 10 * 60 * 1000L

    private val periodicTask = object : Runnable {
        override fun run() {
            fetchDataFromTepco(forceRefresh = false)
            handler.postDelayed(this, intervalMillis)
        }
    }

    override fun onCreate() {
        super.onCreate()
        aqiAlert = AqiAlert(this, getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_SERVICE" -> {
                stopAlarmAndService()
                return START_NOT_STICKY
            }
            "REFRESH_NOW" -> {
                if (monitoring) {
                    val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
                    val cached = JSONArray(prefs.getString("stations_json", "[]") ?: "[]")
                    publishReading(cached, prefs.getString("updated_at", "") ?: "", allowSound = false)
                    fetchDataFromTepco(forceRefresh = true)
                    return START_STICKY
                }
            }
        }

        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("service_running", true).apply()

        monitoring = true
        startForeground(101, buildNotification("پایش هوای تهران فعال است (بروزرسانی هر ۱۰ دقیقه)"))

        val cached = JSONArray(prefs.getString("stations_json", "[]") ?: "[]")
        publishReading(cached, prefs.getString("updated_at", "") ?: "", allowSound = false)
        handler.removeCallbacks(periodicTask)
        handler.post(periodicTask)

        return START_STICKY
    }

    private fun fetchDataFromTepco(forceRefresh: Boolean) {
        val request = ++requestNumber
        thread {
            try {
                val baseUrl = "https://tepcoenergy.com/wp-json/tepco-air/v1/aqi"
                val urlString = if (forceRefresh) "$baseUrl?refresh=1&_t=${System.currentTimeMillis()}" else "$baseUrl?_t=${System.currentTimeMillis()}"
                val conn = URL(urlString).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000

                if (conn.responseCode == 200) {
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    if (json.optString("status") == "ok") {
                        val updatedAt = json.optString("updated_at", "")
                        val dataArray = json.getJSONArray("data")

                        handler.post {
                            if (monitoring && request == requestNumber) {
                                publishReading(dataArray, updatedAt, allowSound = true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun publishReading(data: JSONArray, updatedAt: String, allowSound: Boolean) {
        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
        val selected = prefs.getString("selected_station", "").orEmpty()
        val station = (0 until data.length()).asSequence()
            .mapNotNull { data.optJSONObject(it) }
            .firstOrNull { selected.isEmpty() || it.optString("station") == selected }
        val name = station?.optString("station").orEmpty()
        val aqi = station?.optInt("aqi", -1)?.takeIf { it >= 0 }
        val status = aqiAlert.evaluate(aqi, allowSound)

        prefs.edit()
            .putString("stations_json", data.toString())
            .putString("updated_at", updatedAt)
            .putInt("current_aqi", aqi ?: -1)
            .putString("active_station", name)
            .putBoolean("is_alarm", status.outsideRange)
            .apply()
        sendBroadcast(Intent("TEPCO_AQI_UPDATED").setPackage(packageName))
        updateNotification(listOf(status.message, name, status.details).filter { it.isNotEmpty() }.joinToString("\n"))
    }

    private fun stopAlarmAndService() {
        handler.removeCallbacks(periodicTask)
        monitoring = false
        aqiAlert.close()
        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("service_running", false).putBoolean("is_alarm", false).apply()
        sendBroadcast(Intent("TEPCO_AQI_UPDATED").setPackage(packageName))
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
        return NotificationCompat.Builder(this, "TEPCO_AIR_STATUS_SILENT")
            .setContentTitle("پایش آلودگی هوای تهران - توکا انرژی پارس")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "TEPCO_AIR_STATUS_SILENT",
                "سرویس پایش هوای تهران",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        monitoring = false
        handler.removeCallbacks(periodicTask)
        aqiAlert.close()
        getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
            .edit().putBoolean("service_running", false).apply()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

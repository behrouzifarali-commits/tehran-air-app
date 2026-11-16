package com.tepcoenergy.airalarm

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvAqiValue: TextView
    private lateinit var tvAqiStatus: TextView
    private lateinit var tvServerUpdate: TextView
    private lateinit var tvServiceState: TextView
    private lateinit var tvAlarmBanner: TextView
    private lateinit var spinnerStations: Spinner
    private lateinit var etThreshold: EditText

    private var stationNames = mutableListOf<String>()
    private var stationAqis = mutableListOf<Int>()
    private var isSpinnerProgrammatic = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadFromPrefsAndRender()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermission()

        tvAqiValue = findViewById(R.id.tvAqiValue)
        tvAqiStatus = findViewById(R.id.tvAqiStatus)
        tvServerUpdate = findViewById(R.id.tvServerUpdate)
        tvServiceState = findViewById(R.id.tvServiceState)
        tvAlarmBanner = findViewById(R.id.tvAlarmBanner)
        spinnerStations = findViewById(R.id.spinnerStations)
        etThreshold = findViewById(R.id.etThreshold)

        val btnStart = findViewById<Button>(R.id.btnStartService)
        val btnStop = findViewById<Button>(R.id.btnStopService)
        val btnRefresh = findViewById<Button>(R.id.btnRefreshNow)

        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
        etThreshold.setText(prefs.getInt("threshold", 150).toString())

        spinnerStations.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isSpinnerProgrammatic && position in stationNames.indices) {
                    val chosen = stationNames[position]
                    prefs.edit().putString("selected_station", chosen).apply()
                    displaySelectedAqi(stationAqis[position])
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnStart.setOnClickListener {
            val thr = etThreshold.text.toString().toIntOrNull() ?: 150
            prefs.edit().putInt("threshold", thr).apply()

            val intent = Intent(this, AirQualityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvServiceState.text = "وضعیت پایش پس‌زمینه: فعال (هر ۱۰ دقیقه)"
            Toast.makeText(this, "پایش خودکار فعال شد (حد مجاز: $thr)", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            val stopIntent = Intent(this, AirQualityService::class.java).apply {
                action = "STOP_SERVICE"
            }
            startService(stopIntent)
            tvServiceState.text = "وضعیت پایش پس‌زمینه: متوقف شد"
            tvAlarmBanner.visibility = View.GONE
            Toast.makeText(this, "آژیر و سرویس متوقف شدند", Toast.LENGTH_SHORT).show()
        }

        btnRefresh.setOnClickListener {
            val thr = etThreshold.text.toString().toIntOrNull() ?: 150
            prefs.edit().putInt("threshold", thr).apply()
            Toast.makeText(this, "در حال دریافت اطلاعات از tepcoenergy.com...", Toast.LENGTH_SHORT).show()
            fetchInitialData(forceRefresh = true)
        }

        loadFromPrefsAndRender()
        fetchInitialData(forceRefresh = false)
    }

    private fun fetchInitialData(forceRefresh: Boolean) {
        thread {
            try {
                val baseUrl = "https://tepcoenergy.com/wp-json/tepco-air/v1/aqi"
                val urlString = if (forceRefresh) "$baseUrl?refresh=1&_t=${System.currentTimeMillis()}" else "$baseUrl?_t=${System.currentTimeMillis()}"
                val conn = URL(urlString).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(text)
                    if (json.optString("status") == "ok") {
                        val updatedAt = json.optString("updated_at", "")
                        val arr = json.getJSONArray("data")
                        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("stations_json", arr.toString())
                            .putString("updated_at", updatedAt)
                            .apply()
                        runOnUiThread { loadFromPrefsAndRender() }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvAqiStatus.text = "خطا در اتصال به سرور"
                }
            }
        }
    }

    private fun loadFromPrefsAndRender() {
        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("stations_json", null)
        val updatedAt = prefs.getString("updated_at", "نامشخص")
        val savedStation = prefs.getString("selected_station", "") ?: ""
        val isRunning = prefs.getBoolean("service_running", false)
        val isAlarm = prefs.getBoolean("is_alarm", false)

        tvServerUpdate.text = "بروزرسانی سرور: $updatedAt"
        tvServiceState.text = if (isRunning) "وضعیت پایش پس‌زمینه: فعال (هر ۱۰ دقیقه)" else "وضعیت پایش پس‌زمینه: غیرفعال"
        tvAlarmBanner.visibility = if (isAlarm) View.VISIBLE else View.GONE

        if (jsonStr != null) {
            val arr = JSONArray(jsonStr)
            stationNames.clear()
            stationAqis.clear()
            val displayLabels = mutableListOf<String>()
            var selectedIdx = 0

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("station")
                val aqi = obj.getInt("aqi")
                stationNames.add(name)
                stationAqis.add(aqi)
                displayLabels.add("$name (شاخص: $aqi)")
                if (name == savedStation) {
                    selectedIdx = i
                }
            }

            isSpinnerProgrammatic = true
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayLabels)
            spinnerStations.adapter = adapter
            if (stationNames.isNotEmpty()) {
                spinnerStations.setSelection(selectedIdx)
                displaySelectedAqi(stationAqis[selectedIdx])
            }
            isSpinnerProgrammatic = false
        }
    }

    private fun displaySelectedAqi(aqi: Int) {
        tvAqiValue.text = aqi.toString()
        val (statusText, colorHex) = when {
            aqi <= 50 -> "پاک" to "#10B981"
            aqi <= 100 -> "قابل قبول" to "#F59E0B"
            aqi <= 150 -> "ناسالم برای گروه‌های حساس" to "#F97316"
            aqi <= 200 -> "ناسالم برای همه" to "#EF4444"
            aqi <= 300 -> "بسیار ناسالم" to "#A855F7"
            else -> "خطرناک" to "#991B1B"
        }
        tvAqiStatus.text = statusText
        tvAqiValue.setTextColor(Color.parseColor(colorHex))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("TEPCO_AQI_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
        loadFromPrefsAndRender()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(updateReceiver)
    }
}

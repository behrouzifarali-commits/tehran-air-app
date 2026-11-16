package com.tepcoenergy.airalarm

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvAqiValue: TextView
    private lateinit var tvAqiStatus: TextView
    private lateinit var tvServerUpdate: TextView
    private lateinit var tvLocationInfo: TextView
    private lateinit var tvServiceState: TextView
    private lateinit var tvAlarmBanner: TextView
    private lateinit var spinnerStations: Spinner
    private lateinit var btnAqiProfile: Button

    private lateinit var tabMonitorContainer: ScrollView
    private lateinit var tabAboutContainer: ScrollView
    private lateinit var bottomNav: BottomNavigationView

    private var stationNames = mutableListOf<String>()
    private var stationAqis = mutableListOf<Int>()
    private var isSpinnerProgrammatic = false

    private val knownStationCoords = listOf(
        Triple(listOf("مرکز", "انقلاب", "دانشگاه تهران", "تربیت مدرس", "ستاد بحران"), 35.7022, 51.3910),
        Triple(listOf("شریف", "آزادی", "طرشت", "غرب"), 35.7036, 51.3515),
        Triple(listOf("تجریش", "نیاوران", "اقدسیه", "دارآباد", "شمال"), 35.8050, 51.4340),
        Triple(listOf("پونک", "سعادت", "شهرک غرب", "شمال غرب"), 35.7660, 51.3450),
        Triple(listOf("تهرانپارس", "رسالت", "سرخه حصار", "پیروزی", "شرق"), 35.7244, 51.4912),
        Triple(listOf("شهرری", "بهمن", "فرمانداری ری", "جنوب"), 35.5935, 51.4362),
        Triple(listOf("شادآباد", "منطقه ۱۸", "یافت", "جنوب غرب", "فتح"), 35.6560, 51.3050),
        Triple(listOf("پاسداران", "گلبرگ", "نارمک"), 35.7480, 51.4650),
        Triple(listOf("چیتگر", "منطقه ۲۲", "وردآورد", "پیکان"), 35.7350, 51.2100),
        Triple(listOf("محلاتی", "مسعودیه", "منطقه ۱۵", "افسریه"), 35.6350, 51.4950)
    )

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadFromPrefsAndRender()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestNotificationPermission()

        tabMonitorContainer = findViewById(R.id.tabMonitorContainer)
        tabAboutContainer = findViewById(R.id.tabAboutContainer)
        bottomNav = findViewById(R.id.bottomNav)

        tvAqiValue = findViewById(R.id.tvAqiValue)
        tvAqiStatus = findViewById(R.id.tvAqiStatus)
        tvServerUpdate = findViewById(R.id.tvServerUpdate)
        tvLocationInfo = findViewById(R.id.tvLocationInfo)
        tvServiceState = findViewById(R.id.tvServiceState)
        tvAlarmBanner = findViewById(R.id.tvAlarmBanner)
        spinnerStations = findViewById(R.id.spinnerStations)
        btnAqiProfile = findViewById(R.id.btnAqiProfile)

        val btnStart = findViewById<Button>(R.id.btnStartService)
        val btnStop = findViewById<Button>(R.id.btnStopService)
        val btnRefresh = findViewById<Button>(R.id.btnRefreshNow)
        val btnNearest = findViewById<Button>(R.id.btnNearestStation)
        val btnWebsite = findViewById<Button>(R.id.btnVisitWebsite)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_monitor -> {
                    tabMonitorContainer.visibility = View.VISIBLE
                    tabAboutContainer.visibility = View.GONE
                    true
                }
                R.id.nav_about -> {
                    tabMonitorContainer.visibility = View.GONE
                    tabAboutContainer.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }

        btnWebsite.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tepcoenergy.com"))
            startActivity(browserIntent)
        }

        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
        btnAqiProfile.text = AqiProfiles.selected(prefs).label
        btnAqiProfile.setOnClickListener {
            AqiProfiles.choose(this, prefs) { profile ->
                btnAqiProfile.text = profile.label
                renderAlertStatus(stationAqis.getOrNull(spinnerStations.selectedItemPosition))
                refreshMonitoring()
            }
        }

        spinnerStations.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isSpinnerProgrammatic && position in stationNames.indices) {
                    val chosen = stationNames[position]
                    val changed = prefs.getString("selected_station", "") != chosen
                    if (changed) prefs.edit().putString("selected_station", chosen).apply()
                    tvLocationInfo.visibility = View.GONE
                    displaySelectedAqi(stationAqis[position])
                    if (changed) refreshMonitoring()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnNearest.setOnClickListener {
            findNearestStationByLocation()
        }

        btnStart.setOnClickListener {

            val intent = Intent(this, AirQualityService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            tvServiceState.text = "وضعیت پایش پس‌زمینه: فعال (هر ۱۰ دقیقه)"
            Toast.makeText(this, "پایش خودکار فعال شد", Toast.LENGTH_SHORT).show()
        }

        btnStop.setOnClickListener {
            val stopIntent = Intent(this, AirQualityService::class.java).apply {
                action = "STOP_SERVICE"
            }
            startService(stopIntent)
            tvServiceState.text = "وضعیت پایش پس‌زمینه: متوقف شد"
            Toast.makeText(this, "آژیر و سرویس متوقف شدند", Toast.LENGTH_SHORT).show()
        }

        btnRefresh.setOnClickListener {
            Toast.makeText(this, "در حال دریافت اطلاعات از tepcoenergy.com...", Toast.LENGTH_SHORT).show()
            if (!refreshMonitoring()) fetchInitialData(forceRefresh = true)
        }

        loadFromPrefsAndRender()
        fetchInitialData(forceRefresh = false)
    }

    private fun findNearestStationByLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1002
            )
            return
        }
        detectAndSelectNearestStation()
    }

    @SuppressLint("MissingPermission")
    private fun detectAndSelectNearestStation() {
        if (stationNames.isEmpty()) {
            Toast.makeText(this, "ابتدا لیست ایستگاه‌ها بارگذاری شود...", Toast.LENGTH_SHORT).show()
            return
        }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsLoc = try { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: Exception) { null }
        val netLoc = try { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) } catch (e: Exception) { null }
        val bestLoc = gpsLoc ?: netLoc

        if (bestLoc != null) {
            selectStationClosestTo(bestLoc.latitude, bestLoc.longitude)
        } else {
            Toast.makeText(this, "در حال دریافت موقعیت مکانی گوشی...", Toast.LENGTH_SHORT).show()
            val provider = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else {
                LocationManager.GPS_PROVIDER
            }
            try {
                locationManager.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        selectStationClosestTo(location.latitude, location.longitude)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }, null)
            } catch (e: Exception) {
                Toast.makeText(this, "لطفاً GPS گوشی را روشن کنید.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun selectStationClosestTo(userLat: Double, userLon: Double) {
        var bestStationIndex = 0
        var minDistanceKm = Double.MAX_VALUE

        for (i in stationNames.indices) {
            val stName = stationNames[i]
            val coords = findCoordsForStationName(stName)
            if (coords != null) {
                val dist = haversineKm(userLat, userLon, coords.first, coords.second)
                if (dist < minDistanceKm) {
                    minDistanceKm = dist
                    bestStationIndex = i
                }
            }
        }

        val chosenName = stationNames[bestStationIndex]
        val chosenAqi = stationAqis[bestStationIndex]

        val prefs = getSharedPreferences("TepcoAirPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_station", chosenName).apply()

        isSpinnerProgrammatic = true
        spinnerStations.setSelection(bestStationIndex)
        isSpinnerProgrammatic = false

        displaySelectedAqi(chosenAqi)
        refreshMonitoring()
        val distFormatted = String.format("%.1f", minDistanceKm)
        tvLocationInfo.text = "📍 نزدیک‌ترین ایستگاه به موقعیت شما: $chosenName (فاصله تقریبی $distFormatted کیلومتر)"
        tvLocationInfo.visibility = View.VISIBLE
        Toast.makeText(this, "ایستگاه نزدیک شما انتخاب شد: $chosenName", Toast.LENGTH_SHORT).show()
    }

    private fun findCoordsForStationName(name: String): Pair<Double, Double>? {
        for ((keywords, lat, lon) in knownStationCoords) {
            for (kw in keywords) {
                if (name.contains(kw)) {
                    return Pair(lat, lon)
                }
            }
        }
        return Pair(35.6892, 51.3890)
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            detectAndSelectNearestStation()
        } else if (requestCode == 1002) {
            Toast.makeText(this, "دسترسی به موقعیت مکانی برای یافتن نزدیک‌ترین ایستگاه لازم است.", Toast.LENGTH_LONG).show()
        }
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

        tvServerUpdate.text = "بروزرسانی سرور: $updatedAt"
        tvServiceState.text = if (isRunning) "وضعیت پایش پس‌زمینه: فعال (هر ۱۰ دقیقه)" else "وضعیت پایش پس‌زمینه: غیرفعال"

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
        if (stationNames.isEmpty()) renderAlertStatus(null)
    }

    private fun refreshMonitoring(): Boolean {
        val prefs = getSharedPreferences("TepcoAirPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("service_running", false)) return false
        startService(Intent(this, AirQualityService::class.java).apply { action = "REFRESH_NOW" })
        return true
    }

    private fun renderAlertStatus(aqi: Int?) {
        val prefs = getSharedPreferences("TepcoAirPrefs", MODE_PRIVATE)
        val status = AqiAlertStatus(AqiProfiles.selected(prefs), aqi?.takeIf { it >= 0 })
        tvAlarmBanner.text = status.message
        tvAlarmBanner.visibility = if (status.outsideRange) View.VISIBLE else View.GONE
    }

    private fun displaySelectedAqi(aqi: Int) {
        renderAlertStatus(aqi)
        tvAqiValue.text = if (aqi >= 0) aqi.toString() else "—"
        val (statusText, colorHex) = when {
            aqi < 0 -> "دادهٔ ایستگاه در دسترس نیست" to "#CBD5E1"
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

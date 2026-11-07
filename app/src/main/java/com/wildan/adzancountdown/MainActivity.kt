package com.wildan.adzancountdown

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.provider.Settings as AndroidSettings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wildan.adzancountdown.databinding.ActivityMainBinding
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private val prayerChecks = mutableMapOf<Prayer, CheckBox>()
    private val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var repo: KemenagRepository

    /** City currently selected in the form; persisted on Save. */
    private lateinit var pendingCity: City

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) fetchLocation() else toast(getString(R.string.location_failed))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)
        repo = KemenagRepository(this)
        CountdownNotification.createChannels(this)

        binding.method.adapter = spinnerAdapter(CalcMethod.entries.map { it.label })
        binding.madhab.adapter = spinnerAdapter(AsrMadhab.entries.map { it.label })
        Prayer.entries.forEach { prayer ->
            val cb = CheckBox(this).apply { text = prayer.displayName }
            binding.prayerChecks.addView(cb)
            prayerChecks[prayer] = cb
        }
        loadForm()

        binding.sourceGroup.setOnCheckedChangeListener { _, _ -> updateSourceVisibility() }
        binding.chooseCity.setOnClickListener { showCityPicker() }
        binding.useLocation.setOnClickListener { requestLocation() }
        binding.save.setOnClickListener { save() }
        binding.testNow.setOnClickListener {
            CountdownService.start(this, Prayer.MAGHRIB, System.currentTimeMillis() + 60_000L, 1)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        // Permissions may have changed in Settings; make sure the alarm is armed.
        AdzanScheduler.scheduleNext(this)
        refreshTimes()
        // Top up the Kemenag download (e.g. next month) and re-arm with official times.
        refreshScheduleInBackground()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refreshScheduleInBackground(onDone: (() -> Unit)? = null) {
        val app = applicationContext
        io.execute {
            runCatching { PrayerSchedule.refresh(app) }
            AdzanScheduler.scheduleNext(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                refreshTimes()
                onDone?.invoke()
            }
        }
    }

    private fun updateSourceVisibility() {
        val city = binding.sourceCity.isChecked
        binding.cityGroup.visibility = if (city) View.VISIBLE else View.GONE
        binding.coordsGroup.visibility = if (city) View.GONE else View.VISIBLE
    }

    private fun loadForm() {
        binding.sourceGroup.check(
            if (settings.source == TimeSource.CITY) R.id.sourceCity else R.id.sourceCoords,
        )
        pendingCity = City(settings.cityId, settings.cityName)
        binding.cityName.text = pendingCity.name
        updateSourceVisibility()
        binding.latitude.setText(fmtCoord(settings.latitude))
        binding.longitude.setText(fmtCoord(settings.longitude))
        binding.method.setSelection(settings.method.ordinal)
        binding.madhab.setSelection(settings.madhab.ordinal)
        binding.adjust.setText(settings.adjustMinutes.toString())
        binding.duration.setText(settings.countdownMinutes.toString())
        prayerChecks.forEach { (p, cb) -> cb.isChecked = settings.isEnabled(p) }
    }

    private fun save() {
        val cityMode = binding.sourceCity.isChecked
        val lat = binding.latitude.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        val lng = binding.longitude.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        val coordsValid = lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0
        if (!cityMode && !coordsValid) {
            toast(getString(R.string.invalid_location))
            return
        }
        if (coordsValid) {
            settings.latitude = lat!!
            settings.longitude = lng!!
        }
        settings.source = if (cityMode) TimeSource.CITY else TimeSource.COORDINATES
        settings.cityId = pendingCity.id
        settings.cityName = pendingCity.name
        settings.method = CalcMethod.entries[binding.method.selectedItemPosition]
        settings.madhab = AsrMadhab.entries[binding.madhab.selectedItemPosition]
        settings.adjustMinutes = binding.adjust.text?.toString()?.toIntOrNull() ?: 0
        settings.countdownMinutes = binding.duration.text?.toString()?.toIntOrNull() ?: 15
        binding.duration.setText(settings.countdownMinutes.toString())
        prayerChecks.forEach { (p, cb) -> settings.setEnabled(p, cb.isChecked) }

        AdzanScheduler.scheduleNext(this)
        refreshTimes()
        // Make sure the chosen city's schedule is downloaded, then confirm with the final time.
        refreshScheduleInBackground {
            val next = AdzanScheduler.findNext(this)
            toast(
                next?.let { getString(R.string.saved, it.prayer.displayName, fmtTime(it.time)) }
                    ?: getString(R.string.no_next),
            )
        }
    }

    private fun refreshTimes() {
        val today = LocalDate.now(ZoneId.systemDefault())
        val day = PrayerSchedule.timesFor(this, today)
        val sourceLine = if (day.official) {
            getString(R.string.today_source_official, settings.cityName)
        } else {
            getString(R.string.today_source_calc, settings.method.label)
        }
        binding.todayTimes.text = Prayer.entries.joinToString("\n") { p ->
            val t = day.times[p]?.let(::fmtTime) ?: "--:--"
            val mark = if (settings.isEnabled(p)) "●" else "○"
            "$mark ${p.displayName.padEnd(8)} $t"
        } + "\n\n" + sourceLine
        updateCityStatus()
        val next = AdzanScheduler.findNext(this)
        binding.nextAdzan.text = next?.let {
            getString(R.string.next_adzan, it.prayer.displayName, fmtTime(it.time))
        } ?: getString(R.string.no_next)
    }

    private fun refreshPermissions() {
        val notifOk = CountdownNotification.canPost(this)
        setPermRow(binding.permNotifButton, notifOk) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName),
                )
            }
        }

        setPermRow(binding.permAlarmButton, AdzanScheduler.canScheduleExact(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(
                    Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")),
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            setPermRow(binding.permLiveButton, CountdownNotification.canPromote(this)) {
                startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName),
                )
            }
        } else {
            binding.permLiveLabel.setText(R.string.perm_live_updates_na)
            binding.permLiveButton.visibility = android.view.View.GONE
        }
    }

    private fun setPermRow(button: Button, ok: Boolean, fix: () -> Unit) {
        button.text = getString(if (ok) R.string.perm_ok else R.string.perm_fix)
        button.isEnabled = !ok
        button.setTextColor(ContextCompat.getColor(this, if (ok) R.color.ok else R.color.warn))
        button.setOnClickListener { runCatching(fix) }
    }

    private fun updateCityStatus() {
        val downloaded = repo.timesFor(pendingCity.id, LocalDate.now()) != null
        binding.cityStatus.setText(if (downloaded) R.string.city_ready else R.string.city_not_downloaded)
    }

    private fun showCityPicker() {
        val view = layoutInflater.inflate(R.layout.dialog_city_picker, null)
        val search = view.findViewById<TextView>(R.id.search)
        val loading = view.findViewById<ProgressBar>(R.id.loading)
        val message = view.findViewById<TextView>(R.id.message)
        val list = view.findViewById<ListView>(R.id.list)

        var all: List<City> = emptyList()
        var shown: List<City> = emptyList()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        list.adapter = adapter

        fun applyFilter() {
            val q = search.text?.toString()?.trim().orEmpty()
            // Match every word, so "kota bandung" and "bandung kota" both work.
            val words = q.uppercase().split(' ').filter { it.isNotEmpty() }
            shown = all.filter { c -> words.all { c.name.contains(it) } }
            adapter.clear()
            adapter.addAll(shown.map { it.name })
            message.visibility = if (all.isNotEmpty() && shown.isEmpty()) View.VISIBLE else View.GONE
            if (shown.isEmpty() && all.isNotEmpty()) message.text = getString(R.string.no_city_match, q)
        }

        fun showCities(cities: List<City>) {
            all = cities
            loading.visibility = View.GONE
            applyFilter()
        }

        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_city)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val city = shown[position]
            pendingCity = city
            binding.cityName.text = city.name
            binding.sourceCity.isChecked = true
            binding.cityStatus.setText(R.string.city_downloading)
            dialog.dismiss()
            // Pre-download now so Save is instant; the selection itself is stored on Save.
            val app = applicationContext
            io.execute {
                runCatching { KemenagRepository(app).ensureCached(city.id) }
                runOnUiThread { if (!isDestroyed) updateCityStatus() }
            }
        }

        val cached = repo.cachedCities()
        if (cached != null) {
            showCities(cached)
        } else {
            io.execute {
                val result = runCatching { repo.fetchCities() }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    result.onSuccess(::showCities).onFailure {
                        loading.visibility = View.GONE
                        message.visibility = View.VISIBLE
                        message.setText(R.string.cities_failed)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun requestLocation() {
        if (hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            fetchLocation()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        val lm = getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (provider == null) {
            toast(getString(R.string.location_failed))
            return
        }
        lm.getLastKnownLocation(provider)?.let(::applyLocation)
        LocationManagerCompat.getCurrentLocation(
            lm, provider, CancellationSignal(), ContextCompat.getMainExecutor(this),
        ) { loc -> if (loc != null) applyLocation(loc) else if (binding.latitude.text.isNullOrBlank()) toast(getString(R.string.location_failed)) }
    }

    private fun applyLocation(loc: Location) {
        binding.latitude.setText(fmtCoord(loc.latitude))
        binding.longitude.setText(fmtCoord(loc.longitude))
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun fmtTime(i: Instant) = timeFormatter.format(i.atZone(ZoneId.systemDefault()))
    private fun fmtCoord(d: Double) = String.format(Locale.US, "%.4f", d)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

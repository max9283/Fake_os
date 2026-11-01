package com.greenos.launcher

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GREEN = Color.parseColor("#33FF33")
private val DIM = Color.parseColor("#1F9E1F")
private val BG = Color.parseColor("#000800")
private const val REQ_PERMS = 42

class MainActivity : Activity() {

    private data class AppEntry(val label: String, val pkg: String, val cls: String)
    private data class Batt(val pct: Int, val charging: Boolean, val plug: String, val tempC: Float, val volt: Float)
    private data class Net(val link: String, val internet: Boolean)
    private data class Sms(val from: String, val body: String, val date: Long)
    private data class Meet(val title: String, val begin: Long, val end: Long, val place: String)

    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val hmFmt = SimpleDateFormat("HH:mm", Locale.US)
    private val dateFmt = SimpleDateFormat("EEE dd MMM yyyy", Locale.US)
    private val smsFmt = SimpleDateFormat("dd.MM HH:mm", Locale.US)
    private val meetFmt = SimpleDateFormat("EEE dd.MM HH:mm", Locale.US)

    private lateinit var titleView: TextView
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var statusLine: TextView
    private lateinit var meetLine: TextView
    private lateinit var content: LinearLayout
    private val tabs = ArrayList<TextView>()

    private var tab = 0
    private var tick = 0
    private var filter = ""
    private var apps: List<AppEntry> = emptyList()
    private var appsList: LinearLayout? = null
    private var sysView: TextView? = null
    private var countdownView: TextView? = null
    private var meetBegin = 0L
    private var nextMeet: Meet? = null

    private val ticker = object : Runnable {
        override fun run() {
            tick++
            if (tick % 30 == 1) refreshMeet()
            updateHeader()
            if (tab == 1) updateSys()
            if (tab == 3) updateCountdown()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadApps()
        selectTab(0)
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        if (tab == 0 && appsList != null) renderApps()
        refreshMeet()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Это домашний экран: назад только возвращает на вкладку APPS
        if (tab != 0) selectTab(0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshMeet()
        selectTab(tab)
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this)
        root.setBackgroundColor(BG)

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        main.setPadding(dp(14), dp(10), dp(14), dp(8))

        titleView = tv("GREEN-OS v1.0 :: READY", 12f, DIM)
        clockView = tv("00:00:00", 44f, GREEN)
        dateView = tv("", 14f, GREEN)
        statusLine = tv("", 12f, GREEN)
        meetLine = tv("", 12f, GREEN)

        main.addView(titleView)
        main.addView(clockView)
        main.addView(dateView)
        main.addView(statusLine)
        main.addView(meetLine)
        main.addView(rule())

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        scroll.addView(content)
        main.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = frame()
        val names = listOf("APPS", "SYS", "SMS", "MEET")
        for ((i, n) in names.withIndex()) {
            val t = tv("[$n]", 14f, GREEN)
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(12), 0, dp(12))
            t.setOnClickListener { selectTab(i) }
            tabs.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        main.addView(bar)

        root.addView(main, FrameLayout.LayoutParams(match, match))
        root.addView(ScanlineView(this), FrameLayout.LayoutParams(match, match))
        setContentView(root)
    }

    private fun selectTab(i: Int) {
        tab = i
        for ((idx, t) in tabs.withIndex()) {
            val on = idx == i
            t.setTextColor(if (on) BG else GREEN)
            t.setBackgroundColor(if (on) GREEN else Color.TRANSPARENT)
            t.setShadowLayer(if (on) 0f else 5f, 0f, 0f, GREEN)
        }
        content.removeAllViews()
        appsList = null
        sysView = null
        countdownView = null
        when (i) {
            0 -> showApps()
            1 -> showSys()
            2 -> showSms()
            else -> showMeet()
        }
    }

    private fun updateHeader() {
        val now = Date()
        clockView.text = timeFmt.format(now)
        dateView.text = dateFmt.format(now).uppercase(Locale.US)
        titleView.text = "GREEN-OS v1.0 :: READY" + if (tick % 2 == 0) "_" else " "

        val b = readBattery()
        val n = readNet()
        val chg = if (b.charging) " CHG" else ""
        val net = if (n.internet) "ONLINE" else "OFFLINE"
        statusLine.text = "BAT ${b.pct}%$chg | ${n.link} | NET $net"

        val m = nextMeet
        meetLine.text = when {
            !hasPerm(Manifest.permission.READ_CALENDAR) -> "MEET: NO ACCESS (open MEET tab)"
            m == null -> "MEET: NO EVENTS"
            else -> "MEET: " + meetFmt.format(Date(m.begin)).uppercase(Locale.US) + " " + m.title
        }
    }

    // ---------------------------------------------------------------- APPS

    private fun loadApps() {
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        val list = packageManager.queryIntentActivities(i, 0)
        apps = list
            .map { AppEntry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun showApps() {
        val input = EditText(this)
        input.hint = "> SEARCH_"
        input.setHintTextColor(DIM)
        input.setTextColor(GREEN)
        input.typeface = Typeface.MONOSPACE
        input.textSize = 15f
        input.isSingleLine = true
        input.background = frame()
        input.setPadding(dp(10), dp(10), dp(10), dp(10))
        input.setText(filter)
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString() ?: ""
                renderApps()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        content.addView(input)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        appsList = list
        content.addView(list)
        renderApps()
    }

    private fun renderApps() {
        val list = appsList ?: return
        list.removeAllViews()
        val f = filter.trim().lowercase(Locale.getDefault())
        val shown = apps.filter { f.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(f) }
        val head = tv("${shown.size} PROGRAM(S) FOUND  (long press = info)", 11f, DIM)
        head.setPadding(0, dp(8), 0, dp(4))
        list.addView(head)
        for (a in shown) {
            val row = tv("> ${a.label}", 16f, GREEN)
            row.setPadding(dp(8), dp(11), dp(8), dp(11))
            row.setOnClickListener { launch(a) }
            row.setOnLongClickListener {
                openAppInfo(a)
                true
            }
            list.addView(row)
        }
    }

    private fun launch(a: AppEntry) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_LAUNCHER)
            i.component = ComponentName(a.pkg, a.cls)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(i)
        } catch (e: Exception) {
            toast("LAUNCH FAILED: ${a.label}")
        }
    }

    private fun openAppInfo(a: AppEntry) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("NOT AVAILABLE")
        }
    }

    // ---------------------------------------------------------------- SYS

    private fun showSys() {
        val t = tv("", 14f, GREEN)
        t.setPadding(0, dp(8), 0, dp(8))
        sysView = t
        content.addView(t)
        content.addView(btn("[ WI-FI SETTINGS ]") { openSettings(Settings.ACTION_WIFI_SETTINGS) })
        content.addView(btn("[ INTERNET / NETWORK ]") { openSettings(Settings.ACTION_WIRELESS_SETTINGS) })
        content.addView(btn("[ BATTERY USAGE ]") { openSettings(Intent.ACTION_POWER_USAGE_SUMMARY) })
        updateSys()
    }

    private fun updateSys() {
        val v = sysView ?: return
        val b = readBattery()
        val n = readNet()
        val sb = StringBuilder()
        sb.append("== BATTERY ==\n")
        sb.append("LEVEL : ${b.pct}%\n")
        sb.append(bar(b.pct)).append("\n")
        val state = if (b.charging) "CHARGING ${b.plug}" else "DISCHARGING"
        sb.append("STATE : $state\n")
        sb.append("TEMP  : ").append(String.format(Locale.US, "%.1f", b.tempC)).append(" C\n")
        sb.append("VOLT  : ").append(String.format(Locale.US, "%.2f", b.volt)).append(" V\n\n")
        sb.append("== NETWORK ==\n")
        sb.append("LINK  : ${n.link}\n")
        sb.append("INET  : ").append(if (n.internet) "ONLINE" else "OFFLINE").append("\n")
        sb.append("WI-FI : ${readWifi()}\n")
        v.text = sb.toString()
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("NOT AVAILABLE")
        }
    }

    private fun readBattery(): Batt {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val mv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val pct = if (scale > 0) level * 100 / scale else level
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plug = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "(AC)"
            BatteryManager.BATTERY_PLUGGED_USB -> "(USB)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "(WIRELESS)"
            else -> ""
        }
        return Batt(pct, charging, plug, temp / 10f, mv / 1000f)
    }

    private fun readNet(): Net {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return Net("NO LINK", false)
        val caps = cm.getNetworkCapabilities(network) ?: return Net("NO LINK", false)
        val link = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return Net(link, internet)
    }

    @Suppress("DEPRECATION")
    private fun readWifi(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wm.isWifiEnabled) return "OFF"
            val info = wm.connectionInfo
            if (info == null || info.rssi <= -127) return "ON / NOT CONNECTED"
            var ssid = (info.ssid ?: "?").trim('"')
            if (ssid == "<unknown ssid>") ssid = "(allow LOCATION to see name)"
            val level = WifiManager.calculateSignalLevel(info.rssi, 5)
            "$ssid  SIGNAL $level/4  ${info.linkSpeed}Mbps  ${info.rssi}dBm"
        } catch (e: Exception) {
            "N/A"
        }
    }

    private fun bar(pct: Int): String {
        val w = 24
        val f = pct.coerceIn(0, 100) * w / 100
        return "[" + "#".repeat(f) + ".".repeat(w - f) + "]"
    }

    // ---------------------------------------------------------------- SMS

    private fun showSms() {
        if (!hasPerm(Manifest.permission.READ_SMS)) {
            denied("SMS")
            return
        }
        content.addView(btn("[ OPEN MESSAGES APP ]") { openSelector(Intent.CATEGORY_APP_MESSAGING) })
        val list = readSms()
        val head = tv("== INBOX (${list.size}) ==", 12f, DIM)
        head.setPadding(0, dp(12), 0, dp(4))
        content.addView(head)
        if (list.isEmpty()) content.addView(tv("NO MESSAGES", 14f, GREEN))
        for (m in list) {
            val from = tv("FROM ${m.from}  ${smsFmt.format(Date(m.date))}", 12f, DIM)
            from.setPadding(0, dp(10), 0, 0)
            content.addView(from)
            content.addView(tv(m.body, 14f, GREEN))
        }
    }

    private fun readSms(): List<Sms> {
        val out = ArrayList<Sms>()
        try {
            val c = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null, null, "date DESC"
            )
            c?.use {
                while (it.moveToNext() && out.size < 25) {
                    out.add(Sms(it.getString(0) ?: "?", it.getString(1) ?: "", it.getLong(2)))
                }
            }
        } catch (e: Exception) {
            // нет доступа или нет провайдера SMS
        }
        return out
    }

    // ---------------------------------------------------------------- MEET

    private fun showMeet() {
        if (!hasPerm(Manifest.permission.READ_CALENDAR)) {
            denied("CALENDAR")
            return
        }
        val list = readMeets()
        nextMeet = list.firstOrNull()
        if (list.isEmpty()) {
            val t = tv("NO MEETINGS IN THE NEXT 7 DAYS", 15f, GREEN)
            t.setPadding(0, dp(12), 0, dp(8))
            content.addView(t)
            content.addView(btn("[ OPEN CALENDAR ]") { openSelector(Intent.CATEGORY_APP_CALENDAR) })
            return
        }
        val first = list[0]
        val head = tv("== NEXT MEETING ==", 12f, DIM)
        head.setPadding(0, dp(12), 0, dp(4))
        content.addView(head)
        content.addView(tv(first.title, 20f, GREEN))
        content.addView(tv(meetFmt.format(Date(first.begin)).uppercase(Locale.US) + " - " + hmFmt.format(Date(first.end)), 15f, GREEN))
        if (first.place.isNotBlank()) content.addView(tv("@ ${first.place}", 13f, GREEN))

        val cd = tv("", 30f, GREEN)
        cd.setPadding(0, dp(10), 0, dp(10))
        countdownView = cd
        meetBegin = first.begin
        content.addView(cd)

        content.addView(tv("== SCHEDULE (7 DAYS) ==", 12f, DIM))
        for (m in list) {
            val row = tv(meetFmt.format(Date(m.begin)).uppercase(Locale.US) + "  " + m.title, 13f, GREEN)
            row.setPadding(0, dp(5), 0, dp(5))
            content.addView(row)
        }
        content.addView(btn("[ OPEN CALENDAR ]") { openSelector(Intent.CATEGORY_APP_CALENDAR) })
        updateCountdown()
    }

    private fun updateCountdown() {
        val v = countdownView ?: return
        val diff = meetBegin - System.currentTimeMillis()
        if (diff <= 0) {
            v.text = "IN PROGRESS"
            return
        }
        val s = diff / 1000
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val clock = String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
        v.text = if (d > 0) "T-${d}d $clock" else "T-$clock"
    }

    private fun readMeets(): List<Meet> {
        val out = ArrayList<Meet>()
        if (!hasPerm(Manifest.permission.READ_CALENDAR)) return out
        try {
            val now = System.currentTimeMillis()
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, now)
            ContentUris.appendId(b, now + 7L * 24 * 3600 * 1000)
            val proj = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY
            )
            val c = contentResolver.query(b.build(), proj, null, null, CalendarContract.Instances.BEGIN + " ASC")
            c?.use {
                while (it.moveToNext() && out.size < 30) {
                    if (it.getInt(4) == 1) continue
                    out.add(Meet(it.getString(0) ?: "(no title)", it.getLong(1), it.getLong(2), it.getString(3) ?: ""))
                }
            }
        } catch (e: Exception) {
            // нет доступа к календарю
        }
        return out
    }

    private fun refreshMeet() {
        nextMeet = readMeets().firstOrNull()
    }

    // ---------------------------------------------------------------- helpers

    private fun denied(what: String) {
        val t = tv("ACCESS DENIED\n$what PERMISSION REQUIRED", 15f, GREEN)
        t.setPadding(0, dp(12), 0, dp(4))
        content.addView(t)
        content.addView(btn("[ GRANT $what ACCESS ]") { requestMissingPermissions() })
    }

    private fun openSelector(category: String) {
        try {
            val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            toast("APP NOT FOUND")
        }
    }

    private fun hasPerm(p: String): Boolean =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val need = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).filter { !hasPerm(it) }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_PERMS)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun tv(s: String, size: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.typeface = Typeface.MONOSPACE
        t.setShadowLayer(5f, 0f, 0f, color)
        return t
    }

    private fun frame(): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(Color.TRANSPARENT)
        g.setStroke(dp(1), DIM)
        return g
    }

    private fun rule(): View {
        val v = View(this)
        v.setBackgroundColor(DIM)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        lp.setMargins(0, dp(6), 0, dp(6))
        v.layoutParams = lp
        return v
    }

    private fun btn(label: String, onClick: () -> Unit): TextView {
        val b = tv(label, 15f, GREEN)
        b.gravity = Gravity.CENTER
        b.background = frame()
        b.setPadding(dp(10), dp(12), dp(10), dp(12))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(10), 0, 0)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }
}

/** Полосы развёртки как у старого ЭЛТ-монитора. */
private class ScanlineView(context: Context) : View(context) {
    private val paint = Paint().apply {
        color = Color.argb(55, 0, 0, 0)
        strokeWidth = 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        var y = 0f
        while (y < height) {
            canvas.drawLine(0f, y, w, y, paint)
            y += 5f
        }
    }
}

package com.gabs.fastflow

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

object Sched {
    const val CH = "ff"
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Pengingat FastFlow", NotificationManager.IMPORTANCE_HIGH))
    }

    fun show(c: Context, t: String, b: String, id: Int) {
        channel(c)
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), FLAGS)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.fg)
            .setContentTitle(t)
            .setContentText(b)
            .setStyle(Notification.BigTextStyle().bigText(b))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    fun set(c: Context, json: String) {
        val p = c.getSharedPreferences("ff", 0)
        val am = c.getSystemService(AlarmManager::class.java)
        for (i in 0 until p.getInt("n", 0)) {
            PendingIntent.getBroadcast(c, i, Intent(c, Rcv::class.java), FLAGS or PendingIntent.FLAG_NO_CREATE)
                ?.let { am.cancel(it); it.cancel() }
        }
        val a = JSONArray(json)
        val now = System.currentTimeMillis()
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        var n = 0
        for (i in 0 until a.length()) {
            val e = a.getJSONArray(i)
            val t = e.getLong(0)
            if (t <= now) continue
            val it = Intent(c, Rcv::class.java)
                .putExtra("t", e.getString(1)).putExtra("b", e.getString(2)).putExtra("id", n)
            val pi = PendingIntent.getBroadcast(c, n, it, FLAGS or PendingIntent.FLAG_UPDATE_CURRENT)
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            n++
        }
        p.edit().putInt("n", n).putString("j", json).apply()
    }
}

class Rcv : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            c.getSharedPreferences("ff", 0).getString("j", null)?.let { Sched.set(c, it) }
            return
        }
        Sched.show(c, i.getStringExtra("t") ?: "FastFlow", i.getStringExtra("b") ?: "", i.getIntExtra("id", 0))
    }
}

class Br(private val a: MainActivity) {
    @JavascriptInterface fun schedule(j: String) { try { Sched.set(a, j) } catch (e: Exception) {} }
    @JavascriptInterface fun askPerm() { a.runOnUiThread { a.ask() } }
    @JavascriptInterface fun notifOk(): Boolean = a.okNotif()
    @JavascriptInterface fun put(j: String) { a.getSharedPreferences("ff", 0).edit().putString("state", j).commit() }
    @JavascriptInterface fun get(): String = a.getSharedPreferences("ff", 0).getString("state", "") ?: ""
    @JavascriptInterface fun isNight(): Boolean = a.night()
    @JavascriptInterface fun bar(c: String, dark: Boolean) {
        a.runOnUiThread { try { a.bar(Color.parseColor(c), dark) } catch (e: Exception) {} }
    }
    @JavascriptInterface fun test() { Sched.show(a, "🔔 Tes notifikasi", "Notifikasi berfungsi dengan baik!", 999) }
}

class MainActivity : Activity() {
    private lateinit var w: WebView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Sched.channel(this)
        w = WebView(this)
        setContentView(w)
        bar(if (night()) Color.BLACK else Color.parseColor("#f3f7f5"), night())
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.addJavascriptInterface(Br(this), "Android")
        w.webViewClient = WebViewClient()
        w.loadUrl("file:///android_asset/index.html")
        if (!okNotif()) ask()
    }

    fun night(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Warna status bar & bilah navigasi mengikuti tema aplikasi
    fun bar(bg: Int, dark: Boolean) {
        window.statusBarColor = bg
        window.navigationBarColor = bg
        w.setBackgroundColor(bg)
        window.decorView.systemUiVisibility = if (dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    fun okNotif(): Boolean = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // Ketuk pertama: izin notifikasi. Ketuk berikutnya: izin alarm tepat waktu (jika belum).
    fun ask() {
        if (!okNotif()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else if (Build.VERSION.SDK_INT >= 31 &&
            !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::w.isInitialized) w.evaluateJavascript("if(window.onFg)onFg()", null)
    }

    override fun onPause() {
        if (::w.isInitialized) w.evaluateJavascript("if(window.onBg)onBg()", null)
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        w.evaluateJavascript("permText()", null)
    }
}

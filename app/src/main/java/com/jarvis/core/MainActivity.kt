package com.jarvis.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var core: CoreView
    private lateinit var clockTv: TextView
    private lateinit var infoTv: TextView
    private lateinit var stateTv: TextView
    private lateinit var actionTv: TextView
    private lateinit var panel: ScrollView
    private lateinit var keyBox: EditText
    private lateinit var logv: TextView
    private var guide: TextToSpeech? = null
    private var guideReady = false
    private var asked = false
    private var weather = ""
    private var lastWeather = 0L
    private val cyan = 0xFF00E5FF.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mono(size: Float): TextView {
        val t = TextView(this)
        t.typeface = Typeface.MONOSPACE
        t.textSize = size
        t.setTextColor(cyan)
        return t
    }

    private fun btn(t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.textSize = 12f
        b.setOnClickListener { onClick() }
        return b
    }

    private fun row(vararg bs: Button): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        for (b in bs) l.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return l
    }

    private fun sendAction(a: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            nextStep()
            return
        }
        startForegroundService(Intent(this, JarvisService::class.java).setAction(a))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF05080F.toInt())
        root.addView(DataRainView(this), FrameLayout.LayoutParams(match, match))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(36), dp(16), dp(12))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        clockTv = mono(15f)
        infoTv = mono(12f)
        infoTv.gravity = Gravity.END
        top.addView(clockTv, LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(infoTv, LinearLayout.LayoutParams(0, wrap, 1f))
        col.addView(top, LinearLayout.LayoutParams(match, wrap))

        core = CoreView(this)
        core.setOnClickListener { sendAction("listen_now") }
        col.addView(core, LinearLayout.LayoutParams(match, 0, 1f))

        stateTv = mono(16f)
        stateTv.gravity = Gravity.CENTER
        actionTv = mono(12f)
        actionTv.gravity = Gravity.CENTER
        actionTv.alpha = 0.8f
        col.addView(stateTv, LinearLayout.LayoutParams(match, wrap))
        col.addView(actionTv, LinearLayout.LayoutParams(match, wrap))

        val gear = mono(24f)
        gear.text = "⚙"
        gear.gravity = Gravity.END
        gear.setPadding(dp(12), dp(8), dp(12), dp(8))
        gear.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            refresh()
        }
        col.addView(gear, LinearLayout.LayoutParams(match, wrap))
        root.addView(col, FrameLayout.LayoutParams(match, match))

        panel = ScrollView(this)
        panel.visibility = View.GONE
        panel.setBackgroundColor(0xF005080F.toInt())
        val pc = LinearLayout(this)
        pc.orientation = LinearLayout.VERTICAL
        pc.setPadding(dp(14), dp(40), dp(14), dp(14))

        keyBox = EditText(this)
        keyBox.hint = "Gemini API anahtarı"
        keyBox.setHintTextColor(0xFF557788.toInt())
        keyBox.setTextColor(0xFFFFFFFF.toInt())
        keyBox.setSingleLine(true)
        keyBox.setText(Prefs.key(this))
        pc.addView(keyBox, LinearLayout.LayoutParams(match, wrap))
        pc.addView(row(
            btn("BAŞLAT") { sendAction("start") },
            btn("DURDUR") { stopService(Intent(this, JarvisService::class.java)) }
        ))
        pc.addView(row(
            btn("İZİNLER") { nextStep() },
            btn("UYGULAMA AYARLARI") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        ))
        pc.addView(row(
            btn("SES DEĞİŞTİR") { sendAction("voice_next") },
            btn("ANAHTARI KAYDET") {
                Prefs.setKey(this, keyBox.text.toString())
                toast("Kaydedildi efendim")
            }
        ))
        logv = mono(11f)
        logv.setTextColor(0xFF8FB8C4.toInt())
        pc.addView(logv, LinearLayout.LayoutParams(match, wrap))
        panel.addView(pc)
        root.addView(panel, FrameLayout.LayoutParams(match, match))

        setContentView(root)

        guide = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                guide?.language = Locale("tr", "TR")
                guide?.setPitch(0.8f)
                guide?.setSpeechRate(0.85f)
                guideReady = true
            }
        }

        core.playBoot()
        bootBeep()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            sendAction("start")
        } else {
            ui.postDelayed({ say("Sistemler çevrimiçi efendim. İzinler için İZİNLER düğmesine basın.") }, 1500)
        }
    }

    private fun bootBeep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 60)
            tg.startTone(ToneGenerator.TONE_PROP_PROMPT, 120)
            ui.postDelayed({ try { tg.startTone(ToneGenerator.TONE_PROP_ACK, 160) } catch (e: Exception) {} }, 200)
            ui.postDelayed({ try { tg.release() } catch (e: Exception) {} }, 700)
        } catch (e: Exception) {
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Jarvis sesli yonlendirir (film gibi). */
    private fun say(t: String) {
        toast(t)
        if (guideReady) guide?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "guide")
    }

    private val tick = object : Runnable {
        override fun run() {
            clockTv.text = SimpleDateFormat("HH:mm:ss\nEEE d MMM", Locale("tr", "TR")).format(Date())
            var bat = "--"
            val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bi != null) {
                val l = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val s = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0 && s > 0) bat = (l * 100 / s).toString() + "%"
            }
            infoTv.text = "PİL $bat\n$weather"
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        Bus.onUpdate = { refresh() }
        refresh()
        saveLocation()
        ui.post(tick)
        val now = System.currentTimeMillis()
        if (now - lastWeather > 30 * 60 * 1000L) {
            lastWeather = now
            Thread {
                val w = Weather.brief(this)
                if (w != null) runOnUiThread { weather = "HAVA $w" }
            }.start()
        }
    }

    override fun onPause() {
        Bus.onUpdate = null
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        try { guide?.stop(); guide?.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun refresh() {
        stateTv.text = "● " + Bus.current
        actionTv.text = if (Bus.action.isBlank()) "" else "KOMUT: " + Bus.action
        if (panel.visibility == View.VISIBLE) logv.text = Bus.text()
    }

    private fun nextStep() {
        val all = ArrayList<String>()
        all.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            all.add(Manifest.permission.POST_NOTIFICATIONS)
            all.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            all.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        all.add(Manifest.permission.READ_CALENDAR)
        all.add(Manifest.permission.READ_CONTACTS)
        all.add(Manifest.permission.READ_PHONE_STATE)
        all.add(Manifest.permission.READ_CALL_LOG)
        all.add(Manifest.permission.ANSWER_PHONE_CALLS)
        all.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        val need = all.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty() && !asked) {
            asked = true
            say("Efendim, önce mikrofon, takvim, rehber ve diğer izinleri vermeniz gerekiyor.")
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Mikrofon izni şart efendim. Uygulama ayarlarından açın.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            say("Şimdi diğer uygulamaların üzerinde göster iznini açın efendim. Sonra geri dönüp İZİNLER'e tekrar basın.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            say("Pil kısıtlamasını kaldırın efendim, yoksa arka planda uyuyabilirim.")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            return
        }
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        if (!nl.contains(packageName)) {
            say("Mesajları okuyabilmem için bildirim erişimi verin efendim. Açılmıyorsa uygulama ayarlarında, sağ üstteki üç noktadan kısıtlı ayarlara izin verin. Sonra İZİNLER'e tekrar basın.")
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        val ac = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (!ac.contains(packageName)) {
            say("Ekranı okuyup dokunabilmem için erişilebilirlikten beni açın efendim. Sonra İZİNLER'e tekrar basın.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        say("Tüm izinler tamam efendim. Xiaomi'de uygulama ayarlarından otomatik başlatmayı ve arka planda açılır pencere iznini de açın.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        nextStep()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) sendAction("start")
    }

    private fun saveLocation() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            var best: android.location.Location? = null
            for (p in lm.getProviders(true)) {
                val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
                if (l != null && (best == null || l.time > best.time)) best = l
            }
            if (best != null) {
                Prefs.setStr(this, "lat", best.latitude.toString())
                Prefs.setStr(this, "lon", best.longitude.toString())
            }
        } catch (e: Exception) {
        }
    }
}

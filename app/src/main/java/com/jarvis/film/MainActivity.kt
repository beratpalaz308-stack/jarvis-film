package com.jarvis.film

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ReactorView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var angle = 0f
    var active = false

    private val anim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) * 0.9f
        val col = if (active) 0xFFFFC107.toInt() else 0xFF00E5FF.toInt()
        p.style = Paint.Style.STROKE
        p.color = col
        p.strokeWidth = r * 0.04f
        c.drawCircle(cx, cy, r, p)
        c.drawCircle(cx, cy, r * 0.55f, p)
        val rect = RectF(cx - r * 0.78f, cy - r * 0.78f, cx + r * 0.78f, cy + r * 0.78f)
        p.strokeWidth = r * 0.06f
        for (k in 0 until 3) c.drawArc(rect, angle + k * 120f, 70f, false, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, r * 0.18f, p)
    }
}

class MainActivity : Activity() {
    private lateinit var reactor: ReactorView
    private lateinit var status: TextView
    private lateinit var logv: TextView
    private lateinit var scroll: ScrollView
    private lateinit var keyBox: EditText

    private val cyan = 0xFF00E5FF.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun btn(t: String, onClick: () -> Unit): Button = Button(this).apply {
        text = t
        textSize = 12f
        setOnClickListener { onClick() }
    }

    private fun row(vararg bs: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in bs) addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = dp(14)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF05080F.toInt())
            setPadding(pad, pad, pad, pad)
        }
        reactor = ReactorView(this)
        root.addView(reactor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))

        status = TextView(this).apply {
            setTextColor(cyan)
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            text = "J.A.R.V.I.S."
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        keyBox = EditText(this).apply {
            hint = "Gemini API anahtarı"
            setHintTextColor(0xFF557788.toInt())
            setTextColor(Color.WHITE)
            setSingleLine(true)
            setText(Prefs.key(this@MainActivity))
        }
        root.addView(keyBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(row(
            btn("BAŞLAT") { startJarvis() },
            btn("DURDUR") { stopService(Intent(this, JarvisService::class.java)) }
        ))
        root.addView(row(
            btn("İZİNLER") { nextStep() },
            btn("UYGULAMA AYARLARI") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        ))
        root.addView(row(
            btn("SES DEĞİŞTİR") {
                val i = Intent(this, JarvisService::class.java).setAction("voice_next")
                startForegroundService(i)
            },
            btn("ANAHTARI KAYDET") {
                Prefs.setKey(this, keyBox.text.toString())
                toast("Kaydedildi efendim")
            }
        ))

        logv = TextView(this).apply {
            setTextColor(0xFF8FB8C4.toInt())
            textSize = 12f
            typeface = Typeface.MONOSPACE
        }
        scroll = ScrollView(this).apply { addView(logv) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startJarvis()
        }
    }

    override fun onResume() {
        super.onResume()
        Bus.onUpdate = { refresh() }
        refresh()
        saveLocation()
    }

    override fun onPause() {
        Bus.onUpdate = null
        super.onPause()
    }

    private fun refresh() {
        status.text = "● " + Bus.state
        reactor.active = Bus.state == "EMİR BEKLİYOR" || Bus.state == "UYANDI" || Bus.state == "DÜŞÜNÜYOR"
        logv.text = Bus.text()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startJarvis() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            nextStep()
            return
        }
        Prefs.setKey(this, keyBox.text.toString())
        startForegroundService(Intent(this, JarvisService::class.java))
    }

    private var asked = false

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
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("Mikrofon izni şart efendim. UYGULAMA AYARLARI'ndan aç.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("'Diğer uygulamaların üzerinde göster' iznini aç, sonra geri dön ve İZİNLER'e tekrar bas")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            return
        }
        val nl = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        if (!nl.contains(packageName)) {
            toast("Mesajlar için Jarvis'e bildirim erişimi ver. Açılmıyorsa: UYGULAMA AYARLARI > ⋮ > Kısıtlı ayarlara izin ver. Sonra İZİNLER'e tekrar bas.")
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        val ac = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (!ac.contains(packageName)) {
            toast("Ekran okuma ve dokunma için Erişilebilirlik'ten Jarvis'i aç. Sonra İZİNLER'e tekrar bas.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        toast("Tüm izinler tamam efendim. Xiaomi'de UYGULAMA AYARLARI'ndan otomatik başlatma ve arka planda açılır pencere iznini de aç.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        nextStep()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startJarvis()
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

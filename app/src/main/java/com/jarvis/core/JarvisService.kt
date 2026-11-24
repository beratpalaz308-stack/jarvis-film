package com.jarvis.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.WindowManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import android.speech.RecognitionListener as SpeechListener
import org.vosk.android.RecognitionListener as VoskListener

class JarvisService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var curRec: Recognizer? = null
    private var recog: SpeechRecognizer? = null
    private var wl: PowerManager.WakeLock? = null
    private var bubble: CoreView? = null
    private var state = "INIT"
    private var pendingAfter: (() -> Unit)? = null
    private var lastUtt = ""
    private var started = false
    private var dead = false
    private var bargeOn = false
    private var busyRetries = 0
    private var callMode = false
    private var ringing = false
    private val announced = HashSet<String>()
    private val msgQueue = ArrayList<String>()
    private var warned20 = false
    private var warned10 = false
    private var warned5 = false
    private var lastHeat = 0L
    private var lastStorage = 0L

    companion object {
        private const val CH = "jarvis_core"
    }

    override fun onBind(i: Intent?): IBinder? = null

    // =====================================================================
    // Yasam dongusu
    // =====================================================================
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CH, "J.A.R.V.I.S.", NotificationManager.IMPORTANCE_LOW)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText("Emrinizdeyim efendim")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setLargeIcon(CoreDraw.bitmap(this, 128))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            Bus.log("Servis başlatılamadı: ${e.message}")
            Bus.setState("KAPALI")
            dead = true
            stopSelf()
            return
        }

        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:wake")
            wl?.acquire()
        } catch (e: Exception) {
            Bus.log("WakeLock alınamadı")
        }

        registerReceiver(batt, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        try {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val tmgr = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                tmgr.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: Exception) {
            Bus.log("Arama dinleme açılamadı: ${e.message}")
        }
        main.postDelayed(ticker, 20000L)
        Bus.onMessage = { m -> main.post { onNewMessage(m) } }
        Bus.setState("BAŞLATILIYOR")
        Bus.switchMode("IDLE")
        Bus.log("J.A.R.V.I.S. servisi başladı")

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale("tr", "TR")
                tts?.setPitch(0.8f)
                tts?.setSpeechRate(0.85f)
                applyVoice()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { onSpoken(id) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { onSpoken(id) } }
                })
            } else {
                Bus.log("Ses motoru (TTS) başlatılamadı")
            }
            main.post { boot() }
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (dead) return START_NOT_STICKY
        when (i?.action) {
            "voice_next" -> nextVoice()
            "listen_now" -> {
                if (state == "WAKE") onWake() else Bus.log("Şu an dinlemeye geçemem ($state)")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        dead = true
        Bus.setState("KAPALI")
        Bus.switchMode("IDLE")
        try { unregisterReceiver(batt) } catch (e: Exception) {}
        main.removeCallbacks(ticker)
        Bus.onMessage = null
        try {
            val tmgr = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            tmgr.listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        } catch (e: Exception) {
        }
        hideBubble()
        closeMic()
        try { recog?.destroy() } catch (e: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        try { model?.close() } catch (e: Exception) {}
        try { if (wl?.isHeld == true) wl?.release() } catch (e: Exception) {}
        bg.shutdownNow()
        super.onDestroy()
    }

    // =====================================================================
    // Ses secimi, konusma, ses efekti, titresim
    // =====================================================================
    private fun trVoices(): List<Voice> = try {
        tts?.voices?.filter { it.locale.language == "tr" }?.sortedBy { it.name } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun applyVoice() {
        val v = trVoices()
        val idx = Prefs.voiceIdx(this)
        if (v.isNotEmpty() && idx >= 0) tts?.voice = v[idx % v.size]
    }

    private fun nextVoice() {
        val v = trVoices()
        if (v.isEmpty()) {
            Bus.log("Türkçe ses bulunamadı")
            return
        }
        val idx = (Prefs.voiceIdx(this) + 1) % v.size
        Prefs.setVoiceIdx(this, idx)
        tts?.voice = v[idx]
        Bus.log("Ses: ${v[idx].name} (${idx + 1}/${v.size})")
        speak("Bu ses nasıl efendim?", false, null)
    }

    private fun speak(text: String, err: Boolean = false, after: (() -> Unit)?) {
        Bus.log("J: $text")
        Bus.switchMode(if (err) "ERROR" else "SPEAK")
        showBubble()
        pendingAfter = after
        val t = tts
        if (t == null) {
            val a = pendingAfter
            pendingAfter = null
            a?.invoke()
            return
        }
        val id = "u" + System.nanoTime()
        lastUtt = id
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        main.postDelayed({ onSpoken(id) }, 8000L + text.length * 90L)
        if (text.length > 40) startBarge()
    }

    private fun onSpoken(id: String?) {
        if (id != lastUtt) return
        if (bargeOn) {
            bargeOn = false
            closeMic()
        }
        val a = pendingAfter
        pendingAfter = null
        a?.invoke()
    }

    private fun beepTech() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tg.startTone(ToneGenerator.TONE_PROP_PROMPT, 120)
            main.postDelayed({ try { tg.startTone(ToneGenerator.TONE_PROP_ACK, 160) } catch (e: Exception) {} }, 170)
            main.postDelayed({ try { tg.release() } catch (e: Exception) {} }, 600)
        } catch (e: Exception) {
        }
    }

    private fun buzz() {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(45L, 90))
        } catch (e: Exception) {
        }
    }

    private fun isNight(): Boolean = Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 7

    // =====================================================================
    // Cekirdek balonu (sadece uyanik ve konusurken)
    // =====================================================================
    private fun showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return
        try {
            val v = CoreView(this)
            val s = (96 * resources.displayMetrics.density).toInt()
            val lp = WindowManager.LayoutParams(
                s, s,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            lp.gravity = Gravity.TOP or Gravity.END
            lp.x = 24
            lp.y = 160
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            bubble = v
        } catch (e: Exception) {
            Bus.log("Balon açılamadı: ${e.message}")
        }
    }

    private fun hideBubble() {
        val v = bubble ?: return
        bubble = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
    }

    // =====================================================================
    // Acilis ve ses modeli (APK icinden kopyalanir, internet gerekmez)
    // =====================================================================
    private fun boot() {
        if (started || dead) return
        started = true
        Bus.setState("MODEL YÜKLENİYOR")
        bg.execute {
            var dir: File? = null
            while (dir == null && !dead) {
                try {
                    dir = ensureModel()
                } catch (e: Exception) {
                    Bus.log("Model hatası: ${e.message}")
                    Bus.setState("MODEL HATASI")
                    try { Thread.sleep(20000) } catch (ie: InterruptedException) { return@execute }
                }
            }
            if (dead || dir == null) return@execute
            try {
                model = Model(dir.absolutePath)
            } catch (e: Exception) {
                Bus.log("Model yüklenemedi: ${e.message}")
                Bus.setState("MODEL HATASI")
                return@execute
            }
            main.post {
                if (dead) return@post
                Bus.log("Ses modeli hazır")
                if (isNight()) startWake() else speak("Sistemler çevrimiçi efendim.") { startWake() }
            }
        }
    }

    private fun ensureModel(): File {
        val dest = File(filesDir, "model")
        if (File(dest, "ready.txt").exists() && (File(dest, "am").isDirectory || File(dest, "final.mdl").exists())) return dest
        val top = assets.list("vosk-model")
        if (top == null || top.isEmpty()) throw IllegalStateException("APK içinde ses modeli yok (vosk-model klasörü boş)")
        Bus.log("Ses modeli telefona kopyalanıyor...")
        val tmp = File(filesDir, "model_tmp")
        tmp.deleteRecursively()
        val n = copyAssets("vosk-model", tmp)
        if (!File(tmp, "am").isDirectory && !File(tmp, "final.mdl").exists()) {
            throw IllegalStateException("model yapısı beklenmedik (içerik: " + (tmp.list()?.joinToString(",") ?: "boş") + ")")
        }
        File(tmp, "ready.txt").writeText("ok")
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IllegalStateException("model taşınamadı")
        Bus.log("Ses modeli kopyalandı ($n dosya)")
        return dest
    }

    private fun copyAssets(path: String, out: File): Int {
        val names = assets.list(path) ?: return 0
        if (names.isEmpty()) {
            out.parentFile?.mkdirs()
            assets.open(path).use { ins -> FileOutputStream(out).use { o -> ins.copyTo(o) } }
            return 1
        }
        out.mkdirs()
        var n = 0
        for (nm in names) n += copyAssets("$path/$nm", File(out, nm))
        return n
    }

    // =====================================================================
    // Mikrofon: uyandirma kelimesi dinleme (Vosk) ve araya girme
    // =====================================================================
    private fun openMic(): Boolean {
        val m = model ?: return false
        closeMic()
        return try {
            val rec = Recognizer(m, 16000f)
            val ss = SpeechService(rec, 16000f)
            curRec = rec
            speech = ss
            ss.startListening(wakeListener)
            true
        } catch (e: Exception) {
            Bus.log("Mikrofon/Vosk hatası: ${e.message}")
            false
        }
    }

    private fun closeMic() {
        try { speech?.stop() } catch (e: Exception) {}
        try { speech?.shutdown() } catch (e: Exception) {}
        speech = null
        try { curRec?.close() } catch (e: Exception) {}
        curRec = null
    }

    private fun startWake() {
        if (dead) return
        bargeOn = false
        hideBubble()
        Bus.switchMode("IDLE")
        if (openMic()) {
            state = "WAKE"
            Bus.setState("DİNLİYOR")
            main.postDelayed({ flushQueue() }, 700)
        } else {
            main.postDelayed({ if (!dead && state != "CMD" && state != "CALL") startWake() }, 3000)
        }
    }

    private fun startBarge() {
        if (bargeOn || model == null || dead || ringing || callMode || state == "WAKE") return
        if (openMic()) bargeOn = true
    }

    private val wakeListener = object : VoskListener {
        override fun onPartialResult(h: String?) { main.post { inspect(h, "partial") } }
        override fun onResult(h: String?) { main.post { inspect(h, "text") } }
        override fun onFinalResult(h: String?) { main.post { inspect(h, "text") } }
        override fun onError(e: Exception?) {
            Bus.log("Mikrofon hatası: ${e?.message}")
            main.postDelayed({ if (!dead && state == "WAKE") startWake() }, 2000)
        }
        override fun onTimeout() {}
    }

    private fun inspect(h: String?, field: String) {
        if (h == null || (state != "WAKE" && !bargeOn)) return
        val t = try { JSONObject(h).optString(field) } catch (e: Exception) { "" }
        if (t.isBlank()) return
        if (field == "text") Bus.log("Duydum: $t")
        if (Wake.matches(t)) {
            Bus.log("Uyandırma: \"$t\"")
            if (bargeOn) bargeIn() else onWake()
        }
    }

    private fun onWake() {
        if (state != "WAKE") return
        state = "BUSY"
        closeMic()
        wakeGreeting()
    }

    private fun bargeIn() {
        bargeOn = false
        lastUtt = ""
        pendingAfter = null
        closeMic()
        try { tts?.stop() } catch (e: Exception) {}
        state = "BUSY"
        wakeGreeting()
    }

    private fun wakeGreeting() {
        Bus.setState("UYANDI")
        Bus.switchMode("WAKE")
        showBubble()
        buzz()
        beepTech()
        speak("Buyrun efendim.") { listenCommand() }
    }

    // =====================================================================
    // Komut dinleme (Google ses tanima) ve isleme
    // =====================================================================
    private fun listenCommand() {
        if (dead) return
        state = "CMD"
        Bus.setState("EMİR BEKLİYOR")
        Bus.switchMode("WAKE")
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Bus.log("Konuşma tanıma servisi yok (Google uygulaması gerekli)")
            speak("Ses tanıma servisi bulunamadı efendim.", true) { endConversation() }
            return
        }
        try { recog?.destroy() } catch (e: Exception) {}
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recog = r
        r.setRecognitionListener(object : SpeechListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(code: Int) { main.post { onCmdError(code) } }
            override fun onResults(res: Bundle?) {
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                main.post { onCmdText(t) }
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        try {
            r.startListening(i)
        } catch (e: Exception) {
            Bus.log("Dinleme başlatılamadı: ${e.message}")
            endConversation()
        }
    }

    private fun onCmdError(code: Int) {
        if (state != "CMD") return
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH -> speak("Anlayamadım efendim.", true) { listenCommand() }
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> endConversation()
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                if (busyRetries++ < 3) {
                    main.postDelayed({ if (state == "CMD") listenCommand() }, 700)
                } else {
                    busyRetries = 0
                    endConversation()
                }
            }
            else -> {
                Bus.log("Tanıma hatası: $code")
                speak("Ses tanıma hatası efendim.", true) { endConversation() }
            }
        }
    }

    private fun onCmdText(t: String) {
        if (state != "CMD") return
        busyRetries = 0
        if (t.isBlank()) {
            speak("Anlayamadım efendim.", true) { listenCommand() }
            return
        }
        Bus.log("S: $t")
        Bus.showAction(t)
        val n = Commands.norm(t)
        if (callMode) {
            callMode = false
            handleCallAnswer(n)
            return
        }
        if (n.contains("paydos")) {
            endConversation()
            return
        }
        state = "BUSY"
        Bus.setState("DÜŞÜNÜYOR")
        Bus.switchMode("THINK")
        bg.execute {
            var failed = false
            val reply = try {
                Commands.handle(this, t, n) ?: Brain.ask(this, t)
            } catch (e: Exception) {
                Bus.log("Hata: ${e.message}")
                failed = true
                "Bir aksilik çıktı efendim."
            }
            val bad = failed || reply.contains("Bağlantı kurulamadı") || reply.contains("reddedildi")
            main.post { if (!dead) speak(reply, bad) { listenCommand() } }
        }
    }

    private fun endConversation() {
        state = "BUSY"
        callMode = false
        try { recog?.destroy() } catch (e: Exception) {}
        recog = null
        Bus.log("Sohbet bitti")
        Bus.showAction("")
        main.postDelayed({ startWake() }, 300)
    }

    // =====================================================================
    // Duyurular: mesaj okuma, uyari, hatirlatma
    // =====================================================================
    private fun announce(text: String, urgent: Boolean): Boolean {
        if (state != "WAKE" || dead) return false
        if (isNight() && !urgent) return false
        state = "BUSY"
        closeMic()
        speak(text, urgent) { startWake() }
        return true
    }

    private fun flushQueue() {
        if (state != "WAKE" || msgQueue.isEmpty()) return
        val t = msgQueue.removeAt(0)
        if (!announce(t, false)) msgQueue.add(0, t)
    }

    private fun onNewMessage(m: Msg) {
        if (isNight()) return
        msgQueue.add("${m.from} yazdı: ${m.text.take(120)}")
        if (state == "WAKE") flushQueue()
    }

    // =====================================================================
    // Telefon aramalari
    // =====================================================================
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(st: Int, number: String?) {
            main.post { onCall(st, number) }
        }
    }

    private fun onCall(st: Int, number: String?) {
        when (st) {
            TelephonyManager.CALL_STATE_RINGING -> {
                if (state != "WAKE") return
                state = "BUSY"
                ringing = true
                closeMic()
                val who = nameFor(number)
                speak("$who arıyor efendim. Açayım mı?") {
                    if (ringing) {
                        callMode = true
                        listenCommand()
                    }
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                ringing = false
                callMode = false
                if (state == "WAKE") closeMic()
                try { recog?.destroy() } catch (e: Exception) {}
                recog = null
                hideBubble()
                state = "CALL"
                Bus.setState("ARAMADA")
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                if (state == "CALL" || callMode || ringing) {
                    ringing = false
                    callMode = false
                    try { recog?.destroy() } catch (e: Exception) {}
                    recog = null
                    state = "BUSY"
                    main.postDelayed({ if (!dead && state == "BUSY") startWake() }, 800)
                }
            }
        }
    }

    private fun nameFor(number: String?): String {
        if (number.isNullOrBlank()) return "Bir numara"
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return number
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cur = contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            if (cur != null) {
                cur.use { if (it.moveToFirst()) return it.getString(0) }
            }
        } catch (e: Exception) {
        }
        return number
    }

    private fun handleCallAnswer(n: String) {
        val yes = Regex("\\b(evet|ac|acin|acsana|tamam|cevapla)\\b").containsMatchIn(n)
        val no = Regex("\\b(hayir|reddet|kapat|acma|meskul)\\b").containsMatchIn(n)
        try {
            val tc = getSystemService(TELECOM_SERVICE) as TelecomManager
            if (yes && !no) {
                state = "CALL"
                Bus.setState("ARAMADA")
                try { recog?.destroy() } catch (e: Exception) {}
                recog = null
                hideBubble()
                tc.acceptRingingCall()
                Bus.log("Arama açıldı")
                return
            }
            if (no && Build.VERSION.SDK_INT >= 28) {
                tc.endCall()
                Bus.log("Arama reddedildi")
            }
        } catch (e: SecurityException) {
            Bus.log("Arama izni yok (ANSWER_PHONE_CALLS)")
        } catch (e: Exception) {
            Bus.log("Arama işlemi hatası: ${e.message}")
        }
        endConversation()
    }

    // =====================================================================
    // Zamanlayici: hatirlatmalar, takvim, sabah ve aksam ozeti
    // =====================================================================
    private val ticker = object : Runnable {
        override fun run() {
            if (dead) return
            try { tick() } catch (e: Exception) { Bus.log("Zamanlayıcı hatası: ${e.message}") }
            main.postDelayed(this, 20000L)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (state == "WAKE") {
            val due = Reminders.due(this, now)
            if (due.isNotEmpty()) announce(due.joinToString(" "), true)
        }
        if (state == "WAKE") {
            for (ev in CalendarHelper.events(this, now, now + 16 * 60000L)) {
                val mins = (ev.first - now) / 60000L
                val key = "${ev.first}${ev.second}"
                if (mins >= 0L && mins <= 15L && !announced.contains(key)) {
                    if (announce("$mins dakika sonra ${ev.second} etkinliğiniz var efendim.", false)) announced.add(key)
                    break
                }
            }
        }
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val day = String.format(Locale.US, "%04d%02d%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        val kind = when {
            h in 8..10 -> "morning"
            h in 21..22 -> "evening"
            else -> ""
        }
        if (kind.isNotEmpty() && state == "WAKE" && Prefs.str(this, "sum_$kind") != day) {
            Prefs.setStr(this, "sum_$kind", day)
            bg.execute {
                val text = Summary.build(this, kind)
                main.post {
                    if (!dead && !announce(text, false)) Prefs.setStr(this, "sum_$kind", "")
                }
            }
        }
    }

    // =====================================================================
    // Telefon durumu: pil, isinma, depolama
    // =====================================================================
    private val batt = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
            Bus.lowBattery = !plugged && pct < 20
            if (plugged || pct > 25) {
                warned20 = false; warned10 = false; warned5 = false
            }
            if (!plugged) {
                if (pct <= 5 && !warned5) {
                    if (announce("Pil yüzde $pct efendim. Acil şarj gerekiyor.", true)) warned5 = true
                } else if (pct <= 10 && !warned10) {
                    if (announce("Pil yüzde $pct efendim. Şarj tavsiye ederim.", false)) warned10 = true
                } else if (pct <= 20 && !warned20) {
                    if (announce("Pil yüzde $pct seviyesine düştü efendim.", false)) warned20 = true
                }
            }
            val now = System.currentTimeMillis()
            if (temp >= 42 && now - lastHeat > 15 * 60 * 1000L) {
                if (announce("Telefonun sıcaklığı $temp derece efendim. Biraz dinlendirmenizi öneririm.", false)) lastHeat = now
            }
            if (now - lastStorage > 6 * 3600 * 1000L) {
                try {
                    val st = StatFs(Environment.getDataDirectory().path)
                    val freeGb = st.availableBytes / 1073741824.0
                    if (freeGb < 1.0) {
                        if (announce("Depolama alanı bir gigabaytın altına düştü efendim.", false)) lastStorage = now
                    } else {
                        lastStorage = now
                    }
                } catch (e: Exception) {
                    lastStorage = now
                }
            }
        }
    }
}

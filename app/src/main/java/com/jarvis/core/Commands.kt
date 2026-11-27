package com.jarvis.core

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.content.IntentFilter
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import java.util.Calendar
import java.util.Locale

object Pending {
    @Volatile var msg: Msg? = null
    @Volatile var draft: String? = null
    @Volatile var followup: String = ""
}

object Block {
    private val words = listOf(
        "bank", "finans", "papara", "ininal", "tosla", "enpara", "ziraat", "garanti", "isbank",
        "akbank", "vakif", "halk", "qnb", "denizbank", "kuveyt", "paycell", "fastpay", "param",
        "cepteteb", "odeabank", "fibabanka"
    )

    fun bank(s: String): Boolean {
        val l = s.lowercase()
        return words.any { l.contains(it) }
    }
}

object Commands {
    fun norm(s: String): String = s.lowercase(Locale("tr", "TR"))
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
        .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("[^a-z0-9: ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun has(n: String, re: String) = Regex(re).containsMatchIn(n)
    private var torchOn = false

    private fun launch(ctx: Context, i: Intent): Boolean {
        return try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            Bus.log("Başlatılamadı: ${e.message}")
            false
        }
    }

    /** Yerel komut ise cevabı döner, değilse null (o zaman Gemini'ye gider). */
    fun handle(ctx: Context, raw: String, n: String): String? {
        val warn = if (Settings.canDrawOverlays(ctx)) "" else " Üstte gösterme izni kapalı, işlem engellenmiş olabilir efendim."

        // 0) Bekleyen mesaj taslağı onayı
        val draft = Pending.draft
        val pmsg = Pending.msg
        if (draft != null && pmsg != null) {
            if (has(n, "\\b(evet|gonder|tamam|olur)\\b")) {
                Pending.draft = null
                Pending.msg = null
                val ok = JarvisNotifService.reply(ctx, pmsg, draft)
                return if (ok) "Mesaj gönderildi efendim." else "Gönderemedim efendim, bildirim artık geçerli değil."
            }
            if (has(n, "\\b(hayir|iptal|vazgec|gonderme)\\b")) {
                Pending.draft = null
                Pending.msg = null
                return "İptal edildi efendim."
            }
            Pending.draft = null
            Pending.msg = null
        }

        if (Pending.followup == "alarm") {
            Pending.followup = ""
            val t0 = parseTime(n)
            if (t0 != null) return setAlarm(ctx, t0, warn)
        }

        // Öğrenme: "X dediğimde Y yap"
        val teach = Regex("^(?:ogren )?(.+?) (?:dedigimde|dersem|dedigim zaman) (.+?)(?: yap| calistir)?$").find(n)
        if (teach != null) {
            val phrase = teach.groupValues[1].trim()
            val action = teach.groupValues[2].trim()
            if (phrase.isNotEmpty() && action.isNotEmpty() && phrase != action) {
                Learn.put(ctx, phrase, action)
                return "Öğrendim efendim. $phrase dediğinizde $action yapacağım."
            }
        }
        if (has(n, "neler ogrendin|ogrendiklerini listele")) return Learn.list(ctx)
        if (has(n, "ogrendiklerini sil|hepsini unut")) {
            Learn.clear(ctx)
            return "Öğrendiklerimi sildim efendim."
        }
        val alias = Learn.find(ctx, n)
        if (alias != null && alias != n && depth < 2) {
            depth++
            try {
                return handle(ctx, alias, alias) ?: Brain.ask(ctx, alias)
            } finally {
                depth--
            }
        }

        // Film Jarvis: sohbet cumleleri (internet gerekmez)
        film(n)?.let { return it }

        // Film Jarvis: durum raporu
        if (has(n, "durum raporu|sistem durumu|rapor ver|sistemler nasil|sistemleri kontrol")) {
            return statusReport(ctx)
        }

        // Film Jarvis: parti protokolu
        if (has(n, "parti protokol|parti modu")) {
            Thread { blink(ctx) }.start()
            return "Parti protokolü devrede efendim. " + playMusic(ctx, "party music", warn)
        }

        // Sürüş modu
        if (has(n, "surus modu")) {
            val on = !has(n, "kapat|bitir|kapa")
            Prefs.setStr(ctx, "driving", if (on) "1" else "0")
            return if (on) "Sürüş modu açıldı efendim. Gelen mesajları sesli okuyacağım." else "Sürüş modu kapandı efendim."
        }

        // 1) Zamanlayıcı
        val tm = Regex("(\\d+) (saat|dakika|dk|saniye|sn)").find(n)
        if (tm != null && has(n, "sonra|zamanlayici|sayac")) {
            val v = tm.groupValues[1].toInt()
            val secs = when (tm.groupValues[2]) {
                "saat" -> v * 3600
                "dakika", "dk" -> v * 60
                else -> v
            }
            val i = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!launch(ctx, i)) return "Zamanlayıcıyı kuramadım efendim."
            Reminders.add(ctx, System.currentTimeMillis() + secs * 1000L, "Zamanlayıcı bitti efendim.")
            return "Zamanlayıcı kuruldu efendim.$warn"
        }

        // 2) Alarm
        if (has(n, "\\balarm") && has(n, "kur|ayarla|koy|olustur|ekle")) {
            val t = parseTime(n)
            if (t == null) {
                Pending.followup = "alarm"
                return "Saat kaçta efendim?"
            }
            return setAlarm(ctx, t, warn)
        }

        // 3) Fener
        if (has(n, "fener|flas")) {
            val on = when {
                has(n, "kapat|sondur|kapa") -> false
                has(n, "\\b(ac|acar|acsana|acin|yak)\\b") -> true
                else -> !torchOn
            }
            return if (torch(ctx, on)) (if (on) "Fener açıldı efendim." else "Fener kapandı efendim.")
            else "Fenere erişemedim efendim."
        }

        // 4) Ses seviyesi
        if (has(n, "\\bses")) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when {
                has(n, "yukselt|artir|fazla|yuksek|\\bac\\b") -> {
                    repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0) }
                    return "Ses yükseltildi efendim."
                }
                has(n, "\\bkis\\b|azalt|alcalt|dusur") -> {
                    repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) }
                    return "Ses kısıldı efendim."
                }
                has(n, "sessize|sessiz") -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                    return "Ses kapatıldı efendim."
                }
            }
        }

        // 5) Medya tuşları
        if (has(n, "muzik|sarki")) {
            if (has(n, "sonraki|ileri")) return media(ctx, KeyEvent.KEYCODE_MEDIA_NEXT, "Sonraki şarkı efendim.")
            if (has(n, "onceki")) return media(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Önceki şarkı efendim.")
            if (has(n, "duraklat|durdur")) return media(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE, "Müzik durduruldu efendim.")
            if (has(n, "devam et|oynat")) return media(ctx, KeyEvent.KEYCODE_MEDIA_PLAY, "Devam ediyorum efendim.")
        }

        // 6) Müzik çal
        if (has(n, "\\b(cal|calsana|calin)\\b|calar misin")) {
            val stop = setOf(
                "cal", "calsana", "calin", "calar", "misin", "bana", "bir", "muzik", "muzigi",
                "youtube", "music", "sarki", "sarkisini", "sarkiyi", "de", "da", "bi", "ac",
                "jarvis", "hey", "lutfen"
            )
            val q = raw.split(Regex("\\s+")).filter {
                val k = norm(it)
                k.isNotEmpty() && k !in stop
            }.joinToString(" ")
            return playMusic(ctx, q, warn)
        }

        // 7) Saat
        if (has(n, "saat kac|saati soyle|saat ne")) {
            val c = Calendar.getInstance()
            return String.format(Locale.US, "Saat %02d:%02d efendim.", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }

        // 8) Pil
        if (has(n, "\\bpil\\b|sarj")) {
            return "Pil yüzde ${batteryPct(ctx)} efendim."
        }

        // 9) Hava durumu
        if (has(n, "\\bhava\\b") && has(n, "nasil|durum|derece|yagmur|sicak|soguk")) {
            return Weather.summary(ctx) ?: "Hava durumuna ulaşamadım efendim. Konum izni ve internet gerekiyor."
        }

        // 10) Takvim
        if (has(n, "takvim|program|randevu|toplanti|etkinlik") && has(n, "bugun|yarin|var mi|ne var|neler")) {
            return if (has(n, "yarin")) CalendarHelper.tomorrowText(ctx) else CalendarHelper.todayText(ctx)
        }

        // 11) Mesaj özeti
        if (has(n, "mesaj|\\bdm\\b") && has(n, "var mi|oku|ozet|yeni|neler|bak")) {
            return inboxSummary()
        }

        // 12) Mesaja cevap taslağı
        if (has(n, "cevap yaz|yanit yaz|cevapla|cevap ver|yanitla")) {
            return draftReply(ctx, raw, n)
        }

        // 13) Ekran okuma ve ekran kontrolü
        if (has(n, "ekran") && has(n, "oku|ne var|ozet")) {
            return screenSummary(ctx)
        }
        if (has(n, "bildirimleri ac|bildirim paneli|bildirim cubugu")) {
            return globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Bildirim paneli açıldı efendim.")
        }
        if (has(n, "\\bgeri\\b") && has(n, "git|don|gel")) {
            return globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "Geri döndüm efendim.")
        }
        if (has(n, "ana ekran")) {
            return globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "Ana ekran efendim.")
        }
        if (has(n, "son uygulamalar|acik uygulamalar")) {
            return globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS, "Son uygulamalar efendim.")
        }
        if (has(n, "\\b(dokun|tikla|bas)\\b")) {
            val stop = setOf(
                "dokun", "tikla", "bas", "ona", "jarvis", "hey", "lutfen", "butonuna",
                "dugmesine", "tusuna", "e", "a", "ye", "ya"
            )
            val target = raw.split(Regex("\\s+"))
                .map { it.replace(Regex("[’'].*$"), "") }
                .filter {
                    val k = norm(it)
                    k.isNotEmpty() && k !in stop
                }.joinToString(" ")
            val svc = JarvisAccessService.instance ?: return "Erişilebilirlik izni kapalı efendim."
            if (target.isBlank()) return "Neye dokunacağımı anlayamadım efendim."
            return if (svc.clickText(target)) "Dokundum efendim." else "Ekranda $target bulamadım efendim."
        }

        // 14) Kamera ve galeri
        if (has(n, "video cek|video kaydet")) {
            return if (launch(ctx, Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))) "Kamera açıldı efendim. Kayıt düğmesi sizde.$warn"
            else "Kamerayı açamadım efendim."
        }
        if (has(n, "fotograf cek|foto cek|resim cek|selfie|kamera ac|kamerayi ac")) {
            if (!launch(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))) return "Kamerayı açamadım efendim."
            if (has(n, "fotograf cek|foto cek|resim cek|selfie") && JarvisAccessService.instance != null) {
                Thread { shutter() }.start()
                return "Kamera açıldı, deklanşöre dokunmayı deniyorum efendim.$warn"
            }
            return "Kamera açıldı efendim. Deklanşör sizde.$warn"
        }
        if (has(n, "fotograf|foto|resim") && has(n, "\\bson\\b")) {
            return lastPhoto(ctx, warn)
        }
        if (has(n, "bugun") && has(n, "\\bkac\\b") && has(n, "fotograf|foto|resim")) {
            return photosToday(ctx)
        }

        // 15) Yol tarifi
        if (has(n, "yol tarifi|navigasyon|\\brota\\b")) {
            val stop = setOf(
                "yol", "tarifi", "navigasyon", "rota", "rotasi", "baslat", "bana", "ver",
                "jarvis", "hey", "lutfen", "e", "a", "ye", "ya", "icin"
            )
            val q = raw.split(Regex("\\s+"))
                .map { it.replace(Regex("[’'].*$"), "") }
                .filter {
                    val k = norm(it)
                    k.isNotEmpty() && k !in stop
                }.joinToString(" ")
            if (q.isBlank()) return "Nereye gidiyoruz efendim?"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))
                .setPackage("com.google.android.apps.maps")
            return if (launch(ctx, i)) "$q için yol tarifi başlatıldı efendim.$warn" else "Haritalar açılamadı efendim."
        }

        // 16) Uygulama aç
        if (has(n, "\\b(ac|acar misin|acsana|acin)\\b")) {
            val stop = setOf(
                "ac", "acar", "misin", "acsana", "acin", "uygulamasi", "uygulamayi", "uygulama",
                "lutfen", "jarvis", "hey", "bana", "bir", "bi"
            )
            val target = n.split(" ").filter { it.isNotEmpty() && it !in stop }.joinToString(" ")
            if (target.length >= 3) {
                val label = openApp(ctx, target)
                if (label == "BANKA") return "Bankacılık uygulamalarına dokunmuyorum efendim."
                if (label != null) return "$label açılıyor efendim.$warn"
            }
        }
        return null
    }

    private var depth = 0

    private fun film(n: String): String? = when {
        has(n, "orada misin|burada misin|duyuyor musun") -> "Sizin için her zaman buradayım efendim."
        has(n, "nasilsin|naber|ne haber") -> "Tüm sistemlerim mükemmel durumda efendim. Sormanız çok nazik."
        has(n, "kimsin|adin ne|sen kimsin") -> "Ben J.A.R.V.I.S., yani yeterince zeki bir sistem efendim. Telefonunuzdaki yardımcınızım."
        has(n, "tesekkur|sag ol|eyvallah") -> "Her zaman efendim."
        has(n, "gunaydin") -> "Günaydın efendim. Umarım iyi uyudunuz."
        has(n, "iyi geceler") -> "İyi geceler efendim. Sistemleri nöbette tutacağım."
        has(n, "harikasin|aferin|helal") -> "Teşekkür ederim efendim. Elimden geleni yapıyorum."
        else -> null
    }

    private fun blink(ctx: Context) {
        try {
            for (i in 0 until 6) {
                torch(ctx, true)
                Thread.sleep(250)
                torch(ctx, false)
                Thread.sleep(250)
            }
        } catch (e: Exception) {
        }
    }

    private fun networkName(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "yok"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobil veri"
                else -> "var"
            }
        } catch (e: Exception) {
            "bilinmiyor"
        }
    }

    private fun statusReport(ctx: Context): String {
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = bi != null && bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = if (bi != null) bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10 else 0
        val freeGb = try {
            StatFs(Environment.getDataDirectory().path).availableBytes / 1073741824.0
        } catch (e: Exception) {
            0.0
        }
        val c = Calendar.getInstance()
        val sb = StringBuilder()
        sb.append(String.format(Locale.US, "Saat %02d:%02d. ", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
        sb.append("Pil yüzde ").append(batteryPct(ctx))
        if (charging) sb.append(", şarjda")
        sb.append(". Sıcaklık $temp derece. ")
        sb.append(String.format(Locale("tr", "TR"), "Boş alan %.1f gigabayt. ", freeGb))
        sb.append("Bağlantı: ").append(networkName(ctx)).append(". ")
        val unread = JarvisNotifService.snapshot().size
        if (unread > 0) sb.append("$unread bekleyen mesaj. ")
        sb.append("Tüm sistemler çevrimiçi efendim.")
        return sb.toString()
    }

    private fun setAlarm(ctx: Context, t: Pair<Int, Int>, warn: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, t.first)
            .putExtra(AlarmClock.EXTRA_MINUTES, t.second)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (!launch(ctx, i)) return "Alarmı kuramadım efendim."
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, t.first)
        c.set(Calendar.MINUTE, t.second)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        Reminders.add(ctx, c.timeInMillis, "Alarm vakti efendim.")
        val hhmm = String.format(Locale.US, "%02d:%02d", t.first, t.second)
        return "Alarm $hhmm için kuruldu efendim.$warn"
    }

    private fun shutter() {
        try {
            Thread.sleep(2500)
            val s = JarvisAccessService.instance ?: return
            for (l in listOf("Deklanşör", "Fotoğraf çek", "Çekim", "Shutter", "Take photo", "Capture")) {
                if (s.clickText(l)) {
                    Bus.log("Deklanşöre dokunuldu")
                    return
                }
            }
            Bus.log("Deklanşör düğmesi bulunamadı")
        } catch (e: Exception) {
        }
    }

    private fun clipCount(n: String): Int {
        val m = Regex("\\b(\\d)\\b").find(n)
        if (m != null) return m.groupValues[1].toInt().coerceIn(2, 5)
        return when {
            has(n, "\\buc\\b") -> 3
            has(n, "\\bdort\\b") -> 4
            has(n, "\\bbes\\b") -> 5
            else -> 2
        }
    }

    fun batteryPct(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    // ---------- Mesajlar ----------
    private fun inboxSummary(): String {
        if (JarvisNotifService.instance == null) return "Bildirim erişimi kapalı efendim. İzinler menüsünden açmanız gerekiyor."
        val list = JarvisNotifService.snapshot()
        if (list.isEmpty()) return "Yeni mesajınız yok efendim."
        val groups = list.groupBy { it.from }
        val sb = StringBuilder("${list.size} mesajınız var efendim. ")
        for (e in groups.entries.take(4)) sb.append("${e.key}: ${e.value.size} mesaj. ")
        val last = list.last()
        sb.append("Sonuncusu ${last.from} tarafından: ${last.text.take(120)}")
        return sb.toString()
    }

    private fun draftReply(ctx: Context, raw: String, n: String): String {
        if (JarvisNotifService.instance == null) return "Bildirim erişimi kapalı efendim."
        val list = JarvisNotifService.snapshot().filter { it.ri != null && it.pi != null }
        if (list.isEmpty()) return "Cevaplanabilir bir mesaj bulamadım efendim."
        val target = list.lastOrNull { m ->
            norm(m.from).split(" ").any { it.length >= 3 && n.contains(it) }
        } ?: list.last()

        var content = ""
        val di = raw.lowercase(Locale("tr", "TR")).indexOf(" diye")
        if (di > 0) {
            val nameTokens = norm(target.from).split(" ").filter { it.length >= 3 }
            val skip = setOf("ona", "jarvis", "hey", "bana", "bir")
            content = raw.substring(0, di).split(Regex("\\s+")).filter { t ->
                val k = norm(t)
                k.isNotEmpty() && k !in skip && nameTokens.none { k.startsWith(it) }
            }.joinToString(" ")
        }
        if (content.isBlank()) {
            content = Brain.oneShot(
                ctx,
                "Kısa, doğal ve samimi Türkçe mesaj cevapları yaz. Sadece cevap metnini ver. Tırnak, açıklama veya emoji ekleme.",
                "Gelen mesaj (${target.from}): ${target.text}\nBuna kısa bir cevap yaz."
            ) ?: ""
        }
        if (content.isBlank()) return "Taslak hazırlayamadım efendim."
        Pending.msg = target
        Pending.draft = content
        return "${target.from} kişisine taslak: $content. Göndereyim mi efendim?"
    }

    // ---------- Ekran ----------
    private fun screenSummary(ctx: Context): String {
        val s = JarvisAccessService.instance ?: return "Erişilebilirlik izni kapalı efendim."
        val pkg = s.foregroundPackage()
        if (pkg != null && Block.bank(pkg)) return "Bankacılık ekranına bakmıyorum efendim."
        val t = s.screenText()
        if (t.isNullOrBlank()) return "Ekranda okunabilir bir şey bulamadım efendim."
        return Brain.oneShot(
            ctx,
            "Telefon ekranındaki metni Türkçe, en fazla iki kısa cümleyle özetle. Düz metin yaz.",
            t.take(2500)
        ) ?: "Özetleyemedim efendim."
    }

    private fun globalAction(action: Int, say: String): String {
        val s = JarvisAccessService.instance ?: return "Erişilebilirlik izni kapalı efendim."
        return if (s.performGlobalAction(action)) say else "Yapamadım efendim."
    }

    // ---------- Galeri ----------
    private fun photoPerm(ctx: Context): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    }

    private fun lastPhoto(ctx: Context, warn: String): String {
        if (!photoPerm(ctx)) return "Galeri izni yok efendim."
        var id = -1L
        try {
            val cur = ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null,
                MediaStore.Images.Media.DATE_ADDED + " DESC"
            )
            cur?.use { if (it.moveToFirst()) id = it.getLong(0) }
        } catch (e: Exception) {
        }
        if (id < 0) return "Galeride fotoğraf bulamadım efendim."
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return if (launch(ctx, i)) "En son fotoğrafınız açıldı efendim.$warn" else "Açamadım efendim."
    }

    private fun photosToday(ctx: Context): String {
        if (!photoPerm(ctx)) return "Galeri izni yok efendim."
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        val since = (c.timeInMillis / 1000L).toString()
        var count = 0
        try {
            val cur = ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                MediaStore.Images.Media.DATE_ADDED + " >= ?",
                arrayOf(since), null
            )
            cur?.use { count = it.count }
        } catch (e: Exception) {
        }
        return "Bugün $count fotoğraf çekmişsiniz efendim."
    }

    // ---------- Yardımcılar ----------
    private fun parseTime(n: String): Pair<Int, Int>? {
        var h = -1
        var m = 0
        val a = Regex("(\\d{1,2}):(\\d{2})").find(n)
        if (a != null) {
            h = a.groupValues[1].toInt(); m = a.groupValues[2].toInt()
        } else {
            val b = Regex("saat (\\d{1,2}) (\\d{2})\\b").find(n)
            if (b != null) {
                h = b.groupValues[1].toInt(); m = b.groupValues[2].toInt()
            } else {
                val c = Regex("(\\d{1,2})").find(n)
                if (c != null) h = c.groupValues[1].toInt()
            }
        }
        if (h < 0 || h > 23 || m > 59) return null
        if (n.contains("bucuk")) m = 30
        if (has(n, "aksam|ogleden sonra") && h in 1..11) h += 12
        if (has(n, "gece") && h in 6..11) h += 12
        return Pair(h, m)
    }

    private fun torch(ctx: Context, on: Boolean): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(id, on)
            torchOn = on
            true
        } catch (e: Exception) {
            Bus.log("Fener hatası: ${e.message}")
            false
        }
    }

    private fun media(ctx: Context, code: Int, say: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return say
    }

    private fun playMusic(ctx: Context, q: String, warn: String): String {
        val yt = "com.google.android.apps.youtube.music"
        if (q.isBlank()) {
            val li = ctx.packageManager.getLaunchIntentForPackage(yt)
            return if (li != null && launch(ctx, li)) "YouTube Music açıldı efendim.$warn"
            else "YouTube Music bulunamadı efendim."
        }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(yt)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, q)
        if (launch(ctx, i)) return "$q çalınıyor efendim.$warn"
        val v = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + Uri.encode(q)))
            .setPackage(yt)
        return if (launch(ctx, v)) "$q aranıyor efendim.$warn" else "YouTube Music açılamadı efendim."
    }

    private fun openApp(ctx: Context, target: String): String? {
        val pm = ctx.packageManager
        val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        var bestLabel: String? = null
        var bestPkg: String? = null
        var bestScore = 0
        for (ri in pm.queryIntentActivities(li, 0)) {
            val label = ri.loadLabel(pm).toString()
            val nl = norm(label)
            if (nl.length < 3) continue
            val score = when {
                nl == target -> 3
                target.contains(nl) -> 2
                target.length >= 3 && nl.contains(target) -> 1
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestLabel = label
                bestPkg = ri.activityInfo.packageName
            }
        }
        val pkg = bestPkg ?: return null
        if (Block.bank(pkg) || Block.bank(norm(bestLabel ?: ""))) return "BANKA"
        val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: return null
        return if (launch(ctx, launchIntent)) bestLabel else null
    }
}

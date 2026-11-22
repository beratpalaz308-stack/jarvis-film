package com.jarvis.core

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

object CalendarHelper {
    private fun perm(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun events(ctx: Context, from: Long, to: Long): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        if (!perm(ctx)) return out
        try {
            val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(b, from)
            ContentUris.appendId(b, to)
            val cur = ctx.contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE),
                null, null, CalendarContract.Instances.BEGIN + " ASC"
            )
            cur?.use {
                while (it.moveToNext()) out.add(Pair(it.getLong(0), it.getString(1) ?: "Etkinlik"))
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun dayStart(offsetDays: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, offsetDays)
        return c.timeInMillis
    }

    private fun fmt(list: List<Pair<Long, String>>): String =
        list.take(5).joinToString(", ") {
            val c = Calendar.getInstance()
            c.timeInMillis = it.first
            String.format(Locale.US, "saat %02d:%02d %s", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), it.second)
        }

    fun todayText(ctx: Context, quiet: Boolean = false): String {
        if (!perm(ctx)) return if (quiet) "" else "Takvim izni yok efendim."
        val ev = events(ctx, dayStart(0), dayStart(1))
        return if (ev.isEmpty()) "Bugün takviminiz boş efendim."
        else "Bugün ${ev.size} etkinliğiniz var efendim: ${fmt(ev)}."
    }

    fun tomorrowText(ctx: Context, quiet: Boolean = false): String {
        if (!perm(ctx)) return if (quiet) "" else "Takvim izni yok efendim."
        val ev = events(ctx, dayStart(1), dayStart(2))
        return if (ev.isEmpty()) "Yarın takviminiz boş efendim."
        else "Yarın ${ev.size} etkinliğiniz var efendim: ${fmt(ev)}."
    }
}

object Weather {
    private fun wmo(c: Int): String = when (c) {
        0 -> "hava açık"
        1, 2, 3 -> "parçalı bulutlu"
        45, 48 -> "sisli"
        in 51..57 -> "çisentili"
        in 61..67 -> "yağmurlu"
        in 71..77 -> "karlı"
        in 80..82 -> "sağanak yağışlı"
        in 95..99 -> "gök gürültülü fırtınalı"
        else -> "değişken"
    }

    fun brief(ctx: Context): String? {
        val s = summary(ctx) ?: return null
        val m = Regex("Hava şu an (-?\\d+) derece ve ([^.]+)\\.").find(s) ?: return null
        return m.groupValues[1] + "°C " + m.groupValues[2]
    }

    fun summary(ctx: Context): String? {
        var lat: Double? = null
        var lon: Double? = null
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var best: android.location.Location? = null
                for (p in lm.getProviders(true)) {
                    val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null }
                    if (l != null && (best == null || l.time > best.time)) best = l
                }
                if (best != null) {
                    lat = best.latitude
                    lon = best.longitude
                }
            } catch (e: Exception) {
            }
        }
        if (lat == null || lon == null) {
            lat = Prefs.str(ctx, "lat").toDoubleOrNull()
            lon = Prefs.str(ctx, "lon").toDoubleOrNull()
        }
        if (lat == null || lon == null) return null
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&forecast_days=1&timezone=auto"
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            val txt = try {
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
            val o = JSONObject(txt)
            val cur = o.getJSONObject("current")
            val d = o.getJSONObject("daily")
            val t = Math.round(cur.getDouble("temperature_2m")).toInt()
            val code = cur.getInt("weather_code")
            val mx = Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt()
            val mn = Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt()
            val pr = d.getJSONArray("precipitation_probability_max").optInt(0)
            "Hava şu an $t derece ve ${wmo(code)}. Bugün en düşük $mn, en yüksek $mx derece, yağış ihtimali yüzde $pr."
        } catch (e: Exception) {
            null
        }
    }
}

object Summary {
    fun build(ctx: Context, kind: String): String {
        val sb = StringBuilder()
        if (kind == "morning") {
            sb.append("Günaydın efendim. ")
            val w = Weather.summary(ctx)
            if (w != null) sb.append(w).append(" ")
            sb.append(CalendarHelper.todayText(ctx, true)).append(" ")
        } else {
            sb.append("İyi akşamlar efendim. ")
            val n = JarvisNotifService.snapshot().size
            if (n > 0) sb.append("Bekleyen $n mesajınız var. ")
            sb.append(CalendarHelper.tomorrowText(ctx, true)).append(" ")
        }
        sb.append("Pil yüzde ${Commands.batteryPct(ctx)}.")
        return sb.toString()
    }
}

object Reminders {
    private fun load(ctx: Context): JSONArray = try {
        JSONArray(Prefs.str(ctx, "reminders"))
    } catch (e: Exception) {
        JSONArray()
    }

    @Synchronized
    fun add(ctx: Context, at: Long, text: String) {
        val a = load(ctx)
        a.put(JSONObject().put("t", at).put("s", text))
        Prefs.setStr(ctx, "reminders", a.toString())
    }

    /** Vakti gelenleri dondurur ve listeden siler. 30 dakikadan eski olanlar atilir. */
    @Synchronized
    fun due(ctx: Context, now: Long): List<String> {
        val a = load(ctx)
        val keep = JSONArray()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val t = o.getLong("t")
            if (now >= t) {
                if (now - t < 30 * 60000L) out.add(o.getString("s"))
            } else {
                keep.put(o)
            }
        }
        Prefs.setStr(ctx, "reminders", keep.toString())
        return out
    }
}

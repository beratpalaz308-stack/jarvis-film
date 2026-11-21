package com.jarvis.film

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    @Volatile var current: String = "KAPALI"
    var onUpdate: (() -> Unit)? = null

    fun log(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lines) {
            lines.addLast("$t  $msg")
            while (lines.size > 80) lines.removeFirst()
        }
        main.post { onUpdate?.invoke() }
    }

    fun setState(s: String) {
        current = s
        main.post { onUpdate?.invoke() }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun key(c: Context): String = sp(c).getString("gemini_key", "") ?: ""
    fun setKey(c: Context, v: String) { sp(c).edit().putString("gemini_key", v.trim()).apply() }
    fun voiceIdx(c: Context): Int = sp(c).getInt("voice_idx", -1)
    fun setVoiceIdx(c: Context, v: Int) { sp(c).edit().putInt("voice_idx", v).apply() }
    fun str(c: Context, k: String): String = sp(c).getString(k, "") ?: ""
    fun setStr(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
}

object History {
    private fun file(c: Context) = File(c.filesDir, "history.jsonl")

    @Synchronized
    fun add(c: Context, role: String, text: String) {
        try {
            file(c).appendText(JSONObject().put("r", role).put("t", text).toString() + "\n")
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun last(c: Context, n: Int): List<Pair<String, String>> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        try {
            for (l in f.readLines().takeLast(n)) {
                val j = JSONObject(l)
                out.add(Pair(j.getString("r"), j.getString("t")))
            }
        } catch (e: Exception) {
        }
        return out
    }
}

object Wake {
    private val extra = setOf(
        "carvis", "carviz", "jarviz", "carvi", "jarvi", "arvis",
        "harvis", "garvis", "charvis", "cervis"
    )
    private val others = setOf("cumali", "cavus", "karen")

    fun matches(text: String): Boolean {
        val n = Commands.norm(text)
        val joined = n.replace(" ", "")
        if (joined.contains("jarvis") || joined.contains("carvis") || joined.contains("jarviz")) return true
        for (t in n.split(" ")) {
            if (t.length < 4) continue
            if (t == "jarvis" || t in extra || t in others) return true
            if (t.length in 5..7 && lev(t, "jarvis") <= 1) return true
            if (t.length in 5..7 && lev(t, "cumali") <= 1) return true
        }
        return false
    }

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + c)
            }
        }
        return d[a.length][b.length]
    }
}

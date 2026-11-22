package com.jarvis.core

import android.content.Context
import org.json.JSONObject
import java.io.File

object Learn {
    private fun file(c: Context) = File(c.filesDir, "learned.json")

    @Synchronized
    fun all(c: Context): JSONObject = try {
        JSONObject(file(c).readText())
    } catch (e: Exception) {
        JSONObject()
    }

    @Synchronized
    fun put(c: Context, phrase: String, action: String) {
        val o = all(c)
        o.put(phrase, action)
        try { file(c).writeText(o.toString()) } catch (e: Exception) {}
    }

    @Synchronized
    fun find(c: Context, n: String): String? {
        val o = all(c)
        return if (o.has(n)) o.getString(n) else null
    }

    @Synchronized
    fun clear(c: Context) {
        try { file(c).delete() } catch (e: Exception) {}
    }

    fun list(c: Context): String {
        val o = all(c)
        if (o.length() == 0) return "Henüz bir şey öğretmediniz efendim."
        val sb = StringBuilder("Öğrendiklerim: ")
        val ks = o.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            sb.append(k).append(", ").append(o.getString(k)).append(". ")
        }
        return sb.toString()
    }
}

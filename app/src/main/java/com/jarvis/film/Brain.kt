package com.jarvis.film

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Brain {
    private val MODELS = listOf("gemini-3.5-flash-lite", "gemini-2.5-flash-lite", "gemini-2.5-flash")

    private const val PERSONA =
        "Sen J.A.R.V.I.S.'sin; Tony Stark'ın Iron Man filmlerindeki yapay zeka uşağısın. " +
        "Kullanıcıyla Android telefonunun içinden konuşuyorsun. Her zaman Türkçe konuş. " +
        "Kullanıcıya 'efendim' diye hitap et. Ağırbaşlı, sakin, kibar ve kuru bir İngiliz zekasıyla " +
        "hafif alaycı ol; ara sıra filmden esinlenen bir söz kullan ama abartma. " +
        "Cevapların kısa ve net olsun: en fazla iki üç cümle. Araştırma gerektiren sorularda kısa bir özet ver. " +
        "Emin olmadığın şeyi uydurma. Markdown, liste veya emoji kullanma, çünkü cevabın sesli okunuyor."

    private fun msg(role: String, text: String): JSONObject =
        JSONObject().put("role", role)
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun ask(ctx: Context, text: String): String {
        val key = Prefs.key(ctx)
        if (key.isBlank()) return "Henüz bir API anahtarı girilmemiş efendim."

        val hist = ArrayList(History.last(ctx, 12))
        while (hist.isNotEmpty() && hist[0].first != "user") hist.removeAt(0)
        val contents = JSONArray()
        for (h in hist) contents.put(msg(h.first, h.second))
        contents.put(msg("user", text))

        val combos = MODELS.flatMap { listOf(Pair(it, true), Pair(it, false)) }
        var skipModel: String? = null
        for ((m, search) in combos) {
            if (m == skipModel) continue
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", PERSONA))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 400).put("temperature", 0.8))
            if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))

            val res: Pair<Int, String> = try {
                post(m, key, body.toString())
            } catch (e: IOException) {
                Bus.log("Ağ hatası: ${e.message}")
                return "Çevrimdışıyım efendim. Yerel komutlar çalışmaya devam ediyor."
            }
            val code = res.first
            if (code in 200..299) {
                val t = parse(res.second)
                if (t.isNotBlank()) {
                    History.add(ctx, "user", text)
                    History.add(ctx, "model", t)
                    return t
                }
                continue
            }
            Bus.log("Gemini $m HTTP $code")
            when (code) {
                401, 403 -> return "API anahtarı reddedildi efendim."
                404, 429, 500, 503 -> skipModel = m
                else -> {}
            }
        }
        return "Bağlantı kurulamadı efendim."
    }

    fun oneShot(ctx: Context, system: String, prompt: String): String? {
        val key = Prefs.key(ctx)
        if (key.isBlank()) return null
        val contents = JSONArray().put(msg("user", prompt))
        for (m in MODELS) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("generationConfig", JSONObject().put("maxOutputTokens", 300).put("temperature", 0.6))
            val res: Pair<Int, String> = try {
                post(m, key, body.toString())
            } catch (e: IOException) {
                return null
            }
            if (res.first in 200..299) {
                val t = parse(res.second)
                if (t.isNotBlank()) return t
            }
            if (res.first == 401 || res.first == 403) return null
        }
        return null
    }

    private fun post(model: String, key: String, body: String): Pair<Int, String> {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 25000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, txt)
    }

    private fun parse(resp: String): String {
        return try {
            val parts = JSONObject(resp).optJSONArray("candidates")
                ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text") ?: "")
            sb.toString().replace(Regex("[*#`_]"), "").trim()
        } catch (e: Exception) {
            ""
        }
    }
}

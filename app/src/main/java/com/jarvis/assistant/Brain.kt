package com.jarvis.assistant

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Reply(
    val speech: String,
    val action: String = "none",
    val target: String = "",
    val followUp: Boolean = false
)

/** Talks to Claude (with a tiny offline fallback so basic commands work without a key). */
class Brain(private val ctx: Context) {

    companion object {
        const val KEY_API = "api_key"
        const val KEY_TITLE = "title"

        /** Change this if you want a smarter (slower, pricier) or cheaper model. */
        const val MODEL = "claude-haiku-5-5"

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    }

    private val history = mutableListOf<Pair<String, String>>() // role to text

    val title: String
        get() = prefs(ctx).getString(KEY_TITLE, "")?.trim().takeUnless { it.isNullOrEmpty() } ?: "sir"

    fun ask(userText: String): Reply {
        val key = prefs(ctx).getString(KEY_API, "")?.trim().orEmpty()
        if (key.isEmpty()) return offline(userText, noKey = true)
        return try {
            online(userText, key)
        } catch (e: Exception) {
            offline(userText, noKey = false)
        }
    }

    // ---------------------------------------------------------------- online

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        return """
            You are J.A.R.V.I.S., a refined, dry-witted British AI butler living on your user's Android phone, in the manner of Tony Stark's assistant.
            Address the user as "$title". Your replies are spoken aloud: one or two short sentences, no markdown, no emoji, no lists, never read out URLs. Understated wit is welcome; never gush.
            Current date and time: $now.

            You can perform at most one phone action per reply. Respond with ONLY a JSON object and nothing else:
            {"speech":"what you say aloud","action":"none|open_app|open_url|call|search","target":"...","followUp":false}

            - open_app: target = the app's name as it appears on the phone, e.g. "YouTube", "WhatsApp", "Camera", "Spotify".
            - open_url: target = the full website address, e.g. "https://www.bbc.com".
            - call: target = the contact name exactly as the user said it, or a phone number.
            - search: target = a Google search query.
            - none: just converse; target = "".
            Set followUp to true only if you asked the user a question and need their spoken answer.
            If asked for something you cannot do, say so briefly and offer what you can do.
        """.trimIndent()
    }

    private fun online(userText: String, key: String): Reply {
        history += "user" to userText
        while (history.size > 10) history.removeAt(0)
        while (history.isNotEmpty() && history.first().first != "user") history.removeAt(0)

        val messages = JSONArray()
        history.forEach { (role, text) ->
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 300)
            .put("system", systemPrompt())
            .put("messages", messages)

        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                history.removeLastOrNull() // drop the user turn that failed
                throw RuntimeException("HTTP $code: $raw")
            }
            val text = JSONObject(raw).getJSONArray("content").getJSONObject(0).getString("text")
            history += "assistant" to text
            return parse(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): Reply {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val o = JSONObject(text.substring(start, end + 1))
                return Reply(
                    speech = o.optString("speech", "").ifBlank { "Very good, $title." },
                    action = o.optString("action", "none"),
                    target = o.optString("target", ""),
                    followUp = o.optBoolean("followUp", false)
                )
            } catch (_: Exception) {
            }
        }
        return Reply(text.trim())
    }

    // --------------------------------------------------------------- offline

    private fun offline(raw: String, noKey: Boolean): Reply {
        val t = raw.lowercase().trim().trimEnd('.', '!', '?')

        Regex("^(please )?(call|phone|ring|dial) (.+)$").find(t)?.let {
            val who = it.groupValues[3]
            return Reply("Calling $who, $title.", "call", who)
        }
        Regex("^(please )?(open|launch|start) (the )?(.+?)( app)?$").find(t)?.let {
            val what = it.groupValues[4]
            return if (what.contains('.') && !what.contains(' ')) {
                Reply("Opening $what, $title.", "open_url", what)
            } else {
                Reply("Opening $what, $title.", "open_app", what)
            }
        }
        Regex("^(go to|visit|browse to) (.+)$").find(t)?.let {
            val site = it.groupValues[2].replace(" dot ", ".").replace(" ", "")
            return Reply("Right away, $title.", "open_url", site)
        }
        Regex("^(search|google|look up|search for|search google for) (for )?(.+)$").find(t)?.let {
            return Reply("Searching, $title.", "search", it.groupValues[3])
        }
        if (t.contains("what time")) {
            val now = SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date())
            return Reply("It is $now, $title.")
        }
        if (t.startsWith("hello") || t.startsWith("hi") || t.startsWith("hey")) {
            return Reply("At your service, $title.")
        }
        return if (noKey) {
            Reply("I can only handle basic commands without an API key, $title. Add one in my settings for proper conversation.")
        } else {
            Reply("I seem to have lost my connection, $title. Basic commands still work.")
        }
    }
}

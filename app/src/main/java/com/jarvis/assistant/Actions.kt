package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Things Jarvis can actually do on the phone. Each returns null on success, or a sentence to say on failure. */
object Actions {

    fun execute(ctx: Context, action: String, target: String, title: String): String? {
        return try {
            when (action) {
                "open_app" -> openApp(ctx, target, title)
                "open_url" -> openUrl(ctx, target)
                "search" -> openUrl(ctx, "https://www.google.com/search?q=" + Uri.encode(target))
                "call" -> call(ctx, target, title)
                else -> null
            }
        } catch (e: Exception) {
            "I'm afraid that didn't work, $title."
        }
    }

    private fun launch(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    // ---------- websites ----------

    private fun openUrl(ctx: Context, raw: String): String? {
        var url = raw.trim()
        if (url.isEmpty()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return null
    }

    // ---------- apps ----------

    private fun openApp(ctx: Context, name: String, title: String): String? {
        val pm = ctx.packageManager
        val wanted = name.lowercase().trim()
        if (wanted.isEmpty()) return null

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = pm.queryIntentActivities(launcher, 0).mapNotNull { info ->
            val label = info.loadLabel(pm).toString().lowercase()
            val score = when {
                label == wanted -> 0
                label.startsWith(wanted) -> 1
                label.contains(wanted) -> 2
                label.length >= 3 && wanted.contains(label) -> 3
                else -> return@mapNotNull null
            }
            Triple(score, label, info.activityInfo.packageName)
        }

        val best = candidates.minWithOrNull(
            compareBy<Triple<Int, String, String>> { it.first }.thenBy { it.second.length }
        ) ?: return "I can't find an app called $name, $title."

        val intent = pm.getLaunchIntentForPackage(best.third)
            ?: return "I can't open $name, $title."
        launch(ctx, intent)
        return null
    }

    // ---------- calls ----------

    private fun call(ctx: Context, who: String, title: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "I don't have permission to place calls, $title. Please grant it in my settings."
        }

        val digits = who.filter { it.isDigit() || it == '+' }
        val number: String = if (digits.length >= 5 && who.count { it.isLetter() } == 0) {
            digits
        } else {
            if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                return "I don't have access to your contacts, $title."
            }
            findContactNumber(ctx, who) ?: return "I can't find $who in your contacts, $title."
        }

        launch(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
        return null
    }

    private fun findContactNumber(ctx: Context, name: String): String? {
        val wanted = name.lowercase().trim()
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val matches = mutableListOf<Pair<String, String>>()
        ctx.contentResolver.query(
            phone, cols,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$wanted%"), null
        )?.use { c ->
            while (c.moveToNext()) {
                matches += c.getString(0).orEmpty() to c.getString(1).orEmpty()
            }
        }
        if (matches.isEmpty()) return null
        val best = matches.minByOrNull { (n, _) ->
            val l = n.lowercase()
            when {
                l == wanted -> 0
                l.startsWith(wanted) -> 1
                else -> 2
            }
        }
        return best?.second?.takeIf { it.isNotBlank() }
    }
}

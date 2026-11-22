package com.zfl.os

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Cfg {
    const val VERSION = "202611201809"
}

/** Lets a background service find the running app screen of a package. */
object Hub {
    val apps = HashMap<String, AppActivity>()
}

object Util {
    fun notify(c: Context, title: String, body: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("zfl", "ZFLOS", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(c, "zfl")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
}

object Store {
    private val MRE = Regex(
        "<script[^>]*type=[\"']application/zfl-manifest[\"'][^>]*>([\\s\\S]*?)</script>",
        RegexOption.IGNORE_CASE
    )
    private val IDRE = Regex("^[\\w.\\-]+\$")

    private fun dir(c: Context): File = File(c.filesDir, "packages").also { it.mkdirs() }
    private fun fileOf(c: Context, id: String): File = File(dir(c), id + ".zfl")

    fun manifestOf(text: String, fname: String): JSONObject {
        val m = MRE.find(text)
        val man: JSONObject
        if (m != null) {
            man = try {
                JSONObject(m.groupValues[1])
            } catch (e: Exception) {
                throw Exception("Manifest is not valid JSON in " + fname)
            }
        } else {
            man = JSONObject()
            man.put("zfl", 1)
            man.put("id", "plain." + fname.replace(Regex("\\W+"), "-").lowercase())
            man.put("name", fname.replace(Regex("\\.(zfl|html?)$", RegexOption.IGNORE_CASE), ""))
            man.put("permissions", JSONArray())
        }
        val id = man.optString("id")
        if (id.isEmpty() || !IDRE.matches(id)) throw Exception("Manifest needs an id in " + fname)
        if (man.optString("name").isEmpty()) man.put("name", id)
        if (man.optString("version").isEmpty()) man.put("version", "1")
        if (man.optJSONArray("permissions") == null) man.put("permissions", JSONArray())
        return man
    }

    fun install(c: Context, text: String, fname: String): String {
        val man = manifestOf(text, fname)
        val f = fileOf(c, man.getString("id"))
        val old = f.exists()
        f.writeText(text, Charsets.UTF_8)
        return (if (old) "Updated " else "Installed ") + man.getString("name") + " v" + man.getString("version")
    }

    fun list(c: Context): List<JSONObject> {
        val files = dir(c).listFiles() ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (f in files) {
            if (!f.name.endsWith(".zfl")) continue
            try {
                out.add(manifestOf(f.readText(Charsets.UTF_8), f.name))
            } catch (e: Exception) {
            }
        }
        out.sortBy { it.getString("name").lowercase() }
        return out
    }

    fun html(c: Context, id: String): String? {
        val f = fileOf(c, id)
        return if (f.exists()) f.readText(Charsets.UTF_8) else null
    }

    fun manifest(c: Context, id: String): JSONObject? {
        val h = html(c, id) ?: return null
        return try {
            manifestOf(h, id + ".zfl")
        } catch (e: Exception) {
            null
        }
    }

    fun remove(c: Context, id: String) {
        fileOf(c, id).delete()
        c.getSharedPreferences("data_" + id, 0).edit().clear().apply()
    }

    /** Packages shipped inside the APK. Re-installed whenever the host version changes. */
    fun installBundled(c: Context) {
        val prefs = c.getSharedPreferences("host", 0)
        val force = prefs.getString("bundled", "") != Cfg.VERSION
        val names = c.assets.list("bundled") ?: return
        for (n in names) {
            try {
                val text = c.assets.open("bundled/" + n).bufferedReader().use { it.readText() }
                val man = manifestOf(text, n)
                if (force || !fileOf(c, man.getString("id")).exists()) install(c, text, n)
            } catch (e: Exception) {
            }
        }
        prefs.edit().putString("bundled", Cfg.VERSION).apply()
    }
}

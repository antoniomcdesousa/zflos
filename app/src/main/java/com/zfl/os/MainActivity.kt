package com.zfl.os

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.installBundled(this)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Host(), "Host")
        web.loadUrl("file:///android_asset/launcher.html")
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
        web.evaluateJavascript("refresh()", null)
    }

    private fun handle(i: Intent?) {
        val u = i?.data
        if (i != null && i.action == Intent.ACTION_VIEW && u != null) {
            Toast.makeText(this, importUri(u), Toast.LENGTH_LONG).show()
        }
    }

    private fun queryName(u: Uri): String {
        var n = "package.zfl"
        try {
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ix >= 0) n = c.getString(ix)
                }
            }
        } catch (e: Exception) {
        }
        return n
    }

    private fun importUri(u: Uri): String {
        return try {
            val name = queryName(u)
            val text = contentResolver.openInputStream(u)!!.bufferedReader().use { it.readText() }
            Store.install(this, text, name)
        } catch (e: Exception) {
            "Could not add: " + (e.message ?: "unknown error")
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 11) {
            if (res == RESULT_OK && data != null) {
                val msgs = ArrayList<String>()
                val cd = data.clipData
                if (cd != null) {
                    for (k in 0 until cd.itemCount) {
                        msgs.add(importUri(cd.getItemAt(k).uri))
                    }
                } else {
                    val u = data.data
                    if (u != null) msgs.add(importUri(u))
                }
                web.evaluateJavascript("refresh(" + JSONObject.quote(msgs.joinToString("\n")) + ")", null)
            } else {
                web.evaluateJavascript("refresh(" + JSONObject.quote("Nothing was added.") + ")", null)
            }
        }
    }

    inner class Host {
        @JavascriptInterface
        fun list(): String {
            val a = JSONArray()
            for (m in Store.list(this@MainActivity)) {
                val o = JSONObject()
                o.put("id", m.getString("id"))
                o.put("name", m.getString("name"))
                o.put("version", m.getString("version"))
                o.put("icon", m.optString("icon"))
                a.put(o)
            }
            return a.toString()
        }

        @JavascriptInterface
        fun version(): String = Cfg.VERSION

        @JavascriptInterface
        fun add() {
            runOnUiThread {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                startActivityForResult(i, 11)
            }
        }

        @JavascriptInterface
        fun open(id: String) {
            runOnUiThread {
                val i = Intent(this@MainActivity, AppActivity::class.java)
                i.putExtra("id", id)
                startActivity(i)
            }
        }

        @JavascriptInterface
        fun remove(id: String) {
            Store.remove(this@MainActivity, id)
        }
    }
}

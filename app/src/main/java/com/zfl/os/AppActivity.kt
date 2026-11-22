package com.zfl.os

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Runs one .zfl package in a WebView and gives it the window.zfl bridge (see assets/bridge.js). */
class AppActivity : Activity() {
    private lateinit var web: WebView
    private var pkgId = ""
    private var pkgName = ""
    private var perms: List<String> = emptyList()

    private var chooserCb: ValueCallback<Array<Uri>>? = null
    private var camUri: Uri? = null
    private var permReq: PermissionRequest? = null
    private var shotCallId = -1
    private var targetPkg: String? = null

    private val PERM = mapOf(
        "storage.get" to "storage", "storage.set" to "storage", "storage.remove" to "storage",
        "notify" to "notify", "clipboard.write" to "clipboard", "vibrate" to "vibrate",
        "overlay.show" to "overlay", "overlay.hide" to "overlay", "app.toBack" to "overlay",
        "screenshot.pick" to "screenshot",
        "share.text" to "share", "share.image" to "share",
        "close" to "*"
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.getStringExtra("id")
        val man = if (id == null) null else Store.manifest(this, id)
        if (id == null || man == null) {
            finish()
            return
        }
        pkgId = id
        pkgName = man.optString("name", id)
        val pa = man.optJSONArray("permissions") ?: JSONArray()
        perms = (0 until pa.length()).map { pa.optString(it) }
        Hub.apps[pkgId] = this

        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                val sch = u.scheme ?: return false
                if (sch == "about" || sch == "data" || sch == "blob") return false
                if (sch == "https" && (u.host ?: "").endsWith(".zfl.app")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) {
                runOnUiThread {
                    val cam = r.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (cam && perms.contains("camera")) {
                        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            r.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                        } else {
                            permReq = r
                            requestPermissions(arrayOf(Manifest.permission.CAMERA), 21)
                        }
                    } else {
                        r.deny()
                    }
                }
            }

            override fun onShowFileChooser(
                v: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams
            ): Boolean = chooseFile(cb, p)

            override fun onJsAlert(v: WebView?, url: String?, msg: String?, r: JsResult): Boolean {
                AlertDialog.Builder(this@AppActivity).setMessage(msg)
                    .setPositiveButton("OK") { _, _ -> r.confirm() }
                    .setOnCancelListener { r.cancel() }.show()
                return true
            }

            override fun onJsConfirm(v: WebView?, url: String?, msg: String?, r: JsResult): Boolean {
                AlertDialog.Builder(this@AppActivity).setMessage(msg)
                    .setPositiveButton("OK") { _, _ -> r.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> r.cancel() }
                    .setOnCancelListener { r.cancel() }.show()
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "ZflNative")

        if (perms.contains("camera") &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 22)
        }

        val host = pkgId.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') + ".zfl.app"
        web.loadDataWithBaseURL("https://" + host + "/", inject(Store.html(this, pkgId) ?: ""), "text/html", "utf-8", null)
    }

    private fun inject(html: String): String {
        val shim = "<script>" + assets.open("bridge.js").bufferedReader().use { it.readText() } + "</script>"
        val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (m == null) shim + html else html.substring(0, m.range.last + 1) + shim + html.substring(m.range.last + 1)
    }

    override fun onDestroy() {
        if (Hub.apps[pkgId] === this) Hub.apps.remove(pkgId)
        try {
            web.destroy()
        } catch (e: Exception) {
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- file chooser + camera

    private fun chooseFile(cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams): Boolean {
        chooserCb?.onReceiveValue(null)
        chooserCb = cb
        var cam: Intent? = null
        val wantsImages = p.acceptTypes.any { it.startsWith("image") }
        if (wantsImages && perms.contains("camera") &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val cv = ContentValues()
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, "zfl_" + System.currentTimeMillis() + ".jpg")
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                camUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                if (camUri != null) {
                    cam = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, camUri)
                }
            } catch (e: Exception) {
                camUri = null
            }
        }
        try {
            if (p.isCaptureEnabled && cam != null) {
                startActivityForResult(cam, 31)
            } else {
                val pick = p.createIntent()
                val chooser = Intent.createChooser(pick, "Choose a picture")
                if (cam != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
                startActivityForResult(chooser, 31)
            }
        } catch (e: Exception) {
            chooserCb = null
            return false
        }
        return true
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 31) {
            val cb = chooserCb
            chooserCb = null
            var out: Array<Uri>? = null
            val cu = camUri
            if (res == RESULT_OK) {
                if (data == null || (data.data == null && data.clipData == null)) {
                    if (cu != null) out = arrayOf(cu)
                } else {
                    out = WebChromeClient.FileChooserParams.parseResult(res, data)
                }
            } else if (cu != null) {
                try {
                    contentResolver.delete(cu, null, null)
                } catch (e: Exception) {
                }
            }
            camUri = null
            cb?.onReceiveValue(out)
        } else if (req == 41) {
            if (res == RESULT_OK && data != null) {
                val svc = Intent(this, CaptureService::class.java)
                svc.putExtra("code", res)
                svc.putExtra("data", data)
                svc.putExtra("id", pkgId)
                startForegroundService(svc)
                val t = targetPkg
                val launch = if (t == null) null else packageManager.getLaunchIntentForPackage(t)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                }
            } else {
                shotDone(null, "Screen capture was not allowed.")
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == 21) {
            val pr = permReq
            permReq = null
            if (pr != null) {
                if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) {
                    pr.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pr.deny()
                }
            }
        }
    }

    // ---------------------------------------------------------------- screenshot of another app

    private fun dp(x: Float): Int = (x * resources.displayMetrics.density).toInt()

    private fun pickApp(cb: (String?) -> Unit) {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(i: Int) = list[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val row = LinearLayout(this@AppActivity)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
                val ic = ImageView(this@AppActivity)
                ic.setImageDrawable(list[i].loadIcon(pm))
                row.addView(ic, LinearLayout.LayoutParams(dp(40f), dp(40f)))
                val t = TextView(this@AppActivity)
                t.text = list[i].loadLabel(pm)
                t.textSize = 18f
                t.setPadding(dp(16f), 0, 0, 0)
                t.gravity = android.view.Gravity.CENTER_VERTICAL
                row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40f)))
                return row
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Which app shows the puzzle?")
            .setAdapter(adapter) { _, which -> cb(list[which].activityInfo.packageName) }
            .setNegativeButton("Cancel") { _, _ -> cb(null) }
            .setOnCancelListener { cb(null) }
            .show()
    }

    private fun startShot(id: Int) {
        if (!Settings.canDrawOverlays(this)) {
            askOverlay()
            throw Exception("Allow \"Display over other apps\" for ZFLOS, then press Screenshot again.")
        }
        if (shotCallId != -1) throw Exception("A screenshot is already in progress.")
        shotCallId = id
        pickApp { target ->
            if (target == null) {
                shotDone(null, "Screenshot cancelled.")
            } else {
                targetPkg = target
                val mpm = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(mpm.createScreenCaptureIntent(), 41)
            }
        }
    }

    /** Called by CaptureService (or by us on cancel). Sends the picture back to the page. */
    fun shotDone(path: String?, err: String?) {
        runOnUiThread {
            val cid = shotCallId
            shotCallId = -1
            if (cid != -1) {
                if (path == null) {
                    reply(cid, false, err ?: "Screenshot failed.")
                } else {
                    Thread {
                        try {
                            val bytes = File(path).readBytes()
                            val s = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                            runOnUiThread { reply(cid, true, s) }
                        } catch (e: Exception) {
                            runOnUiThread { reply(cid, false, "Could not read the screenshot.") }
                        }
                    }.start()
                }
            }
        }
    }

    private fun askOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
    }

    // ---------------------------------------------------------------- the bridge

    private fun prefs() = getSharedPreferences("data_" + pkgId, Context.MODE_PRIVATE)

    private fun reply(id: Int, ok: Boolean, v: Any?) {
        val j = JSONObject()
        j.put("ok", ok)
        j.put("v", v ?: JSONObject.NULL)
        web.evaluateJavascript("window.__zflReply(" + id + "," + JSONObject.quote(j.toString()) + ")", null)
    }

    inner class Bridge {
        @JavascriptInterface
        fun call(id: Int, fn: String, args: String) {
            runOnUiThread {
                try {
                    handle(id, fn, JSONObject(if (args.isEmpty()) "{}" else args))
                } catch (e: Exception) {
                    reply(id, false, e.message ?: "Error")
                }
            }
        }
    }

    private fun handle(id: Int, fn: String, a: JSONObject) {
        val need = PERM[fn] ?: throw Exception("Unknown bridge function " + fn)
        if (need != "*" && !perms.contains(need)) {
            throw Exception("Permission \"" + need + "\" is not declared in the package manifest")
        }
        when (fn) {
            "storage.get" -> {
                val s = prefs().getString(a.getString("k"), null)
                reply(id, true, if (s == null) null else JSONArray(s).get(0))
            }
            "storage.set" -> {
                prefs().edit().putString(a.getString("k"), JSONArray().put(a.get("v")).toString()).apply()
                reply(id, true, true)
            }
            "storage.remove" -> {
                prefs().edit().remove(a.getString("k")).apply()
                reply(id, true, true)
            }
            "notify" -> {
                Util.notify(this, a.optString("title", pkgName), a.optString("body", ""))
                reply(id, true, true)
            }
            "clipboard.write" -> {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("zfl", a.getString("text")))
                reply(id, true, true)
            }
            "vibrate" -> {
                @Suppress("DEPRECATION")
                val vb = getSystemService(Vibrator::class.java)
                vb.vibrate(VibrationEffect.createOneShot(a.optLong("ms", 50).coerceIn(1L, 1000L), VibrationEffect.DEFAULT_AMPLITUDE))
                reply(id, true, true)
            }
            "close" -> {
                reply(id, true, true)
                finish()
            }
            "app.toBack" -> {
                reply(id, true, true)
                moveTaskToBack(true)
            }
            "overlay.show" -> {
                if (!Settings.canDrawOverlays(this)) {
                    askOverlay()
                    throw Exception("Allow \"Display over other apps\" for ZFLOS, then try again.")
                }
                val o = a.optJSONObject("opts") ?: JSONObject()
                OverlayService.show(
                    this, pkgId, o.optString("title", pkgName), a.optString("text", ""),
                    o.optJSONArray("grid")?.toString(), o.optJSONArray("auto")?.toString()
                )
                reply(id, true, true)
            }
            "overlay.hide" -> {
                OverlayService.hide(this)
                reply(id, true, true)
            }
            "screenshot.pick" -> startShot(id)
            "share.text" -> {
                val i = Intent(Intent.ACTION_SEND)
                i.type = "text/plain"
                i.putExtra(Intent.EXTRA_TEXT, a.getString("text"))
                startActivity(Intent.createChooser(i, a.optString("title", "Share")))
                reply(id, true, true)
            }
            "share.image" -> {
                val bytes = Base64.decode(a.getString("dataUrl").substringAfter(","), Base64.DEFAULT)
                val cv = ContentValues()
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, "zfl_" + System.currentTimeMillis() + ".png")
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: throw Exception("Could not save the picture to share.")
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                val i = Intent(Intent.ACTION_SEND)
                i.type = "image/png"
                i.putExtra(Intent.EXTRA_STREAM, uri)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, a.optString("title", "Share")))
                reply(id, true, true)
            }
        }
    }
}

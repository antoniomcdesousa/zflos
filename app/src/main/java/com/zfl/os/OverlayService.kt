package com.zfl.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import kotlin.math.max

/** The real floating widget: a small window that stays on top of other apps. Drag the title, resize with the corner or the + and - buttons. */
class OverlayService : Service() {
    private var wm: WindowManager? = null
    private var panel: View? = null
    private var lp: WindowManager.LayoutParams? = null
    private var pkgId = ""
    private var curW = 0

    companion object {
        fun show(c: Context, id: String, title: String, text: String, grid: String?, auto: String?) {
            val i = Intent(c, OverlayService::class.java)
            i.putExtra("id", id)
            i.putExtra("title", title)
            i.putExtra("text", text)
            if (grid != null) i.putExtra("grid", grid)
            if (auto != null) i.putExtra("auto", auto)
            c.startForegroundService(i)
        }

        fun hide(c: Context) {
            c.stopService(Intent(c, OverlayService::class.java))
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        startFg()
        if (i == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        pkgId = i.getStringExtra("id") ?: ""
        build(
            i.getStringExtra("title") ?: "ZFLOS",
            i.getStringExtra("text") ?: "",
            i.getStringExtra("grid"),
            i.getStringExtra("auto")
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removePanel()
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("zfl_float", "Floating widget", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "zfl_float")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ZFLOS floating widget is showing")
            .setContentText("Tap to open ZFLOS")
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(7, n)
        }
    }

    private fun dp(x: Float): Int = (x * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float, color: Int): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        v.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
        v.gravity = Gravity.CENTER
        return v
    }

    private fun removePanel() {
        val p = panel
        if (p != null) {
            try {
                wm?.removeView(p)
            } catch (e: Exception) {
            }
        }
        panel = null
    }

    private fun setW(nw: Int) {
        val l = lp ?: return
        val dm = resources.displayMetrics
        val minW = dp(110f)
        val maxW = max(minW, dm.widthPixels - l.x)
        curW = nw.coerceIn(minW, maxW)
        l.width = curW
        val p = panel ?: return
        try {
            wm?.updateViewLayout(p, l)
        } catch (e: Exception) {
        }
    }

    private fun build(title: String, text: String, gridJson: String?, autoJson: String?) {
        removePanel()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val sp = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        curW = sp.getInt("w", dp(230f)).coerceIn(dp(110f), dm.widthPixels)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xFAFFFFFF.toInt())
        bg.cornerRadius = dp(12f).toFloat()
        bg.setStroke(dp(2f), 0xFF2B6CB0.toInt())
        root.background = bg
        root.setPadding(dp(4f), dp(2f), dp(4f), dp(2f))

        val p = WindowManager.LayoutParams(
            curW, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = sp.getInt("x", dp(16f))
        p.y = sp.getInt("y", dp(120f))
        lp = p

        // header: drag by the title
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val ttl = tv("\u2261 " + title, 14f, 0xFF222222.toInt())
        ttl.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        head.addView(ttl, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        var sx = 0
        var sy = 0
        var dx0 = 0f
        var dy0 = 0f
        ttl.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x
                    sy = p.y
                    dx0 = e.rawX
                    dy0 = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (sx + (e.rawX - dx0).toInt()).coerceIn(0, max(0, dm.widthPixels - dp(60f)))
                    p.y = (sy + (e.rawY - dy0).toInt()).coerceIn(0, max(0, dm.heightPixels - dp(60f)))
                    try {
                        wm?.updateViewLayout(root, p)
                    } catch (ex: Exception) {
                    }
                }
                MotionEvent.ACTION_UP -> sp.edit().putInt("x", p.x).putInt("y", p.y).apply()
            }
            true
        }

        val blue = 0xFF2B6CB0.toInt()
        val minus = tv("\u2212", 20f, blue)
        minus.setOnClickListener { setW((curW * 0.8f).toInt()); sp.edit().putInt("w", curW).apply() }
        val plus = tv("+", 20f, blue)
        plus.setOnClickListener { setW((curW * 1.25f).toInt()); sp.edit().putInt("w", curW).apply() }
        val open = tv("\u29C9", 18f, blue)
        open.setOnClickListener {
            val i = Intent(this, AppActivity::class.java)
            i.putExtra("id", pkgId)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(i)
            } catch (e: Exception) {
            }
        }
        val close = tv("\u2715", 18f, 0xFF8B2D2D.toInt())
        close.setOnClickListener { stopSelf() }
        head.addView(minus)
        head.addView(plus)
        head.addView(open)
        head.addView(close)
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // content: the solution grid, or plain text
        val grid = if (gridJson == null) null else try {
            JSONArray(gridJson)
        } catch (e: Exception) {
            null
        }
        if (grid != null && grid.length() == 81) {
            val ai = if (autoJson == null) null else try {
                JSONArray(autoJson)
            } catch (e: Exception) {
                null
            }
            val sv = SolutionView(this, IntArray(81) { grid.optInt(it) }, BooleanArray(81) { ai?.optBoolean(it) ?: false })
            root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val t = TextView(this)
            t.text = text
            t.textSize = 15f
            t.setTextColor(0xFF111111.toInt())
            t.setPadding(dp(8f), dp(4f), dp(8f), dp(8f))
            root.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // footer: resize handle in the bottom right corner
        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.gravity = Gravity.END
        val handle = tv("\u25E2", 24f, blue)
        handle.setPadding(dp(18f), dp(2f), dp(8f), dp(2f))
        var sw = 0
        var rx0 = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sw = curW
                    rx0 = e.rawX
                }
                MotionEvent.ACTION_MOVE -> setW(sw + (e.rawX - rx0).toInt())
                MotionEvent.ACTION_UP -> sp.edit().putInt("w", curW).apply()
            }
            true
        }
        foot.addView(handle)
        root.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        wm?.addView(root, p)
        panel = root
    }
}

package com.zfl.os

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream

/**
 * Takes ONE screenshot of whatever is on screen. Shows a floating Capture button while you
 * go to the other app. Tap Capture: the button hides, the picture is taken, ZFLOS comes back.
 */
class CaptureService : Service() {
    private var proj: MediaProjection? = null
    private var wm: WindowManager? = null
    private var btn: View? = null
    private var pkgId = ""
    private var done = false
    private var busy = false
    private val h = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        if (i == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        pkgId = i.getStringExtra("id") ?: ""
        startFg()
        val code = i.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data")
        if (data == null || !Settings.canDrawOverlays(this)) {
            finish(null, "Screenshot could not start.")
            return START_NOT_STICKY
        }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val pr = mpm.getMediaProjection(code, data)
            proj = pr
            pr.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    h.post { if (!done) finish(null, "Screenshot stopped.") }
                }
            }, h)
            showButton()
        } catch (e: Exception) {
            finish(null, "Screenshot failed: " + e.message)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeButton()
        super.onDestroy()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("zfl_cap", "Screenshot", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "zfl_cap")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("ZFLOS screenshot")
            .setContentText("Open the puzzle, then tap Capture")
            .setContentIntent(pi)
            .build()
        startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun showButton() {
        val d = resources.displayMetrics.density
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xF01F4E8C.toInt())
        bg.cornerRadius = 40f * d
        row.background = bg

        fun tv(t: String): TextView {
            val v = TextView(this)
            v.text = t
            v.textSize = 18f
            v.setTextColor(Color.WHITE)
            v.setPadding((14f * d).toInt(), (12f * d).toInt(), (14f * d).toInt(), (12f * d).toInt())
            return v
        }

        val grip = tv("\u22EE\u22EE")
        val cap = tv("Capture")
        val x = tv("\u2715")
        cap.setOnClickListener { capture() }
        x.setOnClickListener { finish(null, "Screenshot cancelled.") }
        row.addView(grip)
        row.addView(cap)
        row.addView(x)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = (16f * d).toInt()
        p.y = (200f * d).toInt()

        var sx = 0
        var sy = 0
        var dx0 = 0f
        var dy0 = 0f
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = p.x
                    sy = p.y
                    dx0 = e.rawX
                    dy0 = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - dx0).toInt()
                    p.y = sy + (e.rawY - dy0).toInt()
                    try {
                        wm?.updateViewLayout(row, p)
                    } catch (ex: Exception) {
                    }
                }
            }
            true
        }
        wm?.addView(row, p)
        btn = row
    }

    private fun removeButton() {
        val b = btn
        if (b != null) {
            try {
                wm?.removeView(b)
            } catch (e: Exception) {
            }
        }
        btn = null
    }

    private fun capture() {
        if (busy) return
        busy = true
        btn?.visibility = View.INVISIBLE
        h.postDelayed({ grab() }, 450)
    }

    @Suppress("DEPRECATION")
    private fun grab() {
        val pr = proj
        if (pr == null) {
            finish(null, "Screenshot failed.")
            return
        }
        try {
            val dm = DisplayMetrics()
            wm?.defaultDisplay?.getRealMetrics(dm)
            val w = dm.widthPixels
            val hh = dm.heightPixels
            val ir = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2)
            val ht = HandlerThread("zfl-capture")
            ht.start()
            val bh = Handler(ht.looper)
            var vd: VirtualDisplay? = null
            var got = false
            ir.setOnImageAvailableListener({ r ->
                if (got) {
                    r.acquireLatestImage()?.close()
                } else {
                    val img = r.acquireLatestImage()
                    if (img != null) {
                        got = true
                        try {
                            val pl = img.planes[0]
                            val rs = pl.rowStride
                            val ps = pl.pixelStride
                            val bmp = Bitmap.createBitmap(rs / ps, hh, Bitmap.Config.ARGB_8888)
                            bmp.copyPixelsFromBuffer(pl.buffer)
                            val crop = Bitmap.createBitmap(bmp, 0, 0, w, hh)
                            val f = File(cacheDir, "shot.jpg")
                            FileOutputStream(f).use { crop.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            img.close()
                            h.post {
                                vd?.release()
                                ht.quitSafely()
                                finish(f.absolutePath, null)
                            }
                        } catch (e: Exception) {
                            img.close()
                            h.post {
                                vd?.release()
                                ht.quitSafely()
                                finish(null, "Screenshot failed: " + e.message)
                            }
                        }
                    }
                }
            }, bh)
            vd = pr.createVirtualDisplay(
                "zfl-shot", w, hh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.surface, null, h
            )
            h.postDelayed({
                if (!got && !done) {
                    vd?.release()
                    ht.quitSafely()
                    finish(null, "No picture received. Try again.")
                }
            }, 4000)
        } catch (e: Exception) {
            finish(null, "Screenshot failed: " + e.message)
        }
    }

    private fun finish(path: String?, err: String?) {
        if (done) return
        done = true
        removeButton()
        try {
            proj?.stop()
        } catch (e: Exception) {
        }
        Hub.apps[pkgId]?.shotDone(path, err)
        try {
            val i = Intent(this, AppActivity::class.java)
            i.putExtra("id", pkgId)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(i)
        } catch (e: Exception) {
        }
        stopSelf()
    }
}

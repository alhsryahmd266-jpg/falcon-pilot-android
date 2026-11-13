package app.falconpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class AutoPlayService : Service() {
    private lateinit var cfg: Config
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val ht = HandlerThread("capture").also { it.start() }
    private val bg = Handler(ht.looper)
    private val main = Handler(Looper.getMainLooper())
    private var det: Detector? = null
    private var brain: Brain? = null
    private var bmp: Bitmap? = null
    private var btn: TextView? = null
    private var dbg: DebugView? = null
    private var curW = 0; private var curH = 0; private var dpi = 0
    private var frames = 0; private var fpsT = 0L; private var fps = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY
        cfg = Config(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startFg()
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION") val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        brain = Brain(cfg)
        bg.post {
            val d = Detector(cfg)
            val ok = d.load()
            det = d
            main.post { toast(if (ok) "النموذج جاهز: ${d.info}" else "تعذّر تحميل النموذج: ${d.info}") }
        }
        setupCapture()
        addButton()
        if (cfg.debug) addDebug()
        main.postDelayed(watch, 1000)
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fp", "Falcon Pilot", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getService(this, 0, Intent(this, AutoPlayService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "fp").setContentTitle("Falcon Pilot يعمل")
            .setContentText("اضغط الزر العائم للتشغيل/الإيقاف")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_delete), "إغلاق", pi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun realSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { r -> r.setOnImageAvailableListener({ onFrame(it) }, bg) }

    private fun setupCapture() {
        val (w, h, d) = realSize()
        curW = w; curH = h; dpi = d
        val r = newReader(w, h)
        reader = r
        vd = projection!!.createVirtualDisplay("fp", w, h, d, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    private val watch = object : Runnable {
        override fun run() {
            try {
                val (w, h, d) = realSize()
                if (w != curW || h != curH) {
                    curW = w; curH = h; dpi = d
                    val old = reader
                    val r = newReader(w, h)
                    reader = r
                    vd?.resize(w, h, d)
                    vd?.surface = r.surface
                    old?.close()
                }
            } catch (e: Throwable) { Log.w("FP", "resize: $e") }
            main.postDelayed(this, 1000)
        }
    }

    private fun onFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Throwable) { null } ?: return
        try {
            if (!Bus.active) return
            val d = det ?: return
            if (!d.ready) return
            val plane = img.planes[0]
            val bw = plane.rowStride / plane.pixelStride
            var b = bmp
            if (b == null || b.width != bw || b.height != img.height) {
                b = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888); bmp = b
            }
            b!!.copyPixelsFromBuffer(plane.buffer)
            val res = d.detect(b, img.width, img.height)
            Bus.dets = res
            brain?.step(res, img.width, img.height, d.thumb)
            frames++
            val now = SystemClock.uptimeMillis()
            if (now - fpsT > 1000) { fps = frames; frames = 0; fpsT = now; Bus.status += " | ${fps}fps" }
        } catch (e: Throwable) {
            Log.e("FP", "frame: $e")
        } finally { img.close() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun addButton() {
        val size = (58 * resources.displayMetrics.density).toInt()
        val bgd = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC1E88E5.toInt()) }
        val tv = TextView(this).apply {
            text = "▶"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = bgd
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 30; y = 220 }
        var dX = 0f; var dY = 0f; var sX = 0; var sY = 0; var moved = false; var t0 = 0L
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dX = e.rawX; dY = e.rawY; sX = lp.x; sY = lp.y; moved = false; t0 = SystemClock.uptimeMillis() }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dX) > 12 || abs(e.rawY - dY) > 12) moved = true
                    if (moved) { lp.x = sX + (e.rawX - dX).toInt(); lp.y = sY + (e.rawY - dY).toInt(); wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) { if (SystemClock.uptimeMillis() - t0 > 900) stopSelf() else toggle(bgd, tv) }
            }
            true
        }
        wm.addView(tv, lp)
        btn = tv
        // تحديث نص الحالة على الزر
        main.post(object : Runnable {
            override fun run() {
                if (Bus.active) tv.contentDescription = Bus.status
                main.postDelayed(this, 500)
            }
        })
    }

    private fun toggle(bgd: GradientDrawable, tv: TextView) {
        val g = GestureService.instance
        if (g == null) { toast("فعّل خدمة Falcon Pilot من إعدادات الوصول أولاً"); return }
        if (det?.ready != true) { toast("النموذج غير جاهز — استورد ملف .tflite من التطبيق"); return }
        if (!Bus.active) {
            Bus.active = true; g.startPump()
            tv.text = "■"; bgd.setColor(0xCCE53935.toInt())
        } else {
            Bus.active = false; g.stopPump(); Bus.dets = emptyList()
            tv.text = "▶"; bgd.setColor(0xCC1E88E5.toInt())
        }
    }

    private fun addDebug() {
        val v = DebugView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, lp)
        dbg = v
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        Bus.active = false
        GestureService.instance?.stopPump()
        try { btn?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { dbg?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { vd?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        det?.close()
        ht.quitSafely()
        super.onDestroy()
    }
}

/** طبقة اختيارية ترسم مربعات الاكتشاف فوق اللعبة (للتشخيص). */
class DebugView(ctx: Context) : View(ctx) {
    private val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val txt = Paint().apply { textSize = 28f; color = Color.WHITE }
    override fun onDraw(c: Canvas) {
        for (d in Bus.dets) {
            box.color = when (d.label) { "enemy" -> Color.RED; "ally" -> Color.GREEN; else -> Color.YELLOW }
            c.drawRect(d.box, box)
            c.drawText("${d.label} ${(d.conf * 100).toInt()}", d.box.left, d.box.top - 6, txt)
        }
        c.drawText(Bus.status, 40f, 60f, txt)
        postInvalidateDelayed(120)
    }
}

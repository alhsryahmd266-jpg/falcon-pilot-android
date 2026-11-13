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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.PointF
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
    @Volatile private var brain: Agent? = null
    private var bmp: Bitmap? = null
    private var btn: TextView? = null
    private var dbg: DebugView? = null
    private var scanner: ButtonScanner? = null
    private var scanFinishing = false
    private var calib: FrameLayout? = null
    private var calibView: CalibView? = null
    private var calBtn: TextView? = null
    private var assistOn = false
    private var autoCal = false
    private var firstDetect = false
    private var assistBtn: TextView? = null
    private var assistBgd: GradientDrawable? = null
    private var catcher: View? = null
    private var catcherLp: WindowManager.LayoutParams? = null
    private var savedBrain: Agent? = null
    private var sigs: Signatures? = null
    private var ocr: AllyOcr? = null
    @Volatile private var lastTouchEvt = 0L
    private var curW = 0; private var curH = 0; private var dpi = 0
    private var frames = 0; private var fpsT = 0L; private var fps = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try { startAll(intent) } catch (e: Throwable) {
            try { Trace.log(this, "عطل بدء الخدمة: $e") } catch (_: Throwable) {}
            try { Toast.makeText(this, "عطل أثناء التشغيل: $e", Toast.LENGTH_LONG).show() } catch (_: Throwable) {}
            stopSelf(); START_NOT_STICKY
        }
    }

    private fun startAll(intent: Intent?): Int {
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
        Bus.human = cfg.human
        for ((k, v) in cfg.loadLayout()) Bus.pos[k]?.let { it.x = v.first; it.y = v.second }
        if (cfg.calibrated) Bus.joyR = cfg.joyRf * Bus.sh
        Trace.log(this, "خدمة بدأت")
        bg.post {
          try {
            if (cfg.loading) { cfg.crashCount = cfg.crashCount + 1; cfg.useGpu = false; Trace.log(this, "المحاولة السابقة انهارت (${cfg.crashCount}) → وضع آمن، تم قفل GPU") }
            cfg.loading = true
            Trace.log(this, "تحميل النموذج…")
            val d = Detector(cfg)
            val ok = d.load()
            cfg.loading = false
            Trace.log(this, if (ok) "النموذج جاهز ${d.info}" else "فشل النموذج ${d.info}")
            det = d
            if (cfg.usePolicy && cfg.policyFile.exists()) {
                val pol = Policy(cfg)
                if (pol.load()) { brain = PolicyBrain(cfg, pol); main.post { toast("🧠 نموذج السياسة يعمل") } }
                else main.post { toast("السياسة: ${pol.info} — سيُستخدم العقل الافتراضي") }
            }
            main.post { toast(if (ok) "النموذج جاهز: ${d.info}" else "تعذّر تحميل النموذج: ${d.info}") }
          } catch (e: Throwable) { Trace.log(this, "خطأ تحميل: $e"); main.post { toast("خطأ أثناء تحميل النموذج: $e") } }
        }
        setupCapture()
        addButton()
        addCalButton()
        addAssistButton()
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
        curW = w; curH = h; dpi = d; Bus.sw = w; Bus.sh = h
        val r = newReader(w, h)
        reader = r
        vd = projection!!.createVirtualDisplay("fp", w, h, d, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
    }

    private val watch = object : Runnable {
        override fun run() {
            try {
                val (w, h, d) = realSize()
                if (w != curW || h != curH) {
                    curW = w; curH = h; dpi = d; Bus.sw = w; Bus.sh = h
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
            val sc = scanner
            if (sc == null && !Bus.active) return
            val d = det
            if (sc == null && (d == null || !d.ready)) return
            val plane = img.planes[0]
            val bw = plane.rowStride / plane.pixelStride
            var b = bmp
            if (b == null || b.width != bw || b.height != img.height) {
                b = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888); bmp = b
            }
            b!!.copyPixelsFromBuffer(plane.buffer)
            if (sc != null) {
                sc.feed(b, img.width, img.height)
                if (sc.frames >= 40 && !scanFinishing) { scanFinishing = true; val res = sc.result(); main.post { applyScan(res) } }
                return
            }
            if (!firstDetect) { cfg.loading = true; Trace.log(this, "أول اكتشاف…") }
            val res = d!!.detect(b, img.width, img.height)
            if (!firstDetect) { firstDetect = true; cfg.loading = false; cfg.crashCount = 0; Trace.log(this, "أول اكتشاف نجح (${res.size} كائن)") }
            Bus.dets = res
            Bus.frame = b
            brain?.step(res, img.width, img.height, d.thumb)
            frames++
            val now = SystemClock.uptimeMillis()
            if (now - fpsT > 1000) { fps = frames; frames = 0; fpsT = now; Bus.status += " | ${fps}fps" }
        } catch (e: Throwable) {
            Log.e("FP", "frame: $e")
        } finally { img.close() }
    }


    // ---------- مساعد الضرب ----------
    private fun addAssistButton() {
        val size = (46 * resources.displayMetrics.density).toInt()
        val bgd = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC2E7D32.toInt()) }
        val tv = TextView(this).apply { text = "🔫"; textSize = 18f; gravity = Gravity.CENTER; background = bgd }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 36; y = 220 + (132 * resources.displayMetrics.density).toInt() }
        tv.setOnClickListener { toggleAssist() }
        wm.addView(tv, lp)
        assistBtn = tv; assistBgd = bgd
    }

    private fun toggleAssist() {
        val g = GestureService.instance
        if (g == null) { toast("فعّل خدمة Falcon Pilot من إعدادات الوصول أولاً"); return }
        if (det?.ready != true) { toast("النموذج غير جاهز"); return }
        if (calib != null) { toast("أغلق المعايرة أولاً"); return }
        if (!assistOn) {
            if (Bus.active) { toast("أوقف اللعب الآلي ▶ أولاً"); return }
            if (!cfg.calibrated) { autoCal = true; startScan(); return }
            val o = AllyOcr(); val sg = Signatures(java.io.File(filesDir, "sigs.txt"))
            ocr = o; sigs = sg
            savedBrain = brain
            val ab = AssistBrain(cfg, sg, o) { m -> main.post { toast(m) } }
            brain = ab
            if (!cfg.fireFlat && !ab.fw.learned) ab.fw.startLearning()
            Bus.active = true; g.startPump()
            assistOn = true
            Bus.assistHold = true
            Trace.log(this, "المساعد شغّال")
            assistBgd?.setColor(0xCCE53935.toInt()); assistBtn?.text = "⏹"
            toast("المساعد شغّال: اضرب نار بإيدك وهو يصوّب تلقائياً على أقرب مجسم بشري")
        } else {
            hideCatcher()
            Bus.assistHold = false; Bus.active = false; g.stopPump(); Bus.dets = emptyList()
            brain = savedBrain
            sigs?.save(); ocr?.close(); ocr = null; sigs = null
            assistOn = false
            assistBgd?.setColor(0xCC2E7D32.toInt()); assistBtn?.text = "🔫"
        }
    }

    private fun setCatcherTouchable(on: Boolean) {
        val v = catcher ?: return; val lp = catcherLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                   else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
    }

    private fun releaseHold() {
        Bus.assistHold = false
        setCatcherTouchable(true)
        Bus.idle()
    }

    private val holdWatch = object : Runnable {
        override fun run() {
            if (!Bus.assistHold) return
            if (SystemClock.uptimeMillis() - lastTouchEvt > 700) { releaseHold(); return }
            main.postDelayed(this, 200)
        }
    }

    private fun showCatcher() {
        hideCatcher()
        val size = (96 * resources.displayMetrics.density).toInt()
        val fp = Bus.px("fire")
        val bgd = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x2200E676); setStroke(5, 0xCC00E676.toInt()) }
        val v = View(this).apply { background = bgd }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (fp.x - size / 2).toInt(); y = (fp.y - size / 2).toInt() }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchEvt = SystemClock.uptimeMillis()
                    Bus.assistHold = true
                    setCatcherTouchable(false)   // كي تمر لمسة النار المحقونة إلى اللعبة تحت الطبقة
                    main.postDelayed(holdWatch, 300)
                }
                MotionEvent.ACTION_MOVE -> lastTouchEvt = SystemClock.uptimeMillis()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseHold()
            }
            true
        }
        wm.addView(v, lp)
        catcher = v; catcherLp = lp
    }

    private fun hideCatcher() {
        catcher?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        catcher = null; catcherLp = null
    }

    // ---------- المعايرة ----------
    private fun addCalButton() {
        val size = (46 * resources.displayMetrics.density).toInt()
        val bgd = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC6A1B9A.toInt()) }
        val tv = TextView(this).apply { text = "🎯"; textSize = 18f; gravity = Gravity.CENTER; background = bgd }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 36; y = 220 + (66 * resources.displayMetrics.density).toInt() }
        tv.setOnClickListener { startCalibration() }
        wm.addView(tv, lp)
        calBtn = tv
    }

    private fun startCalibration() {
        if (calib != null) return
        if (assistOn) toggleAssist()
        if (Bus.active) { Bus.active = false; GestureService.instance?.stopPump(); btn?.text = "▶" }
        val pts = LinkedHashMap<String, PointF>()
        for (k in listOf("joy", "fire", "jump", "reload", "crouch", "heal", "aim")) pts[k] = Bus.px(k)
        val cv = CalibView(this, pts)
        cv.joyR = if (cfg.calibrated) cfg.joyRf * Bus.sh else Bus.joyR.coerceAtLeast(0.09f * Bus.sh)
        val frame = FrameLayout(this)
        frame.addView(cv, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xDD000000.toInt()); gravity = Gravity.CENTER }
        fun b(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 12f; setOnClickListener { f() } }
        bar.addView(b("🔍 مسح تلقائي") { startScan() })
        bar.addView(b("R+") { cv.joyR *= 1.1f; cv.invalidate() })
        bar.addView(b("R−") { cv.joyR *= 0.9f; cv.invalidate() })
        bar.addView(b("💾 حفظ") {
            cfg.saveLayout(cv.pts, Bus.sw, Bus.sh); cfg.joyRf = cv.joyR / Bus.sh
            for ((k, p) in cv.pts) Bus.pos[k]?.let { it.x = p.x / Bus.sw; it.y = p.y / Bus.sh }
            Bus.joyR = cv.joyR; closeCalibration(); toast("تم حفظ أماكن الأزرار ✅")
        })
        bar.addView(b("✖") { closeCalibration() })
        frame.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val hint = TextView(this).apply {
            text = "اسحب كل علامة فوق زرّها الحقيقي في اللعبة ثم اضغط حفظ  •  R+/R− لحجم عصا الحركة"
            setTextColor(Color.WHITE); setBackgroundColor(0xAA000000.toInt()); textSize = 14f; setPadding(20, 10, 20, 10)
        }
        frame.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        val lp = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(frame, lp)
        calib = frame; calibView = cv
    }

    private fun closeCalibration() {
        calib?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
        calib = null; calibView = null
    }

    private val scanDrive = object : Runnable {
        var dir = 1
        override fun run() {
            if (scanner == null) return
            Bus.joyX = 0f; Bus.joyY = 1f; Bus.fire = false; Bus.aimDx = dir * Bus.sw * 0.2f; dir = -dir
            main.postDelayed(this, 450)
        }
    }

    private fun startScan() {
        if (scanner != null) return
        toast("جاري المسح 6 ثوانٍ… اترك اللعبة ظاهرة (يتحرك الشخص تلقائياً)")
        calib?.visibility = View.INVISIBLE; btn?.visibility = View.INVISIBLE; calBtn?.visibility = View.INVISIBLE
        scanFinishing = false
        scanner = ButtonScanner(curW, curH)
        GestureService.instance?.startPump()
        main.post(scanDrive)
        main.postDelayed({
            val sc = scanner
            if (sc != null && !scanFinishing) { scanFinishing = true; val res = sc.result(); applyScan(res) }
        }, 15000)
    }

    private fun applyScan(res: ButtonScanner.Result) {
        scanner = null
        main.removeCallbacks(scanDrive)
        GestureService.instance?.stopPump(); Bus.idle()
        calib?.visibility = View.VISIBLE; btn?.visibility = View.VISIBLE; calBtn?.visibility = View.VISIBLE
        if (autoCal) {
            autoCal = false
            val m = LinkedHashMap<String, PointF>()
            for (k in listOf("joy", "fire", "jump", "reload", "crouch", "heal", "aim")) m[k] = res.pts[k] ?: Bus.px(k)
            cfg.saveLayout(m, Bus.sw, Bus.sh)
            if (res.joyR > 0) cfg.joyRf = res.joyR / Bus.sh
            for ((k, p) in m) Bus.pos[k]?.let { it.x = p.x / Bus.sw; it.y = p.y / Bus.sh }
            if (res.joyR > 0) Bus.joyR = res.joyR
            toast(if (res.found == 0 || !res.motionOk) "ما لقيتش أزرار بوضوح (ادخل مباراة وأعد التشغيل). هكمل بأماكن افتراضية." else "اكتشفت ${res.pts.size} زر تلقائياً ✅")
            toggleAssist()
            return
        }
        val cv = calibView ?: return
        for ((k, p) in res.pts) cv.pts[k]?.set(p.x, p.y)
        if (res.joyR > 0) cv.joyR = res.joyR
        cv.invalidate()
        toast("وُجد ${res.found} عنصر دائري، حُدّد ${res.pts.size} زر. " +
            (if (res.motionOk) "راجع الأماكن واسحب أي علامة خاطئة ثم احفظ." else "⚠ الخلفية كانت ثابتة؛ ادخل مباراة وأعد المسح."))
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
        if (calib != null) { toast("أغلق المعايرة أولاً"); return }
        if (assistOn) { toast("أوقف المساعد 🔫 أولاً"); return }
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
        try { calBtn?.let { wm.removeView(it) } } catch (_: Throwable) {}
        try { assistBtn?.let { wm.removeView(it) } } catch (_: Throwable) {}
        hideCatcher(); sigs?.save(); ocr?.close()
        closeCalibration()
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

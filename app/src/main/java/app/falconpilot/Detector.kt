package app.falconpilot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** يشغّل نموذج YOLO (v5/v8/v11 مُصدَّر TFLite) محلياً على الهاتف، GPU ثم CPU كبديل. */
class Detector(private val cfg: Config) {
    var ready = false; private set
    var info = ""; private set
    var thumb = IntArray(0); private set

    private var itp: Interpreter? = null
    private var gpu: GpuDelegate? = null
    private var labels: List<String> = emptyList()
    private var inW = 640
    private var inH = 640
    private var isFloat = true
    private lateinit var buf: ByteBuffer
    private lateinit var lb: Bitmap
    private lateinit var lbCanvas: Canvas
    private lateinit var px: IntArray
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var out: Array<Array<FloatArray>>? = null

    fun load(): Boolean {
        if (cfg.crashCount >= 3) { info = "النموذج انهار 3 مرات على الجهاز؛ استورد نموذجاً آخر أو امسح سجل الأعطال"; return false }
        try {
            if (cfg.activeModel() == null) { info = "لا يوجد نموذج"; return false }
            labels = cfg.labels()
        } catch (e: Throwable) { info = "تعذّر تجهيز النموذج: ${e.message}"; return false }
        try { build(cfg.useGpu) } catch (e: Throwable) {
            Log.w(TAG, "first load failed: $e")
            try { build(false) } catch (e2: Throwable) { info = "فشل تحميل النموذج: ${e2.message}"; return false }
        }
        return ready
    }

    private fun build(useGpu: Boolean) {
        close()
        val o = Interpreter.Options()
        if (useGpu) { gpu = GpuDelegate(); o.addDelegate(gpu) }
        else {
            val lvl = cfg.crashCount
            o.setNumThreads(if (lvl >= 2) 1 else 4)
            if (lvl >= 1) { try { o.setUseXNNPACK(false) } catch (_: Throwable) {} }
        }
        val t = Interpreter(cfg.activeModel()!!, o)
        val ins = t.getInputTensor(0)
        val s = ins.shape()
        inH = s[1]; inW = s[2]
        isFloat = ins.dataType() == DataType.FLOAT32
        buf = ByteBuffer.allocateDirect(inW * inH * 3 * (if (isFloat) 4 else 1)).order(ByteOrder.nativeOrder())
        lb = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
        lbCanvas = Canvas(lb)
        px = IntArray(inW * inH)
        val os = t.getOutputTensor(0).shape()
        out = Array(os[0]) { Array(os[1]) { FloatArray(os[2]) } }
        itp = t
        ready = true
        info = "${if (useGpu) "GPU" else "CPU"} ${inW}x$inH out=${os.joinToString("x")} classes=${labels.size}"
    }

    fun detect(src: Bitmap, w: Int, h: Int): List<Det> {
        val t = itp ?: return emptyList()
        val scale = min(inW / w.toFloat(), inH / h.toFloat())
        val nw = w * scale; val nh = h * scale
        val padX = (inW - nw) / 2f; val padY = (inH - nh) / 2f
        lbCanvas.drawColor(Color.rgb(114, 114, 114))
        lbCanvas.drawBitmap(src, Rect(0, 0, w, h), RectF(padX, padY, padX + nw, padY + nh), paint)
        lb.getPixels(px, 0, inW, 0, 0, inW, inH)
        buf.rewind()
        if (isFloat) {
            val fb = buf.asFloatBuffer()
            for (p in px) {
                fb.put(((p shr 16) and 255) / 255f); fb.put(((p shr 8) and 255) / 255f); fb.put((p and 255) / 255f)
            }
        } else {
            for (p in px) { buf.put((p shr 16).toByte()); buf.put((p shr 8).toByte()); buf.put(p.toByte()) }
        }
        buf.rewind()
        // مصغّرة لكشف التعليق (الحركة)
        val th = IntArray(32 * 18)
        for (gy in 0 until 18) for (gx in 0 until 32) {
            val p = px[(gy * inH / 18) * inW + gx * inW / 32]
            th[gy * 32 + gx] = (((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255)) / 3
        }
        thumb = th

        val o = out!!
        try { t.run(buf, o) } catch (e: Throwable) {
            if (gpu != null) { Log.w(TAG, "GPU run failed, fallback CPU: $e"); build(false); return emptyList() }
            throw e
        }
        return parse(o[0], scale, padX, padY, w, h)
    }

    private fun parse(o: Array<FloatArray>, scale: Float, padX: Float, padY: Float, w: Int, h: Int): List<Det> {
        val a = o.size; val b = o[0].size
        val chFirst = a < b
        val ch = if (chFirst) a else b
        val n = if (chFirst) b else a
        fun v(c: Int, i: Int) = if (chFirst) o[c][i] else o[i][c]
        val hasObj = ch == 5 + labels.size
        val off = if (hasObj) 5 else 4
        val nc = ch - off
        val thr = cfg.conf
        val res = ArrayList<Det>()
        for (i in 0 until n) {
            var best = -1; var bs = 0f
            for (c in 0 until nc) { val s = v(off + c, i); if (s > bs) { bs = s; best = c } }
            if (hasObj) bs *= v(4, i)
            if (best < 0 || bs < thr) continue
            var cx = v(0, i); var cy = v(1, i); var bw = v(2, i); var bh = v(3, i)
            if (cx <= 1.5f && cy <= 1.5f && bw <= 1.5f) { cx *= inW; cy *= inH; bw *= inW; bh *= inH }
            val x1 = ((cx - bw / 2 - padX) / scale).coerceIn(0f, w.toFloat())
            val y1 = ((cy - bh / 2 - padY) / scale).coerceIn(0f, h.toFloat())
            val x2 = ((cx + bw / 2 - padX) / scale).coerceIn(0f, w.toFloat())
            val y2 = ((cy + bh / 2 - padY) / scale).coerceIn(0f, h.toFloat())
            if (x2 - x1 < 2 || y2 - y1 < 2) continue
            res.add(Det(labels.getOrElse(best) { "cls$best" }, bs, RectF(x1, y1, x2, y2)))
        }
        res.sortByDescending { it.conf }
        val keep = ArrayList<Det>()
        for (d in res) {
            if (keep.none { it.label == d.label && iou(it.box, d.box) > 0.5f }) keep.add(d)
            if (keep.size >= 60) break
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val u = a.width() * a.height() + b.width() * b.height() - inter
        return if (u <= 0f) 0f else inter / u
    }

    fun close() {
        ready = false
        try { itp?.close() } catch (_: Throwable) {}
        try { gpu?.close() } catch (_: Throwable) {}
        itp = null; gpu = null
    }

    companion object { const val TAG = "FalconDetector" }
}

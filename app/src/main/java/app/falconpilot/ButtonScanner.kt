package app.falconpilot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * يكتشف أزرار الواجهة (HUD) بدون نموذج: عناصر الواجهة تبقى ثابتة بينما الخلفية تتحرك،
 * فنجمع حواف ثابتة عبر عدة لقطات ثم نستخرج الأشكال الدائرية ونصنّفها حسب موقعها وحجمها.
 */
class ButtonScanner(private val W: Int, private val H: Int) {
    private val s = 4
    private val sw = W / s
    private val sh = H / s
    private val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val px = IntArray(sw * sh)
    private val gray = IntArray(sw * sh)
    private val count = IntArray(sw * sh)
    private var prev: IntArray? = null
    private var lastT = 0L
    private var motionSum = 0.0
    @Volatile var frames = 0; private set

    class Cand(val x: Float, val y: Float, val r: Float)
    class Result(val pts: Map<String, PointF>, val joyR: Float, val found: Int, val motionOk: Boolean)

    fun feed(src: Bitmap, w: Int, h: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastT < 150) return
        lastT = now
        canvas.drawBitmap(src, Rect(0, 0, w, h), Rect(0, 0, sw, sh), paint)
        bmp.getPixels(px, 0, sw, 0, 0, sw, sh)
        for (i in px.indices) {
            val p = px[i]
            gray[i] = (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
        }
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val i = y * sw + x
            if (abs(gray[i + 1] - gray[i - 1]) + abs(gray[i + sw] - gray[i - sw]) > 40) count[i]++
        }
        val p0 = prev
        if (p0 != null) {
            var c = 0; var n = 0
            var i = 0
            while (i < gray.size) { if (abs(gray[i] - p0[i]) > 25) c++; n++; i += 7 }
            motionSum += c.toDouble() / n
        }
        prev = gray.copyOf()
        frames++
    }

    fun result(): Result {
        val n = max(1, frames)
        val thr = (0.6f * n).toInt().coerceAtLeast(2)
        var m = BooleanArray(sw * sh) { count[it] >= thr }
        m = dilate(dilate(m)) // نصف قطر ~2 بكسل
        val seen = BooleanArray(sw * sh)
        val stack = IntArray(sw * sh)
        val cands = ArrayList<Cand>()
        val minD = 0.06f * sh; val maxD = 0.34f * sh
        for (start in m.indices) {
            if (!m[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var x0 = sw; var x1 = 0; var y0 = sh; var y1 = 0; var area = 0
            while (sp > 0) {
                val i = stack[--sp]; area++
                val x = i % sw; val y = i / sw
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x > 0 && m[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < sw - 1 && m[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && m[i - sw] && !seen[i - sw]) { seen[i - sw] = true; stack[sp++] = i - sw }
                if (y < sh - 1 && m[i + sw] && !seen[i + sw]) { seen[i + sw] = true; stack[sp++] = i + sw }
            }
            val bw = (x1 - x0 + 1).toFloat(); val bh = (y1 - y0 + 1).toFloat()
            val asp = bw / bh
            if (bw < minD || bh < minD || bw > maxD || bh > maxD || asp < 0.6f || asp > 1.7f) continue
            if (area < 0.12f * bw * bh) continue
            val cy = (y0 + y1) / 2f * s
            if (cy < 0.3f * H) continue
            cands.add(Cand((x0 + x1) / 2f * s, cy, max(bw, bh) / 2f * s))
        }
        // احذف العناصر الداخلة داخل عنصر أكبر (أيقونة داخل حلقة)
        val top = cands.filter { c -> cands.none { o -> o !== c && o.r > c.r && hypot(o.x - c.x, o.y - c.y) < o.r } }
        val left = top.filter { it.x < W * 0.5f }
        val right = top.filter { it.x >= W * 0.5f }
        val joy = left.maxByOrNull { it.r }
        val fire = right.maxByOrNull { it.r }
        val out = LinkedHashMap<String, PointF>()
        if (joy != null) out["joy"] = PointF(joy.x, joy.y)
        if (fire != null) out["fire"] = PointF(fire.x, fire.y)
        val others = (right.filter { it !== fire }.sortedBy { if (fire != null) hypot(it.x - fire.x, it.y - fire.y) else 0f } +
            left.filter { it !== joy })
        val names = listOf("jump", "reload", "crouch", "heal")
        for ((i, c) in others.take(names.size).withIndex()) out[names[i]] = PointF(c.x, c.y)
        val motionOk = frames < 2 || motionSum / (frames - 1) > 0.02
        return Result(out, joy?.r?.times(0.9f) ?: 0.09f * H, top.size, motionOk)
    }

    private fun dilate(a: BooleanArray): BooleanArray {
        val t = BooleanArray(a.size)
        for (y in 0 until sh) for (x in 0 until sw) {
            val i = y * sw + x
            t[i] = a[i] || (x > 0 && a[i - 1]) || (x < sw - 1 && a[i + 1])
        }
        val o = BooleanArray(a.size)
        for (y in 0 until sh) for (x in 0 until sw) {
            val i = y * sw + x
            o[i] = t[i] || (y > 0 && t[i - sw]) || (y < sh - 1 && t[i + sw])
        }
        return o
    }
}

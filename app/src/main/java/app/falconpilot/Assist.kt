package app.falconpilot

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** يقرأ الأرقام 1..4 فوق رأس المجسم (حليف) بقارئ نصوص محلي، ويتذكر الحكم أثناء تتبّع المجسم. */
class AllyOcr {
    private class V(var x: Float, var y: Float, var ally: Boolean, var ts: Long, var allySeen: Long)
    private val vs = ArrayList<V>()
    private val ex = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false
    private val rec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** true = حليف، false = عدو، null = لم يُحسم بعد. */
    fun verdict(d: Det, frame: Bitmap, now: Long): Boolean? {
        var cached: V? = null
        synchronized(vs) {
            vs.removeAll { now - it.ts > 6000 }
            cached = vs.minByOrNull { hypot(it.x - d.cx, it.y - d.cy) }
                ?.takeIf { hypot(it.x - d.cx, it.y - d.cy) < max(d.box.width() * 1.0f, 50f) }
            cached?.let { it.x = d.cx; it.y = d.cy }
        }
        val c = cached
        val fresh = c != null && now - c.ts < 1200
        if (!fresh && !busy) schedule(d, frame, now)
        return c?.ally
    }

    private fun schedule(d: Det, frame: Bitmap, now: Long) {
        val b = d.box
        val w = b.width(); val h = b.height()
        val x1 = (b.left - w * 0.5f).toInt().coerceAtLeast(0)
        val x2 = (b.right + w * 0.5f).toInt().coerceAtMost(frame.width)
        val y1 = (b.top - h * 0.5f).toInt().coerceAtLeast(0)
        val y2 = (b.top + h * 0.2f).toInt().coerceAtMost(frame.height)
        if (x2 - x1 < 16 || y2 - y1 < 12) return
        val crop = try { Bitmap.createBitmap(frame, x1, y1, x2 - x1, y2 - y1) } catch (_: Throwable) { return }
        val cx = d.cx; val cy = d.cy
        busy = true
        ex.execute {
            try {
                val r = Tasks.await(rec.process(InputImage.fromBitmap(crop, 0)), 900, TimeUnit.MILLISECONDS)
                var found = false
                for (blk in r.textBlocks) for (ln in blk.lines) for (el in ln.elements) {
                    val t = el.text.trim()
                    val digits = t.filter { it.isDigit() }
                    if (t.length <= 2 && digits.length == 1 && (digits[0] in '1'..'4' || digits[0] in '١'..'٤')) found = true
                }
                val t = SystemClock.uptimeMillis()
                synchronized(vs) {
                    val v = vs.minByOrNull { hypot(it.x - cx, it.y - cy) }?.takeIf { hypot(it.x - cx, it.y - cy) < max(b.width() * 1.0f, 50f) }
                    if (v == null) vs.add(V(cx, cy, found, t, if (found) t else 0L))
                    else {
                        // الرقم قد يختفي لحظة (دوران/تغطية): لا نلغي حكم الحليف قبل 4 ثوانٍ
                        if (found) { v.ally = true; v.allySeen = t } else if (!(v.ally && t - v.allySeen < 4000)) v.ally = false
                        v.ts = t
                    }
                }
            } catch (_: Throwable) {
            } finally { try { crop.recycle() } catch (_: Throwable) {}; busy = false }
        }
    }

    fun close() { try { ex.shutdownNow() } catch (_: Throwable) {} }
}

/** طبقة التعلّم: بصمات لونية لما تضرب عليه (عدو) ولما عليه رقم (حليف)، تُحفظ على الهاتف. */
class Signatures(private val file: File) {
    private val enemy = ArrayList<FloatArray>()
    private val ally = ArrayList<FloatArray>()
    private var dirty = 0

    init { load() }

    private fun sig(f: Bitmap, b: RectF): FloatArray? {
        val x1 = b.left.toInt().coerceIn(0, f.width - 1); val x2 = b.right.toInt().coerceIn(x1 + 1, f.width)
        val y1 = b.top.toInt().coerceIn(0, f.height - 1); val y2 = b.bottom.toInt().coerceIn(y1 + 1, f.height)
        if (x2 - x1 < 6 || y2 - y1 < 6) return null
        val h = FloatArray(64); var n = 0
        val sx = max(1, (x2 - x1) / 12); val sy = max(1, (y2 - y1) / 16)
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                val p = f.getPixel(x, y)
                val r = ((p shr 16) and 255) shr 6; val g = ((p shr 8) and 255) shr 6; val bl = (p and 255) shr 6
                h[r * 16 + g * 4 + bl] += 1f; n++
                x += sx
            }
            y += sy
        }
        if (n == 0) return null
        for (i in h.indices) h[i] = h[i] / n.toFloat()
        return h
    }

    private fun dist(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) s += abs(a[i] - b[i]); return s / 2f }

    private fun add(list: ArrayList<FloatArray>, s: FloatArray) {
        var bi = -1; var bd = 0.12f
        for (i in list.indices) { val d = dist(list[i], s); if (d < bd) { bd = d; bi = i } }
        if (bi >= 0) { val o = list[bi]; for (i in o.indices) o[i] = o[i] * 0.9f + s[i] * 0.1f }
        else { list.add(s); if (list.size > 64) list.removeAt(0) }
        if (++dirty >= 25) { dirty = 0; save() }
    }

    fun addEnemy(f: Bitmap, b: RectF) { sig(f, b)?.let { add(enemy, it) } }
    fun addAlly(f: Bitmap, b: RectF) { sig(f, b)?.let { add(ally, it) } }

    /** يبدو حليفاً (بالشكل/اللون) حتى لو الرقم غير مقروء؟ */
    fun looksAlly(f: Bitmap, b: RectF): Boolean {
        if (ally.size < 3) return false
        val s = sig(f, b) ?: return false
        val da = ally.minOf { dist(it, s) }
        val de = if (enemy.isEmpty()) 1f else enemy.minOf { dist(it, s) }
        return da < 0.10f && da < 0.6f * de
    }

    fun count() = "${enemy.size}/${ally.size}"
    fun reset() { enemy.clear(); ally.clear(); try { file.delete() } catch (_: Throwable) {} }

    fun save() {
        try {
            file.writeText((enemy.map { "E " + it.joinToString(",") } + ally.map { "A " + it.joinToString(",") }).joinToString("\n"))
        } catch (_: Throwable) {}
    }

    private fun load() {
        try {
            if (!file.exists()) return
            for (ln in file.readLines()) {
                if (ln.length < 3) continue
                val v = ln.substring(2).split(",").map { it.toFloat() }.toFloatArray()
                if (v.size != 64) continue
                if (ln[0] == 'E') enemy.add(v) else if (ln[0] == 'A') ally.add(v)
            }
        } catch (_: Throwable) {}
    }
}

/**
 * مساعد الضرب: أنت تتحرك بإيدك. أول ما تضغط على زر النار (الطبقة الشفافة) يصوّب تلقائياً
 * (رأس ← صدر ← أي جزء ظاهر) على أي مجسم بشري أو عربية/طائرة، ويتجاهل من فوقه رقم 1..4.
 */
class AssistBrain(private val cfg: Config, private val sigs: Signatures, private val ocr: AllyOcr) : Agent {
    private val vehicle = setOf("vehicle", "helicopter", "car", "truck", "bus", "airplane", "motorcycle", "boat")
    private var lastTx = 0f; private var lastTy = 0f; private var lastSeen = 0L
    private var lockSince = 0L; private var learned = false
    private var targetBox: RectF? = null

    private fun aimPoint(d: Det): Pair<Float, Float> {
        val b = d.box; val h = b.height(); val w = b.width()
        val y = if (d.label == "enemy") {
            val ar = h / max(w, 1f)
            when {
                ar >= 2.0f -> b.top + h * 0.09f   // جسم كامل: الرأس
                ar >= 1.2f -> b.top + h * 0.17f   // جزء علوي: الرأس/الصدر
                else -> b.top + h * 0.32f         // جزء ظاهر: الصدر/الوسط
            }
        } else b.centerY()
        return Pair(b.centerX(), y)
    }

    override fun step(dets: List<Det>, W: Int, H: Int, thumb: IntArray?) {
        Bus.sw = W; Bus.sh = H
        if (!Bus.assistHold) { Bus.idle(); lockSince = 0L; learned = false; return }
        val now = SystemClock.uptimeMillis()
        val frame = Bus.frame
        val cx = W * cfg.crossX; val cy = H * cfg.crossY
        val live = ArrayList<Det>()
        for (d in dets) {
            if (d.label != "enemy" && d.label !in vehicle) continue
            if (d.box.width() < 8f || d.box.height() < 8f) continue
            if (d.label == "enemy" && frame != null) {
                val v = ocr.verdict(d, frame, now)
                if (v == true) { sigs.addAlly(frame, d.box); continue }
                if (v == null && sigs.looksAlly(frame, d.box)) continue
            }
            live.add(d)
        }
        val kept = if (lastSeen != 0L && now - lastSeen < 400)
            live.minByOrNull { hypot(it.cx - lastTx, it.cy - lastTy) }?.takeIf { hypot(it.cx - lastTx, it.cy - lastTy) < W * 0.12f } else null
        val target = kept ?: live.minByOrNull { hypot(it.cx - cx, it.cy - cy) }

        Bus.fire = false; Bus.joyX = 0f; Bus.joyY = 0f   // إنت اللي بتتحرك وبتضرب؛ التطبيق يصوّب فقط
        if (target == null) { Bus.aimDx = 0f; Bus.aimDy = 0f; Bus.status = "مساعد | لا هدف"; lockSince = 0L; return }
        if (kept == null) { lockSince = now; learned = false }
        lastTx = target.cx; lastTy = target.cy; lastSeen = now; targetBox = target.box

        val (ax, ay) = aimPoint(target)
        val ex = ax - cx; val ey = ay - cy
        val gain = max(cfg.aimGain, 0.5f) * 1.3f
        val yMul = if (cfg.invertY) -1f else 1f
        Bus.aimDx = (ex * gain).coerceIn(-W * 0.3f, W * 0.3f)
        Bus.aimDy = (ey * gain * yMul).coerceIn(-W * 0.15f, W * 0.15f)
        // التعلّم: ما ضربت عليه أكثر من نصف ثانية بدون رقم = عدو
        if (!learned && frame != null && target.label == "enemy" && now - lockSince > 500) {
            sigs.addEnemy(frame, target.box); learned = true
        }
        Bus.status = "مساعد | ${target.label} | مكتسب ${sigs.count()}"
    }
}

package app.falconpilot

import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

interface Agent { fun step(dets: List<Det>, W: Int, H: Int, thumb: IntArray?) }

/** مواصفات المدخلات/المخرجات — يجب أن يطابقها نموذج التدريب (انظر training/falcon_spec.py). */
object Obs {
    const val DIM = 82
    const val ACT = 9   // joyX, joyY, aimX, aimY, fire, jump, reload, heal, crouch

    private fun clamp(v: Float, a: Float, b: Float) = max(a, min(b, v))

    fun build(
        enemies: List<Pair<Det, FloatArray>>, dets: List<Det>, W: Int, H: Int, cx: Float, cy: Float,
        lowHp: Boolean, stuck: Boolean, memAge: Float, memRx: Float, memRy: Float, prev: FloatArray
    ): FloatArray {
        val o = FloatArray(DIM); var k = 0
        fun rx(d: Det) = (d.cx - cx) / W
        fun ry(d: Det) = (d.cy - cy) / H
        fun dist(d: Det) = hypot(rx(d), ry(d))
        // A) أعداء: 5 خانات × 8
        val en = enemies.sortedBy { dist(it.first) }
        for (i in 0 until 5) {
            if (i < en.size) {
                val (d, v) = en[i]
                o[k++] = 1f; o[k++] = rx(d); o[k++] = ry(d); o[k++] = d.box.width() / W; o[k++] = d.box.height() / H
                o[k++] = clamp(v[0] / W, -2f, 2f); o[k++] = clamp(v[1] / H, -2f, 2f); o[k++] = d.conf
            } else k += 8
        }
        // B) حلفاء: 3 × 4
        val al = dets.filter { it.label == "ally" }.sortedBy { dist(it) }
        for (i in 0 until 3) {
            if (i < al.size) { val d = al[i]; o[k++] = 1f; o[k++] = rx(d); o[k++] = ry(d); o[k++] = d.box.height() / H } else k += 4
        }
        // C) غنائم (الأقرب من كل نوع): medkit, ammo, weapon × 4
        for (lab in listOf("medkit", "ammo", "weapon")) {
            val d = dets.filter { it.label == lab }.minByOrNull { dist(it) }
            if (d != null) { o[k++] = 1f; o[k++] = rx(d); o[k++] = ry(d); o[k++] = d.box.height() / H } else k += 4
        }
        // D) الزون (الأكبر مساحة): safe_zone, danger_zone × 4
        for (lab in listOf("safe_zone", "danger_zone")) {
            val d = dets.filter { it.label == lab }.maxByOrNull { it.box.width() * it.box.height() }
            if (d != null) { o[k++] = 1f; o[k++] = rx(d); o[k++] = ry(d); o[k++] = d.box.height() / H } else k += 4
        }
        // E) مفردات
        o[k++] = if (lowHp) 1f else 0f
        o[k++] = if (stuck) 1f else 0f
        o[k++] = memAge; o[k++] = memRx; o[k++] = memRy
        // F) آخر فعل: joyX, joyY, aimX, aimY, fire
        for (i in 0 until 5) o[k++] = prev[i]
        return o
    }
}

/** متتبّع بسيط للأعداء: يربط الإطارات ويقدّر السرعة (بكسل/ثانية على الشاشة). */
class Tracker {
    private class T(var x: Float, var y: Float, var vx: Float, var vy: Float, var t: Long)
    private val tracks = ArrayList<T>()

    fun update(en: List<Det>, now: Long, W: Int): List<FloatArray> {
        val nOld = tracks.size
        val used = BooleanArray(nOld)
        val res = ArrayList<FloatArray>()
        for (d in en) {
            var bi = -1; var bd = W * 0.12f
            for (i in 0 until nOld) {
                if (used[i]) continue
                val t = tracks[i]
                val dist = hypot(d.cx - t.x, d.cy - t.y)
                if (dist < bd) { bd = dist; bi = i }
            }
            if (bi >= 0) {
                used[bi] = true
                val t = tracks[bi]
                val dt = (now - t.t) / 1000f
                if (dt > 0.001f) { t.vx = 0.5f * t.vx + 0.5f * (d.cx - t.x) / dt; t.vy = 0.5f * t.vy + 0.5f * (d.cy - t.y) / dt }
                t.x = d.cx; t.y = d.cy; t.t = now
                res.add(floatArrayOf(t.vx, t.vy))
            } else {
                tracks.add(T(d.cx, d.cy, 0f, 0f, now)); res.add(floatArrayOf(0f, 0f))
            }
        }
        tracks.removeAll { now - it.t > 600 }
        return res
    }
}

class Policy(private val cfg: Config) {
    var ready = false; private set
    var info = ""; private set
    private var itp: Interpreter? = null
    private val inBuf = ByteBuffer.allocateDirect(Obs.DIM * 4).order(ByteOrder.nativeOrder())
    private val out = Array(1) { FloatArray(Obs.ACT) }

    fun load(): Boolean {
        if (!cfg.policyFile.exists()) { info = "لا يوجد policy.tflite"; return false }
        return try {
            val t = Interpreter(cfg.policyFile, Interpreter.Options().setNumThreads(2))
            val i = t.getInputTensor(0).shape(); val o = t.getOutputTensor(0).shape()
            if (i.last() != Obs.DIM || o.last() != Obs.ACT) {
                info = "أبعاد غير مطابقة: مدخل ${i.joinToString("x")} (المطلوب ${Obs.DIM}) ومخرج ${o.joinToString("x")} (المطلوب ${Obs.ACT})"
                t.close(); false
            } else { itp = t; ready = true; info = "سياسة جاهزة"; true }
        } catch (e: Throwable) { info = "فشل: ${e.message}"; false }
    }

    fun act(obs: FloatArray): FloatArray {
        inBuf.rewind(); inBuf.asFloatBuffer().put(obs); inBuf.rewind()
        itp!!.run(inBuf, out)
        return out[0]
    }

    fun close() { try { itp?.close() } catch (_: Throwable) {}; ready = false }
}

/** يشغّل نموذج السياسة المدرَّب بدل القواعد اليدوية. */
class PolicyBrain(private val cfg: Config, private val policy: Policy) : Agent {
    private val tracker = Tracker()
    private val prev = FloatArray(5)
    private var lastThumb: IntArray? = null
    private var stillSince = 0L
    private var lastUi = 0L
    private var lastEnemyAt = 0L
    private var memRx = 0f
    private var memRy = 0f
    private var lastUnstick = 0L
    private val cd = HashMap<String, Long>()
    private val ctrl = mapOf(
        "joystick" to "joy", "btn_fire" to "fire", "btn_jump" to "jump",
        "btn_reload" to "reload", "btn_crouch" to "crouch", "btn_heal" to "heal"
    )

    private fun trig(name: String, now: Long, ms: Long) {
        if (now - (cd[name] ?: 0L) >= ms) { cd[name] = now; Bus.taps.add(Bus.px(name)) }
    }

    override fun step(dets: List<Det>, W: Int, H: Int, thumb: IntArray?) {
        val now = SystemClock.uptimeMillis()
        Bus.sw = W; Bus.sh = H
        val hu = cfg.human; Bus.human = hu
        if (cfg.calibrated) Bus.joyR = cfg.joyRf * H
        else {
            for (d in dets) {
                val k = ctrl[d.label] ?: continue
                val p = Bus.pos[k]!!
                p.x = p.x * 0.8f + (d.cx / W) * 0.2f; p.y = p.y * 0.8f + (d.cy / H) * 0.2f
                if (k == "joy") Bus.joyR = max(40f, d.box.width() * 0.45f)
            }
            if (Bus.joyR < 40f || Bus.joyR > 0.3f * H) Bus.joyR = 0.09f * H
        }
        val ui = dets.filter { it.label.startsWith("ui_tap") }.maxByOrNull { it.conf }
        if (ui != null) {
            if (now - lastUi > 1500) { lastUi = now; Bus.taps.add(android.graphics.PointF(ui.cx, ui.cy)) }
            Bus.idle(); Bus.status = "قائمة"; return
        }
        // كشف التعليق
        var moved = true
        val lt = lastThumb
        if (thumb != null && lt != null && lt.size == thumb.size) {
            var s = 0L
            for (i in thumb.indices) s += kotlin.math.abs(thumb[i] - lt[i])
            moved = s.toFloat() / thumb.size > 3f
        }
        lastThumb = thumb
        val wantsMove = kotlin.math.abs(prev[0]) + kotlin.math.abs(prev[1]) > 0.6f
        if (wantsMove && !moved) { if (stillSince == 0L) stillSince = now } else stillSince = 0L
        val stuck = stillSince != 0L && now - stillSince > 800

        val cx = W * cfg.crossX; val cy = H * cfg.crossY
        val enemies = dets.filter { it.label == "enemy" }
        val vel = tracker.update(enemies, now, W)
        if (enemies.isNotEmpty()) {
            val n = enemies.minByOrNull { hypot((it.cx - cx) / W, (it.cy - cy) / H) }!!
            lastEnemyAt = now; memRx = (n.cx - cx) / W; memRy = (n.cy - cy) / H
        }
        val memAge = if (lastEnemyAt == 0L) 1f else min(1f, (now - lastEnemyAt) / 5000f)
        val lowHp = dets.any { it.label == "low_health" }
        val obs = Obs.build(enemies.zip(vel), dets, W, H, cx, cy, lowHp, stuck, memAge, if (lastEnemyAt == 0L) 0f else memRx, if (lastEnemyAt == 0L) 0f else memRy, prev)
        val a = try { policy.act(obs) } catch (e: Throwable) { Log.e("FP", "policy: $e"); return }

        val jx = a[0].coerceIn(-1f, 1f); val jy = a[1].coerceIn(-1f, 1f)
        val noise = (Random.nextFloat() - 0.5f) * 2f * hu * 0.02f
        val ax = (a[2] + noise).coerceIn(-1f, 1f); val ay = a[3].coerceIn(-1f, 1f)
        val fire = a[4] > 0.5f
        Bus.joyX = jx; Bus.joyY = jy; Bus.fire = fire
        Bus.aimDx = ax * W * 0.2f
        Bus.aimDy = ay * W * 0.1f * (if (cfg.invertY) -1f else 1f)
        if (a[5] > 0.5f) trig("jump", now, 600)
        if (a[6] > 0.5f) trig("reload", now, 3000)
        if (a[7] > 0.5f) trig("heal", now, 3000)
        if (a[8] > 0.5f) trig("crouch", now, 500)
        // شبكة أمان: تعليق طويل
        if (stillSince != 0L && now - stillSince > 3000 && now - lastUnstick > 3000) {
            lastUnstick = now; stillSince = 0L
            Bus.aimDx = (if (Random.nextBoolean()) 1 else -1) * W * 0.2f; trig("jump", now, 0)
        }
        prev[0] = jx; prev[1] = jy; prev[2] = ax; prev[3] = ay; prev[4] = if (fire) 1f else 0f
        Bus.status = "سياسة | أعداء=${enemies.size}"
    }
}

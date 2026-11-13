package app.falconpilot

import android.graphics.PointF
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * عقل اللعب: يحوّل ما رآه النموذج إلى أوامر (حركة، تصويب، نار، قفز، علاج، تذخير).
 * الأولويات: قائمة/أزرار واجهة > قتال > علاج > الزون > جمع الغنائم > استكشاف.
 */
class Brain(private val cfg: Config) {
    private var strafeDir = 1
    private var nextStrafe = 0L
    private var nextJump = 0L
    private var nextIdleJump = 0L
    private var lastHeal = 0L
    private var lastReload = 0L
    private var lastUi = 0L
    private var firedMs = 0L
    private var lastStep = 0L
    private var yawDir = 1
    private var nextYaw = 0L
    private var turnRemaining = 0f
    private var lastThumb: IntArray? = null
    private var stillSince = 0L
    private var unstickUntil = 0L
    private var wantedMove = false
    private var hadTarget = false
    private var seenAt = 0L
    private var reactMs = 0L
    private var burstEnd = 0L
    private var pauseEnd = 0L

    private val ctrl = mapOf(
        "joystick" to "joy", "btn_fire" to "fire", "btn_jump" to "jump",
        "btn_reload" to "reload", "btn_crouch" to "crouch", "btn_heal" to "heal"
    )

    private fun tap(name: String) { Bus.taps.add(Bus.px(name)) }

    fun step(dets: List<Det>, W: Int, H: Int, thumb: IntArray?) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastStep == 0L) 100L else min(300L, now - lastStep)
        lastStep = now
        Bus.sw = W; Bus.sh = H

        // 1) تعلّم أماكن الأزرار تلقائياً من النموذج
        val hu = cfg.human
        Bus.human = hu
        if (cfg.calibrated) Bus.joyR = cfg.joyRf * H
        else for (d in dets) {
            val k = ctrl[d.label] ?: continue
            val p = Bus.pos[k]!!
            p.x = p.x * 0.8f + (d.cx / W) * 0.2f
            p.y = p.y * 0.8f + (d.cy / H) * 0.2f
            if (k == "joy") Bus.joyR = max(40f, d.box.width() * 0.45f)
        }
        if (!cfg.calibrated && (Bus.joyR < 40f || Bus.joyR > 0.3f * H)) Bus.joyR = 0.09f * H

        // 2) أزرار القوائم (ready / respawn / continue ...)
        val ui = dets.filter { it.label.startsWith("ui_tap") }.maxByOrNull { it.conf }
        if (ui != null) {
            if (now - lastUi > 1500) { lastUi = now; Bus.taps.add(PointF(ui.cx, ui.cy)) }
            Bus.idle(); wantedMove = false; Bus.status = "قائمة"; return
        }

        // 3) حركة الشاشة (كشف التعليق)
        var moved = true
        val lt = lastThumb
        if (thumb != null && lt != null && lt.size == thumb.size) {
            var s = 0L
            for (i in thumb.indices) s += abs(thumb[i] - lt[i])
            moved = s.toFloat() / thumb.size > 3f
        }
        lastThumb = thumb
        if (wantedMove && !moved) { if (stillSince == 0L) stillSince = now } else stillSince = 0L
        if (stillSince != 0L && now - stillSince > 1500 && now > unstickUntil) {
            turnRemaining = (if (Random.nextBoolean()) 1 else -1) * W * (0.18f + Random.nextFloat() * 0.2f)
            unstickUntil = now + 2500; stillSince = 0L; tap("jump")
        }

        val cx = W * cfg.crossX
        val cy = H * cfg.crossY
        val gain = cfg.aimGain
        val yMul = if (cfg.invertY) -1f else 1f
        val enemies = dets.filter { it.label == "enemy" }
        val lowHp = dets.any { it.label == "low_health" }
        val target = enemies.minByOrNull { hypot(it.cx - cx, it.cy - cy) - it.box.height() * 0.5f }

        var jx = 0f; var jy = 0f; var fire = false; var dx = 0f; var dy = 0f
        var mode: String

        if (target != null) {
            mode = "قتال"
            val ax = target.cx
            val ay = target.box.top + target.box.height() * 0.22f
            if (!hadTarget) { seenAt = now; reactMs = ((120 + Random.nextFloat() * 220) * hu).toLong() }
            val noise = (Random.nextFloat() - 0.5f) * 2f * target.box.width() * 0.12f * hu
            val ex = ax - cx + noise; val ey = ay - cy
            val maxStep = W * 0.2f
            dx = (ex * gain).coerceIn(-maxStep, maxStep)
            dy = (ey * gain * 0.6f * yMul).coerceIn(-maxStep / 2, maxStep / 2)
            val aligned = abs(ex) < max(target.box.width() * 0.5f, 30f) && abs(ey) < max(target.box.height() * 0.45f, 40f)
            if (aligned && now - seenAt >= reactMs) {
                if (now < pauseEnd) fire = false
                else if (now > burstEnd) {
                    pauseEnd = now + (Random.nextLong(60, 260) * hu).toLong()
                    burstEnd = pauseEnd + Random.nextLong(500, 1400)
                    fire = false
                } else fire = true
            }
            val rel = target.box.height() / H
            if (now > nextStrafe) { strafeDir = -strafeDir; nextStrafe = now + Random.nextLong(450, 1300) }
            jx = strafeDir * 0.9f
            jy = when {
                lowHp -> -0.9f
                rel > 0.45f -> -0.5f
                rel < 0.12f -> 0.8f
                else -> 0.25f
            }
            if (now > nextJump) { if (Random.nextFloat() < 0.5f) tap("jump"); nextJump = now + Random.nextLong(1800, 3500) }
            if (lowHp && now - lastHeal > 4000 && rel < 0.2f) { tap("heal"); lastHeal = now }
            hadTarget = true
        } else {
            hadTarget = false
            mode = "استكشاف"
            if (lowHp && now - lastHeal > 4000) { tap("heal"); lastHeal = now }
            val safe = dets.filter { it.label == "safe_zone" }.maxByOrNull { it.box.width() * it.box.height() }
            val danger = dets.filter { it.label == "danger_zone" }.maxByOrNull { it.box.width() * it.box.height() }
            val loot = dets.filter {
                it.label == "medkit" || (!lowHp && (it.label == "ammo" || it.label == "weapon"))
            }.minByOrNull { hypot(it.cx - cx, it.cy - cy) }

            var gx: Float? = null
            var close = false
            when {
                danger != null && safe == null -> { gx = 2 * cx - danger.cx; mode = "هروب من الزون" }
                safe != null -> { gx = safe.cx; mode = "نحو الزون" }
                loot != null -> { gx = loot.cx; close = loot.box.height() > 0.3f * H; mode = "جمع" }
            }
            if (gx != null) {
                val ex = gx - cx
                dx = (ex * gain * 0.8f).coerceIn(-W * 0.2f, W * 0.2f)
                jy = if (close) 0.3f else if (abs(ex) < W * 0.25f) 1f else 0.4f
            } else {
                jy = 1f
                if (now > nextYaw) { yawDir = -yawDir; nextYaw = now + Random.nextLong(1500, 3500) }
                dx = yawDir * W * 0.00025f * dt
            }
            if (turnRemaining != 0f) {
                val s = turnRemaining.coerceIn(-W * 0.08f, W * 0.08f)
                dx += s; turnRemaining -= s
                if (abs(turnRemaining) < 5f) turnRemaining = 0f
            }
            if (now < unstickUntil) { jy = 1f; mode = "فك تعليق" }
            if (now > nextIdleJump) { if (Random.nextFloat() < 0.4f) tap("jump"); nextIdleJump = now + Random.nextLong(5000, 9000) }
            if (firedMs > 3500 && now - lastReload > 6000) { tap("reload"); firedMs = 0; lastReload = now }
        }
        if (fire) firedMs += dt

        wantedMove = jy > 0.5f && target == null
        Bus.joyX = jx; Bus.joyY = jy; Bus.fire = fire; Bus.aimDx = dx; Bus.aimDy = dy
        Bus.status = "$mode | أعداء=${enemies.size}"
    }
}

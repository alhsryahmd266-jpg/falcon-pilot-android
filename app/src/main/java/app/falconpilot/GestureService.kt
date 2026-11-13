package app.falconpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * ينفّذ اللمس الفعلي: عصا الحركة (لمسة مستمرة)، زر النار (لمسة مستمرة)،
 * سحب التصويب، والنقرات — كلها في إيماءة واحدة متعددة اللمسات كل ~100ms.
 */
class GestureService : AccessibilityService() {
    private class Hold { var s: StrokeDescription? = null; var x = 0f; var y = 0f; var flip = false }

    private val main = Handler(Looper.getMainLooper())
    private val joy = Hold()
    private val fire = Hold()
    private var running = false
    private var inFlight = false

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; running = false; return super.onUnbind(intent) }

    fun startPump() {
        if (running) return
        running = true; joy.s = null; fire.s = null
        main.post { pump() }
    }

    fun stopPump() {
        running = false
        Bus.idle()
        // فكّ اللمسات المعلّقة
        val gb = GestureDescription.Builder()
        var n = 0
        hold(joy, 0f, 0f, 0f, 0f, false, 20)?.let { gb.addStroke(it); n++ }
        hold(fire, 0f, 0f, 0f, 0f, false, 20)?.let { gb.addStroke(it); n++ }
        if (n > 0) try { dispatchGesture(gb.build(), null, null) } catch (_: Throwable) {}
    }

    private fun hold(h: Hold, sx: Float, sy: Float, tx: Float, ty: Float, on: Boolean, d: Long): StrokeDescription? {
        val p = Path()
        if (!on) {
            val cur = h.s ?: return null
            p.moveTo(h.x, h.y)
            h.s = null
            return cur.continueStroke(p, 0, 20, false)
        }
        val cur = h.s
        val startX = if (cur == null) sx else h.x
        val startY = if (cur == null) sy else h.y
        var ex = tx; var ey = ty
        if (ex == startX && ey == startY) { ey += if (h.flip) 0.5f else -0.5f; h.flip = !h.flip }
        p.moveTo(startX, startY); p.lineTo(ex, ey)
        val st = if (cur == null) StrokeDescription(p, 0, d, true) else cur.continueStroke(p, 0, d, true)
        h.s = st; h.x = ex; h.y = ey
        return st
    }

    private fun pump() {
        if (!running) return
        val w = Bus.sw.toFloat(); val hgt = Bus.sh.toFloat()
        val d = Bus.tickMs
        val gb = GestureDescription.Builder()
        var n = 0
        try {
            val jx = Bus.joyX; val jy = Bus.joyY
            val jc = Bus.px("joy"); val r = Bus.joyR
            val jOn = jx != 0f || jy != 0f
            hold(joy, jc.x, jc.y, (jc.x + jx * r).coerceIn(1f, w - 1), (jc.y - jy * r).coerceIn(1f, hgt - 1), jOn, d)?.let { gb.addStroke(it); n++ }

            val fp = Bus.px("fire")
            hold(fire, fp.x, fp.y, fp.x, fp.y, Bus.fire, d)?.let { gb.addStroke(it); n++ }

            val adx = Bus.aimDx; val ady = Bus.aimDy
            Bus.aimDx = 0f; Bus.aimDy = 0f
            if (adx != 0f || ady != 0f) {
                val a = Bus.px("aim")
                val p = Path()
                p.moveTo(a.x, a.y)
                p.lineTo((a.x + adx).coerceIn(1f, w - 1), (a.y + ady).coerceIn(1f, hgt - 1))
                gb.addStroke(StrokeDescription(p, 0, minOf(d, 80L), false)); n++
            }
            var t = 0
            while (t < 3) {
                val tp = Bus.taps.poll() ?: break
                val p = Path(); p.moveTo(tp.x, tp.y)
                gb.addStroke(StrokeDescription(p, 0, 45, false)); n++; t++
            }
        } catch (e: Throwable) { n = 0 }

        if (n == 0) { main.postDelayed({ pump() }, 25); return }
        val ok = try {
            dispatchGesture(gb.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { main.post { pump() } }
                override fun onCancelled(g: GestureDescription?) { joy.s = null; fire.s = null; main.postDelayed({ pump() }, 20) }
            }, main)
        } catch (e: Throwable) { false }
        if (!ok) { joy.s = null; fire.s = null; main.postDelayed({ pump() }, 50) }
    }

    companion object { @Volatile var instance: GestureService? = null }
}

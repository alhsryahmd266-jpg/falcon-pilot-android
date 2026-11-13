package app.falconpilot

import android.graphics.PointF
import android.graphics.RectF
import java.util.concurrent.ConcurrentLinkedQueue

data class Det(val label: String, val conf: Float, val box: RectF) {
    val cx get() = box.centerX()
    val cy get() = box.centerY()
}

/** حالة مشتركة بين خيط الرؤية/القرار وخدمة اللمس. */
object Bus {
    class P(x: Float, y: Float) {
        @Volatile var x = x
        @Volatile var y = y
    }

    // مواقع عناصر التحكم (نسبة من الشاشة). تتحدث تلقائياً عند اكتشاف الأزرار بالنموذج.
    val pos = hashMapOf(
        "joy" to P(0.13f, 0.70f), "fire" to P(0.87f, 0.62f), "jump" to P(0.94f, 0.42f),
        "reload" to P(0.80f, 0.86f), "crouch" to P(0.94f, 0.62f), "heal" to P(0.70f, 0.88f),
        "aim" to P(0.64f, 0.40f)
    )
    @Volatile var active = false
    @Volatile var sw = 2400
    @Volatile var sh = 1080
    @Volatile var joyX = 0f
    @Volatile var joyY = 0f
    @Volatile var joyR = 100f
    @Volatile var fire = false
    @Volatile var aimDx = 0f
    @Volatile var aimDy = 0f
    @Volatile var tickMs = 100L
    @Volatile var human = 0.6f
    @Volatile var dets: List<Det> = emptyList()
    @Volatile var status = ""
    /** المساعد: true طالما إصبعك على زر النار (الطبقة الشفافة). */
    @Volatile var assistHold = false
    @Volatile var firePressed = false
    /** آخر لقطة شاشة (تصلح داخل خطوة القرار فقط). */
    @Volatile var frame: android.graphics.Bitmap? = null
    val taps = ConcurrentLinkedQueue<PointF>()

    fun px(name: String): PointF {
        val p = pos[name]!!
        return PointF(p.x * sw, p.y * sh)
    }

    fun idle() { joyX = 0f; joyY = 0f; fire = false; aimDx = 0f; aimDy = 0f }
}

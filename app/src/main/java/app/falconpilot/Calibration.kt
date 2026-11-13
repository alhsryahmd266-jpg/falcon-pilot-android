package app.falconpilot

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** طبقة معايرة: علامات قابلة للسحب فوق أزرار اللعبة الحقيقية. */
class CalibView(ctx: Context, val pts: LinkedHashMap<String, PointF>) : View(ctx) {
    var joyR = 100f
    private val colors = mapOf(
        "joy" to 0xFF00E5FF.toInt(), "fire" to 0xFFFF1744.toInt(), "jump" to 0xFF76FF03.toInt(),
        "reload" to 0xFFFFEA00.toInt(), "crouch" to 0xFFE040FB.toInt(), "heal" to 0xFF69F0AE.toInt(), "aim" to 0xFFFF9100.toInt()
    )
    private val names = mapOf(
        "joy" to "عصا الحركة", "fire" to "نار", "jump" to "قفز", "reload" to "تذخير",
        "crouch" to "انحناء", "heal" to "علاج", "aim" to "بداية التصويب"
    )
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private var drag: String? = null

    override fun onDraw(c: Canvas) {
        c.drawColor(0x55000000)
        for ((k, p) in pts) {
            val col = colors[k] ?: Color.WHITE
            val r = if (k == "joy") joyR else 55f
            ring.color = col; fill.color = (col and 0x00FFFFFF) or 0x44000000
            c.drawCircle(p.x, p.y, r, fill); c.drawCircle(p.x, p.y, r, ring)
            c.drawLine(p.x - 14, p.y, p.x + 14, p.y, ring); c.drawLine(p.x, p.y - 14, p.x, p.y + 14, ring)
            c.drawText(names[k] ?: k, p.x, p.y - r - 10, txt)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                drag = pts.minByOrNull { hypot(it.value.x - e.x, it.value.y - e.y) }
                    ?.takeIf { hypot(it.value.x - e.x, it.value.y - e.y) < 200 }?.key
            }
            MotionEvent.ACTION_MOVE -> drag?.let { pts[it]?.set(e.x, e.y); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = null
        }
        return true
    }
}

package app.falconpilot

import android.content.Context
import java.io.File

class Config(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    val modelFile get() = File(ctx.filesDir, "model.tflite")
    val labelsFile get() = File(ctx.filesDir, "labels.txt")

    var conf: Float
        get() = p.getFloat("conf", 0.40f)
        set(v) { p.edit().putFloat("conf", v).apply() }
    var aimGain: Float
        get() = p.getFloat("aimGain", 0.60f)
        set(v) { p.edit().putFloat("aimGain", v).apply() }
    var invertY: Boolean
        get() = p.getBoolean("invertY", false)
        set(v) { p.edit().putBoolean("invertY", v).apply() }
    var debug: Boolean
        get() = p.getBoolean("debug", false)
        set(v) { p.edit().putBoolean("debug", v).apply() }
    var crossX: Float
        get() = p.getFloat("crossX", 0.5f)
        set(v) { p.edit().putFloat("crossX", v).apply() }
    var crossY: Float
        get() = p.getFloat("crossY", 0.5f)
        set(v) { p.edit().putFloat("crossY", v).apply() }

    var human: Float
        get() = p.getFloat("human", 0.6f)
        set(v) { p.edit().putFloat("human", v).apply() }
    var joyRf: Float
        get() = p.getFloat("joyRf", 0.09f)
        set(v) { p.edit().putFloat("joyRf", v).apply() }
    val calibrated: Boolean get() = p.contains("layout")

    fun saveLayout(m: Map<String, android.graphics.PointF>, w: Int, h: Int) {
        p.edit().putString("layout", m.entries.joinToString(";") { "${it.key}:${it.value.x / w},${it.value.y / h}" }).apply()
    }
    fun loadLayout(): Map<String, Pair<Float, Float>> {
        val r = HashMap<String, Pair<Float, Float>>()
        val s = p.getString("layout", null) ?: return r
        for (e in s.split(";")) {
            val kv = e.split(":"); if (kv.size != 2) continue
            val xy = kv[1].split(","); if (xy.size != 2) continue
            r[kv[0]] = Pair(xy[0].toFloat(), xy[1].toFloat())
        }
        return r
    }
    fun resetLayout() { p.edit().remove("layout").apply() }

    fun labels(): List<String> {
        if (labelsFile.exists()) {
            val l = labelsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            if (l.isNotEmpty()) return l
        }
        return DEFAULT_LABELS
    }

    companion object {
        val DEFAULT_LABELS = listOf(
            "enemy", "ally", "medkit", "ammo", "weapon", "safe_zone", "danger_zone", "low_health",
            "joystick", "btn_fire", "btn_jump", "btn_reload", "btn_crouch", "btn_heal", "ui_tap"
        )
    }
}

package app.falconpilot

import android.content.Context
import java.io.File

class Config(private val ctx: Context) {
    private val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    val modelFile get() = File(ctx.filesDir, "model.tflite")
    val policyFile get() = File(ctx.filesDir, "policy.tflite")
    val labelsFile get() = File(ctx.filesDir, "labels.txt")
    val bundledFile get() = File(ctx.filesDir, "bundled_yolo.tflite")
    /** هل يوجد نموذج جاهز (مستورد أو مدمج داخل التطبيق)؟ */
    val hasModel: Boolean get() = modelFile.exists() || hasBundled
    val hasBundled: Boolean get() = try { ctx.assets.open("yolo_default.tflite").close(); true } catch (_: Throwable) { false }
    val usingBundled: Boolean get() = !modelFile.exists() && hasBundled
    /** يجهّز الملف المدمج (ينسخه من assets مرة واحدة) ويعيد النموذج الفعّال. */
    fun activeModel(): File? {
        if (modelFile.exists()) return modelFile
        if (!hasBundled) return null
        val sz = ctx.assets.openFd("yolo_default.tflite").use { it.length }
        if (!bundledFile.exists() || bundledFile.length() != sz)
            ctx.assets.open("yolo_default.tflite").use { i -> bundledFile.outputStream().use { o -> i.copyTo(o) } }
        return bundledFile
    }

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

    var fireBase: String?
        get() = p.getString("fire_base", null)
        set(v) { p.edit().putString("fire_base", v).apply() }
    var firePressed: String?
        get() = p.getString("fire_pressed", null)
        set(v) { p.edit().putString("fire_pressed", v).apply() }
    var fireFlat: Boolean   // الزر لا يتغير شكله عند الضغط
        get() = p.getBoolean("fire_flat", false)
        set(v) { p.edit().putBoolean("fire_flat", v).apply() }
    fun clearFire() { p.edit().remove("fire_base").remove("fire_pressed").remove("fire_flat").apply() }
    var gate: Boolean
        get() = p.getBoolean("gate", false)
        set(v) { p.edit().putBoolean("gate", v).apply() }
    var leadMs: Int
        get() = p.getInt("lead", 180)
        set(v) { p.edit().putInt("lead", v).apply() }
    var fireThr: Float
        get() = p.getFloat("fthr", 0.07f)
        set(v) { p.edit().putFloat("fthr", v).apply() }
    /** حماية من الانهيار الأصلي: true = كان فيه تحميل/تشغيل أول للنموذج ولم ينتهِ. */
    var loading: Boolean
        get() = p.getBoolean("loading", false)
        set(v) { p.edit().putBoolean("loading", v).commit() }
    var crashCount: Int
        get() = p.getInt("cc", 0)
        set(v) { p.edit().putInt("cc", v).commit() }
    var useGpu: Boolean
        get() = p.getBoolean("gpu2", false)
        set(v) { p.edit().putBoolean("gpu2", v).apply() }
    var usePolicy: Boolean
        get() = p.getBoolean("usePolicy", true)
        set(v) { p.edit().putBoolean("usePolicy", v).apply() }
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
        if (usingBundled) return COCO_LABELS
        if (labelsFile.exists()) {
            val l = labelsFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { Roles.norm(it) }
            if (l.isNotEmpty()) return l
        }
        return DEFAULT_LABELS
    }

    companion object {
        /** نموذج COCO الافتراضي: "person" يُعامل كعدو. استورد نموذجك الخاص ليتعرف على أعداء لعبتك. */
        val COCO_LABELS = listOf("enemy","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair","couch","potted plant","bed","dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven","toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear","hair drier","toothbrush")
        val DEFAULT_LABELS = listOf(
            "enemy", "ally", "medkit", "ammo", "weapon", "safe_zone", "danger_zone", "low_health",
            "joystick", "btn_fire", "btn_jump", "btn_reload", "btn_crouch", "btn_heal", "ui_tap"
        )
    }
}

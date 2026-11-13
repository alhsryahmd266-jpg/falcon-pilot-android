package app.falconpilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var cfg: Config
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Config(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(28)) }
        val root = ScrollView(this).apply { setBackgroundColor(0xFF0B0F14.toInt()); addView(col) }
        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun button(s: String, color: Int = 0xFF1E88E5.toInt(), f: () -> Unit) = Button(this).apply {
            text = s; setTextColor(Color.WHITE); setBackgroundColor(color); setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        col.addView(text("🦅 Falcon Pilot", 28f, 0xFF00E5FF.toInt(), true))
        col.addView(text("لاعب آلي أوفلاين: يرى الشاشة، يكتشف الأعداء والأزرار بنموذجك، ويتحكم باللمس.", 14f, 0xFFB0BEC5.toInt()))
        status = text("", 14f, Color.WHITE)
        col.addView(status)
        col.addView(button("1) السماح بالعرض فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("2) تفعيل خدمة التحكم (إمكانية الوصول)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("ابحث عن Falcon Pilot وفعّلها")
        })
        col.addView(button("3) استيراد النموذج (.tflite)") { pick(11) })
        col.addView(button("4) استيراد أسماء الفئات (labels.txt)") { pick(12) })
        col.addView(button("5) استيراد نموذج السياسة (policy.tflite)", 0xFF7B1FA2.toInt()) { pick(13) })

        fun slider(title: String, min: Int, max: Int, cur: Int, on: (Int) -> Unit) {
            val t = text("$title: $cur", 14f, Color.WHITE)
            val s = SeekBar(this).apply {
                this.max = max - min; progress = cur - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) { t.text = "$title: ${p + min}"; on(p + min) }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            col.addView(t); col.addView(s)
        }
        slider("حساسية التصويب %", 10, 200, (cfg.aimGain * 100).toInt()) { cfg.aimGain = it / 100f }
        slider("مستوى المحاكاة البشرية %", 0, 100, (cfg.human * 100).toInt()) { cfg.human = it / 100f }
        slider("حد الثقة %", 10, 90, (cfg.conf * 100).toInt()) { cfg.conf = it / 100f }
        slider("موضع المؤشر X %", 30, 70, (cfg.crossX * 100).toInt()) { cfg.crossX = it / 100f }
        slider("موضع المؤشر Y %", 30, 70, (cfg.crossY * 100).toInt()) { cfg.crossY = it / 100f }
        fun sw(title: String, cur: Boolean, on: (Boolean) -> Unit) = Switch(this).apply {
            text = title; setTextColor(Color.WHITE); isChecked = cur; setOnCheckedChangeListener { _, c -> on(c) }
            setPadding(0, dp(10), 0, dp(10))
        }
        col.addView(sw("استخدام نموذج السياسة إن وُجد", cfg.usePolicy) { cfg.usePolicy = it })
        col.addView(sw("تسريع GPU (تجريبي، لو التطبيق بيقفل خليه مقفول)", cfg.useGpu) { cfg.useGpu = it })
        col.addView(sw("عكس اتجاه التصويب العمودي", cfg.invertY) { cfg.invertY = it })
        col.addView(sw("عرض مربعات الاكتشاف (تشخيص)", cfg.debug) { cfg.debug = it })
        col.addView(text("🎯 معايرة الأزرار: شغّل التطبيق ثم ادخل مباراة (تدريب) واضغط الزر البنفسجي 🎯 فوق اللعبة ← «مسح تلقائي» ← عدّل العلامات ← حفظ.", 13f, 0xFFFFD54F.toInt()))
        col.addView(text("🔫 مساعد الضرب: اضغط الزر الأخضر 🔫 (أول مرة بيكتشف الأزرار تلقائياً 6 ثوانٍ، سيبه داخل المباراة). اتحرك واضرب نار بإيدك، والتطبيق يصوّب تلقائياً (رأس ثم صدر ثم أي جزء) على أي مجسم بشري أو عربية أو طائرة، ويتجاهل من فوقه رقم 1 إلى 4. بيتعلم من اللي بتضرب عليه.", 13f, 0xFF80CBC4.toInt()))
        col.addView(button("إعادة ضبط المعايرة", 0xFF6D4C41.toInt()) { cfg.resetLayout(); toast("تمت الإعادة"); refresh() })
        col.addView(button("مسح سجل الأعطال", 0xFF455A64.toInt()) { Trace.clear(this); refresh() })
        col.addView(button("▶  تشغيل (يظهر الزر العائم)", 0xFF2E7D32.toInt()) { start() })
        col.addView(text("داخل اللعبة: اضغط الزر العائم للتشغيل/الإيقاف، اسحبه لتحريكه، ضغطة مطوّلة لإغلاق التطبيق.", 12f, 0xFF90A4AE.toInt()))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val ov = Settings.canDrawOverlays(this)
        val ac = GestureService.instance != null
        val md = cfg.hasModel
        status.text = "${if (ov) "✅" else "❌"} العرض فوق التطبيقات\n" +
            "${if (ac) "✅" else "❌"} خدمة التحكم\n" +
            "${if (cfg.calibrated) "✅" else "⚠️"} معايرة الأزرار\n" +
            "${if (cfg.policyFile.exists()) "🧠" else "➖"} نموذج السياسة\n" +
            "${if (md) "✅" else "❌"} النموذج ${if (cfg.usingBundled) "(YOLO11 مدمج: person=عدو)" else if (md) "(مستورد ${cfg.modelFile.length() / 1024} KB)" else ""}\n" +
            "فئات: ${cfg.labels().take(6).joinToString(",")}…\n" + Trace.read(this)
    }

    private fun pick(code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(i, code)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) { toast("امنح إذن العرض فوق التطبيقات"); return }
        if (GestureService.instance == null) { toast("فعّل خدمة التحكم من إعدادات الوصول"); return }
        if (!cfg.hasModel) { toast("لا يوجد نموذج"); return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 21)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        when (req) {
            11, 12, 13 -> {
                val dst: File = when (req) { 11 -> cfg.modelFile; 12 -> cfg.labelsFile; else -> cfg.policyFile }
                val uri = data.data ?: return
                Thread {
                    try {
                        contentResolver.openInputStream(uri)!!.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                        runOnUiThread { toast("تم الاستيراد"); refresh() }
                    } catch (e: Exception) { runOnUiThread { toast("فشل: ${e.message}") } }
                }.start()
            }
            21 -> {
                val i = Intent(this, AutoPlayService::class.java).putExtra("code", res).putExtra("data", data)
                startForegroundService(i)
                moveTaskToBack(true)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

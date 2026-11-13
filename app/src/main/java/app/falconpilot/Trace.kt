package app.falconpilot

import android.app.Application
import android.content.Context
import java.io.File

/** سجل مراحل بسيط (يبقى حتى لو انهار التطبيق انهياراً أصلياً) + سجل الأعطال. */
object Trace {
    fun log(ctx: Context, m: String) {
        try {
            val f = File(ctx.filesDir, "trace.txt")
            val lines = if (f.exists()) f.readLines().takeLast(30) else emptyList()
            f.writeText((lines + "${android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())} $m").joinToString("\n") + "\n")
        } catch (_: Throwable) {}
    }
    fun read(ctx: Context): String {
        val sb = StringBuilder()
        try { val c = File(ctx.filesDir, "crash.txt"); if (c.exists()) sb.append("⚠ آخر عطل:\n").append(c.readText().take(700)).append("\n") } catch (_: Throwable) {}
        try { val t = File(ctx.filesDir, "trace.txt"); if (t.exists()) sb.append("آخر المراحل:\n").append(t.readLines().takeLast(8).joinToString("\n")) } catch (_: Throwable) {}
        return sb.toString()
    }
    fun clear(ctx: Context) { try { File(ctx.filesDir, "crash.txt").delete(); File(ctx.filesDir, "trace.txt").delete() } catch (_: Throwable) {} }
}

class FalconApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText("${t.name}: $e\n" + e.stackTrace.take(14).joinToString("\n")) } catch (_: Throwable) {}
            old?.uncaughtException(t, e)
        }
    }
}

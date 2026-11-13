package app.falconpilot

/**
 * يحوّل أسماء فئات داتاسيتك (Roboflow) إلى أدوار يفهمها التطبيق.
 * - صيغة صريحة في labels.txt:  اسم_فئتك=الدور     مثال:  Zombie=enemy
 * - أو تخمين تلقائي من الاسم (enemy/opponent/bot/person ... ).
 */
object Roles {
    private fun strip(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    // الترتيب مهم: الأكثر تحديداً أولاً
    private val rules = listOf(
        "low_health" to listOf("lowhealth", "lowhp", "healthlow"),
        "danger_zone" to listOf("danger", "redzone", "storm", "bluezone", "poison"),
        "safe_zone" to listOf("safezone", "safe", "whitecircle", "nextzone"),
        "ally" to listOf("ally", "friend", "teammate", "friendly", "team"),
        "medkit" to listOf("medkit", "firstaid", "bandage", "medic", "healthpack"),
        "ammo" to listOf("ammo", "ammunition", "magazine", "bullets"),
        "weapon" to listOf("weapon", "gun", "rifle", "pistol", "shotgun"),
        "enemy" to listOf("enemy", "enemies", "opponent", "hostile", "foe", "bot", "zombie", "person", "player", "character", "human")
    )
    private val exact = setOf("enemy", "ally", "medkit", "ammo", "weapon", "safezone", "dangerzone", "lowhealth",
        "joystick", "btnfire", "btnjump", "btnreload", "btncrouch", "btnheal", "uitap")

    fun norm(raw: String): String {
        val t = raw.trim()
        if (t.contains("=")) return t.substringAfter("=").trim()
        val n = strip(t)
        if (n in exact) return t
        if (t.startsWith("btn_") || t.startsWith("ui_tap") || t == "joystick") return t
        for ((role, keys) in rules) if (keys.any { n.contains(it) }) return role
        return t
    }
}

package hu.fokusz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class FocusSubject(val id: String, val name: String, val packages: Set<String>, val nextStep: String = "")

internal data class FocusNote(
    val subjectId: String,
    val subjectName: String,
    val finishedAt: Long,
    val seconds: Int,
    val text: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("subjectId", subjectId)
        .put("subjectName", subjectName)
        .put("finishedAt", finishedAt)
        .put("seconds", seconds)
        .put("text", text)

    companion object {
        fun fromJson(note: JSONObject): FocusNote? = runCatching {
            FocusNote(note.getString("subjectId"), note.getString("subjectName"),
                note.getLong("finishedAt"), note.getInt("seconds"), note.getString("text"))
        }.getOrNull()
    }
}

internal object FocusSubjects {
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_focus_subjects_v1", Context.MODE_PRIVATE)
    val general = FocusSubject("", "Általános tanulás", emptySet())

    fun all(c: Context): List<FocusSubject> {
        val stored = runCatching { JSONArray(prefs(c).getString("subjects", "[]")) }
            .getOrElse { JSONArray() }
        return (0 until stored.length()).mapNotNull { index ->
            val row = stored.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id")
            val name = row.optString("name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val apps = row.optJSONArray("packages") ?: JSONArray()
            val packages = (0 until apps.length()).mapNotNull { appIndex ->
                apps.optString(appIndex).takeIf { it.isNotBlank() }
            }.toSet()
            FocusSubject(id, name, packages, row.optString("nextStep"))
        }
    }

    fun selected(c: Context): String = prefs(c).getString("selected", "").orEmpty()

    fun select(c: Context, id: String) {
        prefs(c).edit().putString("selected", id).apply()
    }

    @Synchronized fun save(c: Context, id: String?, name: String, packages: Set<String>): String? {
        val title = name.trim()
        if (title.length !in 1..60) return null
        val all = all(c)
        if (all.any { it.id != id && it.name.equals(title, ignoreCase = true) } ||
            general.name.equals(title, ignoreCase = true)) return null
        val key = id ?: UUID.randomUUID().toString()
        val subject = FocusSubject(key, title, packages, all.firstOrNull { it.id == key }?.nextStep.orEmpty())
        val next = if (id == null) all + subject else all.map { if (it.id == id) subject else it }
        if (next.none { it.id == key }) return null
        val editor = prefs(c).edit().putString("subjects", encode(next))
        return if (editor.commit()) key else null
    }

    private fun encode(subjects: List<FocusSubject>): String = JSONArray(subjects.map {
        JSONObject().put("id", it.id).put("name", it.name)
            .put("packages", JSONArray(it.packages.toList())).put("nextStep", it.nextStep)
    }).toString()

    fun nextStep(c: Context, id: String): String = if (id.isEmpty()) {
        prefs(c).getString("generalNext", "").orEmpty()
    } else all(c).firstOrNull { it.id == id }?.nextStep.orEmpty()

    @Synchronized fun saveNextStep(c: Context, id: String, value: String) {
        val step = value.take(200)
        val edit = prefs(c).edit()
        if (id.isEmpty()) edit.putString("generalNext", step)
        else edit.putString("subjects", encode(all(c).map { if (it.id == id) it.copy(nextStep = step) else it }))
        edit.apply()
    }
}

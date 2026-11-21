package hu.fokusz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

internal data class StudyTopic(val title: String, val due: LocalDate, val completed: Boolean = false)

internal data class ExamPlan(val id: String, val title: String, val examDate: LocalDate, val topics: List<StudyTopic>) {
    fun current(today: LocalDate): ExamPlan {
        if (topics.none { !it.completed && it.due < today }) return this
        val dates = scheduleDates(topics.count { !it.completed }, today, examDate).iterator()
        return copy(topics = topics.map { if (it.completed) it else it.copy(due = dates.next()) })
    }
}

private fun scheduleDates(count: Int, start: LocalDate, examDate: LocalDate): List<LocalDate> {
    val days = ChronoUnit.DAYS.between(start, examDate).coerceAtLeast(1)
    return List(count) { index -> start.plusDays(index * days / count) }
}

internal data class StudyQuestion(val prompt: String, val answer: String) {
    fun accepts(value: String): Boolean = normalize(answer) == normalize(value)

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)
        .trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}

internal data class QuizDeck(val id: String, val title: String, val minutes: Int, val questions: List<StudyQuestion>)
internal data class QuestTemplate(val id: String, val title: String, val minutes: Int)

internal object StudyToolsStore {
    const val MAX_ITEMS = 20
    const val QUIZ_SIZE = 5
    const val QUIZ_PASS = 4
    private const val EXAMS = "exams"
    private const val QUIZZES = "quizzes"
    private const val TEMPLATES = "templates"
    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_study_tools_v1", Context.MODE_PRIVATE)

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(this::optJSONObject)
    private fun rows(c: Context, key: String): List<JSONObject> =
        runCatching { JSONArray(prefs(c).getString(key, "[]")).objects() }.getOrDefault(emptyList())

    private fun exams(c: Context): List<ExamPlan> = rows(c, EXAMS).mapNotNull { row ->
        runCatching {
            ExamPlan(row.getString("id"), row.getString("title"), LocalDate.parse(row.getString("examDate")),
                row.getJSONArray("topics").objects().map { topic ->
                    StudyTopic(topic.getString("title"), LocalDate.parse(topic.getString("due")), topic.optBoolean("completed"))
                })
        }.getOrNull()
    }.sortedBy { it.examDate }

    @Synchronized fun exams(c: Context, today: LocalDate): List<ExamPlan> = exams(c).map { it.current(today) }

    @Synchronized fun addExam(c: Context, title: String, date: LocalDate, topics: List<String>, today: LocalDate): Boolean {
        if (title.isBlank() || date < today || topics.isEmpty()) return false
        val dates = scheduleDates(topics.size, today, date)
        return saveExam(c, ExamPlan(UUID.randomUUID().toString(), title, date,
            topics.zip(dates) { topic, due -> StudyTopic(topic, due) }))
    }

    @Synchronized fun toggleTopic(c: Context, id: String, index: Int, today: LocalDate): Boolean {
        val plan = exams(c, today).firstOrNull { it.id == id } ?: return false
        val topic = plan.topics.getOrNull(index) ?: return false
        val topics = plan.topics.toMutableList()
        topics[index] = topic.copy(completed = !topic.completed)
        return saveExam(c, plan.copy(topics = topics).current(today))
    }

    private fun saveExam(c: Context, plan: ExamPlan): Boolean = put(c, EXAMS,
        JSONObject().put("id", plan.id).put("title", plan.title).put("examDate", plan.examDate.toString())
            .put("topics", JSONArray(plan.topics.map { topic ->
                JSONObject().put("title", topic.title).put("due", topic.due.toString()).put("completed", topic.completed)
            })))

    @Synchronized fun quizzes(c: Context): List<QuizDeck> = rows(c, QUIZZES).mapNotNull { row ->
        runCatching {
            QuizDeck(row.getString("id"), row.getString("title"), row.getInt("minutes"),
                row.getJSONArray("questions").objects().map { question ->
                    StudyQuestion(question.getString("prompt"), question.getString("answer"))
                })
        }.getOrNull()
    }

    @Synchronized fun saveQuiz(c: Context, id: String?, title: String, minutes: Int, questions: List<StudyQuestion>): Boolean {
        if (title.isBlank() || minutes !in 1..10 || questions.size !in QUIZ_SIZE..100) return false
        return put(c, QUIZZES, JSONObject().put("id", id ?: UUID.randomUUID().toString())
            .put("title", title).put("minutes", minutes)
            .put("questions", JSONArray(questions.map { JSONObject().put("prompt", it.prompt).put("answer", it.answer) })))
    }

    @Synchronized fun templates(c: Context): List<QuestTemplate> = rows(c, TEMPLATES).mapNotNull { row ->
        runCatching { QuestTemplate(row.getString("id"), row.getString("title"), row.getInt("minutes")) }.getOrNull()
    }

    @Synchronized fun saveTemplate(c: Context, id: String?, title: String, minutes: Int): Boolean {
        if (title.length !in 3..80 || minutes !in 5..60) return false
        return put(c, TEMPLATES, JSONObject().put("id", id ?: UUID.randomUUID().toString())
            .put("title", title).put("minutes", minutes))
    }

    @Synchronized fun deleteExam(c: Context, id: String): Boolean = delete(c, EXAMS, id)
    @Synchronized fun deleteQuiz(c: Context, id: String): Boolean = delete(c, QUIZZES, id)
    @Synchronized fun deleteTemplate(c: Context, id: String): Boolean = delete(c, TEMPLATES, id)

    private fun put(c: Context, key: String, entry: JSONObject): Boolean {
        val existing = rows(c, key).toMutableList()
        val index = existing.indexOfFirst { it.optString("id") == entry.optString("id") }
        if (index >= 0) existing[index] = entry
        else if (existing.size < MAX_ITEMS) existing.add(entry)
        else return false
        return prefs(c).edit().putString(key, JSONArray(existing).toString()).commit()
    }

    private fun delete(c: Context, key: String, id: String): Boolean =
        prefs(c).edit().putString(key, JSONArray(rows(c, key).filterNot { it.optString("id") == id }).toString()).commit()
}

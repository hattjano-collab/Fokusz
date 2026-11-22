package hu.fokusz

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

object QuizStore {
    data class Attempt(
        val id: String,
        val subject: String,
        val completedAt: LocalDateTime,
        val correct: Int,
        val total: Int,
        val rewardSeconds: Int,
        val rows: List<QuizAnalysis.Row>,
        internal val fingerprints: Set<String>,
        internal val attemptId: String?,
    ) {
        val wrong: Int get() = rows.count { it.grade == QuizAnalysis.Grade.WRONG }
        val blank: Int get() = rows.count { it.grade == QuizAnalysis.Grade.BLANK }
    }

    enum class ClaimStatus { SAVED, DUPLICATE, INVALID, LIMIT_REACHED, STORAGE_ERROR }

    data class Claim(val status: ClaimStatus, val entry: Attempt? = null)
    data class PracticeItem(val id: String, val subject: String, val number: Int, val text: String, val reviewed: Boolean)
    data class Exam(val id: String, val subject: String, val date: LocalDate)
    data class Stats(val attempts: Int, val correct: Int, val total: Int, val rewardSeconds: Int, val needsReview: Int)

    private fun prefs(c: Context) = c.getSharedPreferences("fokusz_quiz_v1", Context.MODE_PRIVATE)

    private fun readAttempts(raw: String): List<Attempt>? = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val savedRows = item.optJSONArray("rows") ?: JSONArray()
            val rows = List(savedRows.length()) { rowIndex ->
                val row = savedRows.getJSONObject(rowIndex)
                QuizAnalysis.Row(row.getInt("number"),
                    QuizAnalysis.Grade.valueOf(row.getString("grade")), row.optString("text"))
            }
            val aliases = item.optJSONArray("fingerprints") ?: JSONArray()
            Attempt(item.getString("id"), item.getString("subject"),
                LocalDateTime.parse(item.getString("completedAt")), item.getInt("correct"),
                item.getInt("total"), item.getInt("reward"), rows,
                List(aliases.length(), aliases::getString).toSet(),
                item.optString("attemptId").ifBlank { null })
        }
    }.getOrNull()

    private fun encode(entry: Attempt): JSONObject {
        val rows = JSONArray()
        for (row in entry.rows) {
            rows.put(JSONObject().put("number", row.number).put("grade", row.grade.name).put("text", row.text))
        }
        return JSONObject()
            .put("id", entry.id).put("subject", entry.subject).put("completedAt", entry.completedAt.toString())
            .put("correct", entry.correct).put("total", entry.total).put("reward", entry.rewardSeconds)
            .put("rows", rows).put("fingerprints", JSONArray(entry.fingerprints.sorted()))
            .put("attemptId", entry.attemptId ?: "")
    }

    fun history(c: Context): List<Attempt> =
        readAttempts(Store.quizAttempts(c)).orEmpty().sortedByDescending { it.completedAt }

    @Synchronized fun claim(c: Context, result: QuizAnalysis.Result, subject: String = ""): Claim {
        val total = result.total
        val key = result.fingerprints.firstOrNull()
        if (!result.canClaim || total == null || key == null) return Claim(ClaimStatus.INVALID)
        val item = Attempt(key, subject.trim().take(80).ifBlank { "Kvíz" }, LocalDateTime.now(),
            result.correct, total, result.rewardSeconds, result.rows, result.fingerprints, result.attemptId)
        repeat(3) {
            val previous = Store.quizAttempts(c)
            val stored = readAttempts(previous) ?: return Claim(ClaimStatus.STORAGE_ERROR)
            val duplicate = stored.firstOrNull { sameAttempt(it, item) }
            if (duplicate != null) return Claim(ClaimStatus.DUPLICATE, duplicate)
            val next = JSONArray(previous).put(encode(item)).toString()
            when (Store.commitQuizAttempt(c, previous, next, item.rewardSeconds)) {
                Store.QuizWrite.SAVED -> return Claim(ClaimStatus.SAVED, item)
                Store.QuizWrite.LIMIT_REACHED -> return Claim(ClaimStatus.LIMIT_REACHED)
                Store.QuizWrite.STORAGE_ERROR -> return Claim(ClaimStatus.STORAGE_ERROR)
                Store.QuizWrite.HISTORY_CHANGED -> Unit
            }
        }
        return Claim(ClaimStatus.STORAGE_ERROR)
    }

    private fun sameAttempt(old: Attempt, new: Attempt): Boolean {
        if (old.id == new.id || old.fingerprints.any { it in new.fingerprints }) return true
        if (old.attemptId != null && new.attemptId != null) return false
        val sameScore = old.correct == new.correct && old.total == new.total
        if (sameScore && (old.rows.isEmpty() || new.rows.isEmpty())) return true
        val olderRows = old.rows.associateBy { it.number }
        val common = new.rows.mapNotNull { row -> olderRows[row.number]?.let { it to row } }
        if (common.isEmpty()) return false
        var matches = 0
        for ((first, second) in common) {
            if (first.grade != second.grade && first.grade != QuizAnalysis.Grade.UNCLEAR &&
                second.grade != QuizAnalysis.Grade.UNCLEAR) return false
            val firstText = QuizAnalysis.rowContent(first)
            val secondText = QuizAnalysis.rowContent(second)
            if (firstText.isEmpty() || secondText.isEmpty()) continue
            if (!QuizAnalysis.compatibleContent(firstText, secondText)) return false
            matches++
        }
        val smallerReport = minOf(old.rows.size, new.rows.size)
        return matches >= minOf(2, smallerReport) ||
            (sameScore && common.size == smallerReport &&
                (old.rows.all { QuizAnalysis.rowContent(it).isEmpty() } ||
                    new.rows.all { QuizAnalysis.rowContent(it).isEmpty() }))
    }

    private fun practiceItems(c: Context, attempts: List<Attempt>): List<PracticeItem> {
        val reviewed = prefs(c).getStringSet("reviewed", emptySet()).orEmpty()
        return attempts.flatMap { entry ->
            entry.rows.filter { it.grade == QuizAnalysis.Grade.WRONG || it.grade == QuizAnalysis.Grade.BLANK }
                .map { row ->
                    val id = "${entry.id}:${row.number}"
                    PracticeItem(id, entry.subject, row.number, row.text, id in reviewed)
                }
        }
    }

    fun practice(c: Context): List<PracticeItem> =
        practiceItems(c, history(c)).sortedBy { it.reviewed }

    @Synchronized fun setReviewed(c: Context, id: String, reviewed: Boolean): Boolean {
        if (practice(c).none { it.id == id }) return false
        val current = prefs(c).getStringSet("reviewed", emptySet()).orEmpty()
        val next = if (reviewed) current + id else current - id
        return prefs(c).edit().putStringSet("reviewed", next).commit()
    }

    fun stats(c: Context): Stats {
        val all = history(c)
        fun sum(selector: (Attempt) -> Int): Int =
            all.sumOf { selector(it).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return Stats(all.size, sum { it.correct }, sum { it.total }, sum { it.rewardSeconds },
            practiceItems(c, all).count { !it.reviewed })
    }

    fun exams(c: Context): List<Exam> {
        val raw = prefs(c).getString("exams", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                Exam(item.getString("id"), item.getString("subject"), LocalDate.parse(item.getString("date")))
            }.getOrNull()
        }.sortedBy { it.date }
    }

    private fun writeExams(c: Context, entries: List<Exam>): Boolean {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().put("id", entry.id).put("subject", entry.subject).put("date", entry.date.toString()))
        }
        return prefs(c).edit().putString("exams", array.toString()).commit()
    }

    @Synchronized fun saveExam(c: Context, subject: String, date: LocalDate): Boolean {
        val name = subject.trim()
        if (name.isEmpty() || name.length > 80) return false
        val current = exams(c)
        if (current.any { it.date == date && it.subject.equals(name, ignoreCase = true) }) return true
        return writeExams(c, current + Exam(UUID.randomUUID().toString(), name, date))
    }

    @Synchronized fun removeExam(c: Context, id: String): Boolean {
        val current = exams(c)
        val next = current.filterNot { it.id == id }
        return next.size != current.size && writeExams(c, next)
    }
}

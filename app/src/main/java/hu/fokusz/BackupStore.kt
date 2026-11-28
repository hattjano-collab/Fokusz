package hu.fokusz

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

internal object BackupStore {
    private const val FORMAT = "hu.fokusz.backup"
    private const val VERSION = 1
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val INVALID = "Ez nem érvényes Fókusz-mentés."
    private val fingerprint = Regex("[0-9a-f]{64}")
    private val practiceId = Regex("[0-9a-f]{64}:[1-9][0-9]{0,9}")
    private val chessReceipt = Regex("chess_reward_[cl]:[^\\s]{1,350}")
    private val studyReceipt = Regex("study_claim_[a-zA-Z0-9_-]{1,100}")
    private val bossReceipt = Regex("user_quest_reward_personal_boss_[0-9]{4}-[0-9]{1,2}")
    private val username = Regex("[A-Za-z0-9_-]{2,25}")
    private val packageName = Regex("[A-Za-z0-9_.]{1,255}")
    private val attemptIdPattern = Regex("(?:[a-z0-9_-]{6,100})?")

    internal data class Snapshot(
        val id: String,
        val issuedAt: Long,
        val wallet: Store.BackupWalletData,
        val preferences: Map<String, Map<String, Any?>>
    )

    private class Field(
        val fromPreference: (Any) -> Any = { it },
        val fromDocument: (Any) -> Any
    )

    private fun fields(): Map<String, Map<String, Field>> {
        val packages = (FocusPrefs.socialChoices.map { it.second } + Store.BLOCKED).distinct()
        val options = packages.associate { "blocked_$it" to bool() }.toMutableMap()
        options["maximum_seconds"] = integer(1800..21600)
        options["auto_restart"] = bool()
        options["appearance.theme_id"] = Field { value ->
            text(value, 16).also { require(it in setOf("rebel", "studio", "night", "play", "sage")) }
        }
        options["theme"] = Field { value ->
            text(value, 20).also { require(it in setOf("menta", "lila", "arany")) }
        }
        val limits = packages.map(SocialUsage::appKey).distinct().associate { "limit_$it" to integer(0..1440) }
        val accounts = ChessPlatform.entries.associate { platform ->
            platform.key to Field { value -> text(value, 25).also { require(username.matches(it)) } }
        }
        return mapOf(
            "fokusz_options_v1" to options,
            "fokusz_social_use_v1" to limits,
            SocialUnlockStore.PREFS to mapOf(
                "selected" to Field({ JSONArray(it as Set<*>) }) { value ->
                    val packages = SocialUnlockStore.supportedPackages
                    stringSet(value as JSONArray, packages.size, packages::contains)
                },
                "jokes" to bool(),
                "baselineMinutes" to integer(0..1440)
            ),
            "fokusz_daily_starters_v1" to mapOf("choice0" to integer(0..19), "choice1" to integer(0..19)),
            "fokusz_chess_v2" to accounts,
            "fokusz_calendar_v1" to mapOf("events" to jsonRows(10_000, ::validateCalendarEvent)),
            "fokusz_focus_subjects_v1" to mapOf(
                "subjects" to jsonRows(10_000, ::validateSubject),
                "selected" to Field { value -> (value as String).also { if (it.isNotEmpty()) uuid(it) } },
                "generalNext" to Field { value -> (value as String).also { require(it.length <= 200) } }
            ),
            "fokusz_focus_v1" to mapOf("pastNotes" to jsonRows(100_000, ::validateFocusNote)),
            "fokusz_user_quests" to mapOf("quests" to jsonRows(10_000, ::validateUserQuest)),
            "fokusz_boss_v1" to mapOf("goals" to jsonRows(3, ::validateBossGoal)),
            "fokusz_study_tools_v1" to mapOf(
                "exams" to jsonRows(StudyToolsStore.MAX_ITEMS, ::validateStudyPlan),
                "quizzes" to jsonRows(StudyToolsStore.MAX_ITEMS, ::validateQuizDeck),
                "templates" to jsonRows(StudyToolsStore.MAX_ITEMS, ::validateTemplate)
            ),
            "fokusz_quiz_v1" to mapOf(
                "exams" to jsonRows(10_000, ::validateExam),
                "reviewed" to Field({ JSONArray(it as Set<*>) }) { value ->
                    stringSet(value as JSONArray, 100_000, practiceId::matches)
                }
            )
        )
    }

    private fun appField(file: String, key: String): Field? {
        val prefix = when (file) {
            "fokusz_options_v1" -> if (key.startsWith("focus_rule_")) "focus_rule_" else "blocked_"
            "fokusz_social_use_v1" -> "limit_"
            else -> return null
        }
        if (!key.startsWith(prefix) || !Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+").matches(key.removePrefix(prefix))) return null
        return when (prefix) {
            "blocked_" -> bool()
            "limit_" -> integer(0..1440)
            else -> Field { value -> text(value, 8).also { require(it == "ALLOW" || it == "BLOCK") } }
        }
    }

    fun write(c: Context, uri: Uri) {
        ChessVerifier.accounts(c)
        val state = Store.walletBackup(c)
        val sections = JSONObject()
        for ((file, fields) in fields()) {
            val preferences = c.getSharedPreferences(file, Context.MODE_PRIVATE).all
            val section = JSONObject()
            for ((key, field) in fields) {
                preferences[key]?.let { section.put(key, field.fromPreference(it)) }
            }
            for ((key, value) in preferences) {
                if (key !in fields && value != null) appField(file, key)?.let { section.put(key, it.fromPreference(value)) }
            }
            sections.put(file, section)
        }
        val wallet = JSONObject()
            .put("date", state.wallet.day.toString())
            .put("seconds", state.wallet.balance)
            .put("spendSeconds", state.wallet.debits)
            .put("installation", state.wallet.installation)
            .put("chessReceipts", JSONArray(state.chessReceipts))
            .put("studyReceipts", JSONArray(state.studyReceipts))
            .put("bossReceipts", JSONArray(state.bossReceipts))
            .put("quizAttempts", state.quizAttempts)
        val document = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("id", UUID.randomUUID().toString())
            .put("issuedAt", System.currentTimeMillis())
            .put("wallet", wallet)
            .put("preferences", sections)
        val bytes = document.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "A mentés meghaladja a 8 MB-os mérethatárt." }
        checkNotNull(c.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
    }

    fun read(c: Context, uri: Uri): Snapshot {
        val data = checkNotNull(c.contentResolver.openInputStream(uri)).buffered().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "A mentés meghaladja a 8 MB-os mérethatárt." }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
        return decode(data)
    }

    private fun decode(data: String): Snapshot {
        try {
            val parser = JSONTokener(data.removePrefix("\uFEFF"))
            val document = parser.nextValue() as JSONObject
            require(parser.nextClean() == '\u0000' && document.getString("format") == FORMAT &&
                number(document.get("version")) == VERSION.toLong())
            val id = uuid(document.get("id") as String)
            val issuedAt = number(document.get("issuedAt"))
            require(issuedAt > 0L && issuedAt <= System.currentTimeMillis() + 300_000L)
            val wallet = document.getJSONObject("wallet")
            val walletData = Store.BackupWallet(
                date(wallet.get("date")),
                number(wallet.get("seconds")).also { require(it in 0L..Store.MAX_SAFE_SECONDS.toLong()) }.toInt(),
                number(wallet.get("spendSeconds")).also { require(it >= 0L) },
                uuid(wallet.get("installation") as String)
            )
            val chess = stringSet(wallet.getJSONArray("chessReceipts"), 200_000, chessReceipt::matches)
            val study = stringSet(wallet.getJSONArray("studyReceipts"), 10_000, studyReceipt::matches)
            val boss = stringSet(wallet.optJSONArray("bossReceipts") ?: JSONArray(), 10_000, bossReceipt::matches)
            val quiz = wallet.getJSONArray("quizAttempts").also {
                validateRows(it, 100_000, ::validateAttempt)
            }
            val sections = document.getJSONObject("preferences")
            val knownFields = fields().filterKeys { it != SocialUnlockStore.PREFS || sections.has(it) }
            val settings = knownFields.mapValues { (file, fields) ->
                val section = if (sections.has(file)) sections.getJSONObject(file) else JSONObject()
                val dynamic = section.keys().asSequence().filter { it !in fields }
                    .mapNotNull { key -> appField(file, key)?.let { key to it } }.toMap()
                (fields + dynamic).mapValues { (key, field) ->
                    if (section.has(key)) field.fromDocument(section.get(key)) else null
                }
            }
            val choices = settings.getValue("fokusz_daily_starters_v1")
            require(choices.values.all { it == null } ||
                (choices.values.none { it == null } && choices.values.toSet().size == 2))
            return Snapshot(id, issuedAt, Store.BackupWalletData(walletData, chess, study, quiz, boss), settings)
        } catch (exception: Exception) {
            throw IllegalArgumentException(INVALID, exception)
        } catch (error: StackOverflowError) {
            throw IllegalArgumentException(INVALID, error)
        }
    }

    fun restore(c: Context, snapshot: Snapshot): Boolean {
        val edits = snapshot.preferences.map { (file, values) ->
            val preferences = c.getSharedPreferences(file, Context.MODE_PRIVATE)
            val previous = preferences.all.filterKeys { it in values }
            PreferenceChange(preferences, previous, values)
        }
        val applied = mutableListOf<PreferenceChange>()
        val restoreBalance = try {
            for (change in edits) {
                applied.add(change)
                check(writeFields(change.preferences, change.values)) { "A beállításokat nem sikerült menteni." }
            }
            Store.restoreWalletBackup(c, snapshot.wallet, snapshot.id, snapshot.issuedAt)
        } catch (e: Exception) {
            for (change in applied.asReversed()) {
                val rollback = change.values.keys.associateWith { change.previous[it] }
                writeFields(change.preferences, rollback)
            }
            throw e
        }
        QuestStore.applyConfiguredIfIdle(c)
        ProgressStore.updateWidget(c)
        return restoreBalance
    }

    private data class PreferenceChange(
        val preferences: SharedPreferences,
        val previous: Map<String, *>,
        val values: Map<String, Any?>
    )

    private fun writeFields(preferences: SharedPreferences, values: Map<String, Any?>): Boolean {
        val editor = preferences.edit()
        for ((key, value) in values) {
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Ismeretlen beállítástípus.")
            }
        }
        return editor.commit()
    }

    private fun integer(range: IntRange) = Field { value ->
        number(value).also { require(it in range.first.toLong()..range.last.toLong()) }.toInt()
    }

    private fun bool() = Field { value -> value as Boolean }

    private fun validateBossGoal(row: JSONObject): String {
        val key = text(row.get("key"), 80)
        require(key.matches(Regex("[a-z0-9_-]{1,80}")))
        require(text(row.get("title"), 80).isNotBlank() && number(row.get("target")) in 1L..100_000L)
        val unit = BossUnit.valueOf(row.getString("unit"))
        val session = number(row.get("session"))
        require(if (unit == BossUnit.MINUTES) session in 1L..1440L else session == 0L)
        return key
    }

    private fun validateUserQuest(row: JSONObject): String {
        val id = uuid(row.getString("id"))
        require(text(row.get("title"), 80).trim().length >= 3)
        val seconds = number(row.get("seconds"))
        require(seconds in 60L..86400L && number(row.get("reward")) in 0L..seconds)
        return id
    }

    private fun jsonRows(maxCount: Int, validate: (JSONObject) -> String) =
        Field({ JSONArray(it as String) }) { value ->
            (value as JSONArray).also { validateRows(it, maxCount, validate) }.toString()
        }

    private fun validateRows(data: JSONArray, maxCount: Int, validate: (JSONObject) -> String) {
        require(data.length() <= maxCount)
        val ids = hashSetOf<String>()
        for (index in 0 until data.length()) require(ids.add(validate(data.getJSONObject(index))))
    }

    private fun validateCalendarEvent(row: JSONObject): String {
        val id = number(row.get("id")).also { require(it > 0) }
        text(row.get("title"), 90)
        dateTime(row.get("time"))
        integer(5..600).fromDocument(row.get("minutes"))
        if (row.has("weekly")) bool().fromDocument(row.get("weekly"))
        return id.toString()
    }

    private fun validateSubject(row: JSONObject): String {
        text(row.get("name"), 60)
        stringSet(row.getJSONArray("packages"), 10_000, packageName::matches)
        require((row.get("nextStep") as String).length <= 200)
        return uuid(row.get("id") as String)
    }

    private fun validateFocusNote(row: JSONObject): String {
        val subject = row.get("subjectId") as String
        if (subject.isNotEmpty()) uuid(subject)
        text(row.get("subjectName"), 60)
        val finishedAt = number(row.get("finishedAt")).also { require(it > 0L) }
        integer(900..7200).fromDocument(row.get("seconds"))
        text(row.get("text"), 2000)
        return "$subject:$finishedAt"
    }

    private fun validateStudyPlan(row: JSONObject): String {
        text(row.get("title"), 1000)
        date(row.get("examDate"))
        val topics = row.getJSONArray("topics")
        require(topics.length() in 1..1000)
        for (index in 0 until topics.length()) {
            val topic = topics.getJSONObject(index)
            text(topic.get("title"), 1000)
            date(topic.get("due"))
            bool().fromDocument(topic.get("completed"))
        }
        return uuid(row.get("id") as String)
    }

    private fun validateQuizDeck(row: JSONObject): String {
        text(row.get("title"), 1000)
        integer(1..10).fromDocument(row.get("minutes"))
        val questions = row.getJSONArray("questions")
        require(questions.length() in StudyToolsStore.QUIZ_SIZE..100)
        for (index in 0 until questions.length()) {
            val question = questions.getJSONObject(index)
            text(question.get("prompt"), 10_000)
            text(question.get("answer"), 10_000)
        }
        return uuid(row.get("id") as String)
    }

    private fun validateTemplate(row: JSONObject): String {
        text(row.get("title"), 80)
        integer(5..60).fromDocument(row.get("minutes"))
        return uuid(row.get("id") as String)
    }

    private fun validateExam(row: JSONObject): String {
        text(row.get("subject"), 1000)
        date(row.get("date"))
        return (row.get("id") as String).also { require(validId(it)) }
    }

    private fun validateAttempt(row: JSONObject): String {
        val id = text(row.get("id"), 64)
        require(fingerprint.matches(id))
        text(row.get("subject"), 1000)
        dateTime(row.get("completedAt"))
        val total = number(row.get("total"))
        val correct = number(row.get("correct"))
        require(total in 1L..Int.MAX_VALUE.toLong() && correct in 0L..total && correct <= Int.MAX_VALUE / 30L)
        require(number(row.get("reward")) in 0L..correct * 30L)
        require(stringSet(row.getJSONArray("fingerprints"), 10_000, fingerprint::matches).isNotEmpty())
        if (row.has("attemptId")) {
            require(attemptIdPattern.matches(row.get("attemptId") as String))
        }
        val rows = row.getJSONArray("rows")
        require(rows.length().toLong() <= total)
        validateRows(rows, 100_000) { item ->
            val number = number(item.get("number")).also { require(it in 1L..total) }
            QuizAnalysis.Grade.valueOf(item.get("grade") as String)
            require((item.get("text") as String).length <= 1200)
            number.toString()
        }
        return id
    }

    private fun number(value: Any): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException(INVALID)
    }

    private fun text(value: Any, maxLength: Int): String =
        (value as String).also { require(it.isNotBlank() && it.length <= maxLength) }

    private fun date(value: Any): LocalDate =
        LocalDate.parse(value as String).also { require(it.year in 1900..2200) }

    private fun dateTime(value: Any): LocalDateTime =
        LocalDateTime.parse(value as String).also { require(it.year in 1900..2200) }

    private fun uuid(id: String): String =
        id.also { require(UUID.fromString(it).toString() == it) }

    private fun validId(id: String) = runCatching { uuid(id) }.isSuccess || fingerprint.matches(id)

    private fun stringSet(data: JSONArray, maxCount: Int, check: (String) -> Boolean): Set<String> {
        require(data.length() <= maxCount)
        val values = linkedSetOf<String>()
        for (index in 0 until data.length()) {
            val value = data.get(index) as String
            require(check(value) && values.add(value))
        }
        return values
    }
}

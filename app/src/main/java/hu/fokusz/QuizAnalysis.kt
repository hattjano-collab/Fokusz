package hu.fokusz

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object QuizAnalysis {
    const val PROMPT = """Javítsd ki a válaszaimat, majd az utolsó sorban írd ki, hány kérdésre válaszoltam helyesen az összesből.
Minden kérdéshez külön sort használj: „1. HELYES | a kérdés rövid szövege | a saját válaszom”; „2. HIBÁS | a kérdés | a saját válaszom | javítás”; „3. ÜRES | a kérdés”. Az első javításkor az első sorban adj meg egy egyedi „Próbaazonosító: …” értéket legalább hat betűvel vagy számjeggyel. Ugyanazokhoz a kérdésekhez és válaszokhoz használd újra az azonosítót, akkor is, ha csak átrendezed vagy átformázod a jelentést. Az utolsó sor legyen például: „Eredmény: 7/10”."""

    enum class Grade { CORRECT, WRONG, BLANK, UNCLEAR }

    data class Row(val number: Int, val grade: Grade, val text: String)

    data class Result internal constructor(
        val rows: List<Row>,
        val correct: Int,
        val total: Int?,
        val issues: List<String>,
        internal val fingerprints: Set<String>,
        internal val attemptId: String?,
    ) {
        val canClaim: Boolean get() = issues.isEmpty() && total != null
        val rewardSeconds: Int get() = if (canClaim) correct * 30 else 0
    }

    private data class RowEvidence(val row: Row, val grades: Set<Grade>)
    private data class Source(val rows: List<RowEvidence>, val scores: Set<Pair<Int, Int>>, val attemptIds: Set<String>)

    private val diacritics = Regex("\\p{M}+")
    private val inlineFormat = Regex("[*`]")
    private val space = Regex("\\s+")
    private val keycap = Regex("([0-9])\\uFE0F?\\u20E3")
    private val attempt = Regex("""^(?:probaazonosito|kitoltesazonosito|attempt[ -]?id)\s*[:=]\s*([a-z0-9_-]{6,100})\s*$""")
    private val labeledRow = Regex("""^(?:\|\s*)?(?:(?:kerdes|feladat|question|item|q)\s*#?\s*|#\s*)?(\d+)\s*(?:[.):|]|[-–—]\s+)(.*)$""")
    private val looseRow = Regex("""^(\d+)\s+(.+)$""")

    private val correctWords = """(?:helyes|jo(?: valasz)?|correct|right)"""
    private val wrongWords = """(?:hibas|helytelen|rossz(?: valasz)?|nem helyes|incorrect|wrong|not correct)"""
    private val blankWords = """(?:ures|kihagyva|nincs valasz|nem valaszoltal|blank|unanswered|skipped|no answer(?: provided)?|not answered)"""
    private val correctSymbols = "[✅✔✓☑]"
    private val wrongSymbols = "[❌✗✘✖❎]"
    private val blankSymbols = "[⬜◻□]"
    private val gradingWords = mapOf(
        Grade.CORRECT to correctWords,
        Grade.WRONG to wrongWords,
        Grade.BLANK to blankWords,
    )
    private val gradingSymbols = mapOf(
        Grade.CORRECT to Regex(correctSymbols),
        Grade.WRONG to Regex(wrongSymbols),
        Grade.BLANK to Regex(blankSymbols),
    )
    private val answerKey = Regex("""(?:helyes valasz|helyes megoldas|megoldokulcs|correct answer|right answer|answer key|solution)\s*[:=]""")
    private val rowHeader = Regex("""^(?:valaszod|a valaszod|your answer|valasz|answer|kerdes|question)\s*[:=]\s*""")
    private val statusHeader = Regex("""^(?:ertekeles|minosites|eredmeny|statusz|status|grade|result|verdict)\s*[:=]\s*""")
    private val summaryLabel = """(?:eredmeny|pontszam|osszesites|osszesen|helyes valaszok(?: szama)?|helyesen megvalaszolt kerdesek|score|result|total(?: correct)?|correct answers)"""
    private val labeledScore = Regex("""^$summaryLabel\s*[:=–-]?\s*(\d+)\s*(?:/|out of|of|a(?:z)?)\s*(\d+)(?:\s*-?bol)?(?:\s*(?:helyes(?: valasz)?|jo|pont|correct(?: answers?)?|points?))?(?:[.!]|\s*\(\d+(?:[.,]\d+)?\s*%\))?$""")
    private val ratioWithStatus = Regex("""^(\d+)\s*/\s*(\d+)\s+(?:helyes(?: valasz)?|jo|pont|correct(?: answers?)?|points?)(?:[.!]|\s*\(\d+(?:[.,]\d+)?\s*%\))?$""")
    private val bareRatio = Regex("""^(\d+)\s*/\s*(\d+)\s*\.?$""")
    private val summarySentence = listOf(
        Regex("""^(?:osszesen\s+)?(\d+)\s+(?:kerdesre\s+)?(?:valaszoltal helyesen|helyes valasz|helyes)\s+(?:az?\s+)?(?:osszes\s+)?(\d+)(?:\s+kerdes)?\s*-?bol[.!]?$"""),
        Regex("""^(?:az?\s+)?(?:osszes\s+)?(\d+)(?:\s+kerdes)?\s*-?bol\s+(\d+)\s+(?:kerdesre\s+)?(?:helyes(?: valasz)?|helyesen valaszoltal)[.!]?$"""),
        Regex("""^(?:you (?:got|answered)\s+)?(\d+)\s+(?:out of|of)\s+(\d+)\s+(?:(?:questions?|answers?)\s+)?(?:are\s+)?correct[.!]?$"""),
    )

    private fun normalize(text: String): String =
        Normalizer.normalize(inlineFormat.replace(text, ""), Normalizer.Form.NFD)
            .replace(diacritics, "").lowercase(Locale.ROOT).trim()

    private fun normalizedText(text: String): String = normalize(text).replace(space, " ")

    private fun summary(line: String, last: Boolean): Pair<Int, Int>? {
        val plain = line.replace(Regex("^[\\p{So}\\s]+"), "").trim(' ', '|', '✅', '✔', '✓')
        fun score(match: MatchResult, reverse: Boolean = false): Pair<Int, Int>? {
            val first = match.groupValues[1].toIntOrNull() ?: return null
            val second = match.groupValues[2].toIntOrNull() ?: return null
            return if (reverse) second to first else first to second
        }
        labeledScore.matchEntire(plain)?.let { return score(it) }
        ratioWithStatus.matchEntire(plain)?.let { return score(it) }
        for ((index, pattern) in summarySentence.withIndex()) {
            pattern.matchEntire(plain)?.let { return score(it, index == 1) }
        }
        return if (last) bareRatio.matchEntire(plain)?.let { score(it) } else null
    }

    private val statusCells = Regex("""\s*(?:\||\t|[–—]|\s-\s|;|→)\s*""")
    private val statusWords = gradingWords.mapValues { (_, words) ->
        Regex("""^\s*[.!:()\[\]]*\s*$words(?:\s+(?:valasz|answer))?\s*[.!:()\[\]]*\s*$""")
    }
    private val explainedStatus = gradingWords.mapValues { (_, words) ->
        Regex("""^$words[.!:]\s+.+$""")
    }

    private fun marks(text: String): Set<Grade> {
        val normalized = normalize(text)
        val gradedPart = answerKey.find(normalized)?.let { normalized.substring(0, it.range.first) } ?: normalized
        val result = mutableSetOf<Grade>()
        for ((grade, symbol) in gradingSymbols) if (symbol.containsMatchIn(gradedPart)) result.add(grade)
        val plain = gradingSymbols.values.fold(gradedPart) { value, regex -> regex.replace(value, "") }
        for (cell in statusCells.split(plain)) {
            val status = statusHeader.replaceFirst(cell.trim(), "")
            for ((grade, pattern) in statusWords) {
                if (pattern.matches(status) || explainedStatus.getValue(grade).matches(status)) result.add(grade)
            }
        }
        return result
    }

    private fun parseSource(text: String): Source {
        val lines = text.lineSequence()
            .map { it.trim().removePrefix("> ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
            .toList()
        val rows = mutableListOf<RowEvidence>()
        val scores = mutableSetOf<Pair<Int, Int>>()
        val ids = mutableSetOf<String>()
        var number: Int? = null
        val body = mutableListOf<String>()
        fun finishRow() {
            val rowNumber = number ?: return
            val grades = body.flatMap(::marks).toSet()
            rows.add(RowEvidence(Row(rowNumber, grades.singleOrNull() ?: Grade.UNCLEAR,
                body.joinToString("\n").trim().take(1200)), grades))
            number = null
            body.clear()
        }
        for ((index, original) in lines.withIndex()) {
            val line = inlineFormat.replace(keycap.replace(original, "$1."), "").trim()
            val simple = normalize(line)
            val identifier = attempt.matchEntire(simple)
            if (identifier != null) {
                finishRow()
                ids.add(identifier.groupValues[1])
                continue
            }
            val score = summary(simple, index == lines.lastIndex)
            if (score != null) {
                finishRow()
                scores.add(score)
                continue
            }
            val explicit = labeledRow.matchEntire(simple)
            val loose = looseRow.matchEntire(simple)
                ?.takeIf { marks(it.groupValues[2]).isNotEmpty() }
            val numbered = explicit ?: loose
            val indexNumber = numbered?.groupValues?.get(1)?.toIntOrNull()
            if (indexNumber != null) {
                finishRow()
                number = indexNumber
                val prefix = numbered.groups[2]?.range?.first ?: line.length
                body.add(line.drop(prefix).trim(' ', '|'))
            } else if (number != null) {
                body.add(line)
            }
        }
        finishRow()
        return Source(rows, scores, ids)
    }

    fun analyze(sources: List<String>): Result {
        val readableSources = sources.filter(String::isNotBlank)
        val reports = readableSources.map(::parseSource)
        val issues = mutableListOf<String>()
        val allScores = reports.flatMap { it.scores }.toSet()
        val ids = reports.flatMap { it.attemptIds }.toSet()
        if (ids.size > 1) issues.add("A részleteken különböző próbaazonosítók szerepelnek.")
        if (allScores.size > 1) issues.add("A részleteken eltérő összpontszámok szerepelnek.")
        val grouped = reports.flatMap { it.rows }.groupBy { it.row.number }.toSortedMap()
        val rows = grouped.map { (number, evidence) ->
            val grades = evidence.flatMap { it.grades }.toSet()
            if (grades.size > 1) issues.add("A(z) $number. kérdéshez ellentmondó javítás szerepel.")
            val texts = evidence.map { rowContent(it.row) }.filter(String::isNotBlank).distinct()
            val longest = texts.maxByOrNull(String::length)
            if (longest != null && texts.any { !compatibleContent(it, longest) }) {
                issues.add("A részletek a(z) $number. sorszámnál különböző kérdéseket vagy válaszokat mutatnak.")
            }
            if (number < 1) issues.add("A kérdésszámozásnak 1-től kell indulnia.")
            val grade = grades.singleOrNull() ?: Grade.UNCLEAR
            val best = evidence.maxWith(compareBy<RowEvidence>({ it.row.grade == grade }, { it.row.text.length }))
            best.row.copy(grade = grade)
        }
        val knownCorrect = rows.count { it.grade == Grade.CORRECT }
        val knownIncorrect = rows.count { it.grade == Grade.WRONG || it.grade == Grade.BLANK }
        val score = allScores.singleOrNull()
        val total = score?.second ?: rows.size.takeIf { size ->
            size > 0 && rows.withIndex().all { (index, row) -> row.number == index + 1 }
        }
        if (score == null && (total == null || knownCorrect + knownIncorrect != rows.size)) {
            issues.add("A pontszámhoz minden sorszámozott javítás vagy egy egyértelmű záró eredmény kell.")
        }
        val correct = score?.first ?: knownCorrect
        if (total != null && (total < 1 || correct !in 0..total || correct > Int.MAX_VALUE / 30 ||
                rows.any { it.number !in 1..total } || correct < knownCorrect || correct > total - knownIncorrect)) {
            issues.add("Az összpontszám nem egyezik a látható javításokkal vagy a kérdésszámmal.")
        }
        val attemptId = ids.singleOrNull()
        val fingerprints = mutableSetOf<String>()
        if (issues.isEmpty() && total != null) {
            val rowIdentity = rows.joinToString("\n") { "${it.number}:${it.grade}:${rowContent(it)}" }
            val contextualRows = rows.any { rowContent(it).isNotEmpty() }
            if (attemptId != null) fingerprints.add(hash("id:$attemptId"))
            if (contextualRows || attemptId == null) fingerprints.add(hash("report:$correct/$total\n$rowIdentity"))
            for ((index, report) in reports.withIndex()) {
                if (report.attemptIds.isNotEmpty() || report.rows.any { rowContent(it.row).isNotEmpty() }) {
                    fingerprints.add(hash("source:${normalizedText(readableSources[index])}"))
                }
            }
        }
        return Result(rows, correct, total, issues.distinct(), fingerprints, attemptId)
    }

    /** Ignores grading labels and formatting when comparing overlapping rows. */
    internal fun rowContent(row: Row): String {
        val plain = gradingSymbols.values.fold(normalize(row.text)) { text, regex -> regex.replace(text, "") }
        val answer = answerKey.find(plain)?.let { plain.substring(0, it.range.first) } ?: plain
        return answer.lineSequence().flatMap { statusCells.split(it).asSequence() }.map { part ->
            rowHeader.replaceFirst(part.trim(), "").trim()
        }.filter { part -> part.isNotBlank() && statusWords.values.none { it.matches(statusHeader.replaceFirst(part, "")) } }
            .joinToString(" ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    internal fun compatibleContent(first: String, second: String): Boolean {
        if (first == second) return true
        val shorter = minOf(first.length, second.length)
        if (shorter >= 8 && (first.contains(second) || second.contains(first))) return true
        val a = first.split(' ').toSet()
        val b = second.split(' ').toSet()
        val common = a.count { it in b }
        return common >= 3 && common * 5 >= maxOf(a.size, b.size) * 4
    }

    internal fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

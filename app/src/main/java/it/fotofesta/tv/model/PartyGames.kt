package it.fotofesta.tv.model

import java.text.Normalizer
import java.util.Calendar
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * Logica dei giochi e degli spettacoli dell'ondata 11, senza Android: chi non c'è, quiz scritti
 * dalla regia, scelte per "chi è da piccolo" e "indovina la canzone", le note della festa, le
 * differenze da trovare, il quadro della festa e le foto di "accadde oggi".
 */
object PartyGames {
    // -----------------------------------------------------------------------------------------
    // Chi non c'è
    // -----------------------------------------------------------------------------------------

    /** Invitati attesi divisi tra arrivati e mancanti, confrontando i nomi senza maiuscole e accenti. */
    data class Roll(val present: List<String>, val missing: List<String>)

    fun roll(expected: List<String>, seen: List<String>): Roll {
        val seenKeys = seen.map(::nameKey).filter(String::isNotEmpty)
        val (present, missing) = expected.partition { name ->
            val key = nameKey(name)
            val first = key.substringBefore(' ')
            // "Marco" dalla lista vale anche per "Marco R." dal telefono, e viceversa.
            seenKeys.any { it == key || it.substringBefore(' ') == first }
        }
        return Roll(present, missing)
    }

    private fun nameKey(name: String): String =
        Normalizer.normalize(name.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    // -----------------------------------------------------------------------------------------
    // Quiz scritti dalla regia: "Domanda? | risposta giusta | sbagliata | sbagliata"
    // -----------------------------------------------------------------------------------------

    data class Question(val text: String, val right: String, val wrong: List<String>)

    fun parseQuiz(text: String): List<Question> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('|').map { it.trim() }.filter(String::isNotEmpty)
        if (parts.size < 3) return@mapNotNull null
        Question(parts[0].take(160), parts[1].take(60), parts.drop(2).take(3).map { it.take(60) })
    }.take(MAX_QUESTIONS).toList()

    /** Risposte mescolate con l'indice di quella giusta. */
    fun shuffle(question: Question, random: Random = Random): Pair<List<String>, Int> {
        val choices = (listOf(question.right) + question.wrong).shuffled(random)
        return choices to choices.indexOf(question.right)
    }

    /** [correct] e altri nomi presi da [pool] (fino a quattro risposte), mescolati. */
    fun nameChoices(correct: String, pool: List<String>, random: Random = Random): Pair<List<String>, Int> {
        val others = pool.filter { !it.equals(correct, ignoreCase = true) }.distinctBy { it.lowercase() }.shuffled(random).take(3)
        val choices = (others + correct).shuffled(random)
        return choices to choices.indexOf(correct)
    }

    /** Titolo leggibile dal nome di un file musicale: "01 - Gloria (Remastered).mp3" → "Gloria". */
    fun songTitle(fileName: String): String {
        var name = fileName.substringAfterLast('/').substringBeforeLast('.')
        name = name.replace('_', ' ')
        name = name.replace(Regex("^\\d{1,3}\\s*[-.)]?\\s*"), "")
        name = name.replace(Regex("\\s*[(\\[][^)\\]]*(remaster|live|edit|version|mix|radio)[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE), "")
        return name.trim().ifEmpty { fileName }
    }

    // -----------------------------------------------------------------------------------------
    // La festa suona: una nota per foto, dalla luminosità e dal colore
    // -----------------------------------------------------------------------------------------

    data class Note(val frequency: Double, val millis: Int)

    /**
     * Scala pentatonica (suona sempre bene): la luminosità sceglie l'altezza, la saturazione la
     * durata. Due ottave a partire dal La 220 Hz.
     */
    fun notes(colors: List<Int>): List<Note> = colors.map { color ->
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val brightness = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        val saturation = (maxOf(r, g, b) - minOf(r, g, b)) / 255.0
        val step = (brightness * (PENTATONIC.size * 2 - 1)).toInt().coerceIn(0, PENTATONIC.size * 2 - 1)
        val semitones = PENTATONIC[step % PENTATONIC.size] + 12 * (step / PENTATONIC.size)
        Note(220.0 * 2.0.pow(semitones / 12.0), if (saturation > 0.35) 260 else 520)
    }

    private val PENTATONIC = intArrayOf(0, 2, 4, 7, 9)

    // -----------------------------------------------------------------------------------------
    // Trova le differenze
    // -----------------------------------------------------------------------------------------

    enum class DiffKind { DOT, STAR, MIRROR, TINT, HIDE }

    /** Differenza nella foto di destra: centro e raggio in frazioni della foto. */
    data class Difference(val x: Float, val y: Float, val radius: Float, val kind: DiffKind, val color: Int)

    /** [count] differenze lontane tra loro e dai bordi, uguali per lo stesso [seed]. */
    fun differences(seed: Long, count: Int = 5): List<Difference> {
        val random = Random(seed)
        val palette = intArrayOf(0xFFFF7A57.toInt(), 0xFFFF4F9A.toInt(), 0xFF8B6CFF.toInt(), 0xFFFFC145.toInt(), 0xFF4CD787.toInt())
        val result = ArrayList<Difference>()
        var attempts = 0
        while (result.size < count && attempts++ < 500) {
            val x = 0.12f + random.nextFloat() * 0.76f
            val y = 0.14f + random.nextFloat() * 0.72f
            val radius = 0.045f + random.nextFloat() * 0.03f
            if (result.any { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < 0.04f }) continue
            result += Difference(x, y, radius, DiffKind.entries[result.size % DiffKind.entries.size], palette[random.nextInt(palette.size)])
        }
        return result
    }

    // -----------------------------------------------------------------------------------------
    // L'opera d'arte della festa: rettangoli alla Mondrian con i colori delle foto
    // -----------------------------------------------------------------------------------------

    /** Rettangolo in frazioni della tela, con il colore di una foto. */
    data class Block(val left: Float, val top: Float, val right: Float, val bottom: Float, val color: Int)

    fun artwork(colors: List<Int>, seed: Long): List<Block> {
        if (colors.isEmpty()) return emptyList()
        val random = Random(seed)
        val blocks = ArrayList<Block>()
        var index = 0
        fun split(left: Float, top: Float, right: Float, bottom: Float, depth: Int) {
            val width = right - left
            val height = bottom - top
            val stop = depth >= 5 || (depth >= 2 && random.nextFloat() < 0.25f) || max(width, height) < 0.12f
            if (stop) {
                blocks += Block(left, top, right, bottom, colors[index++ % colors.size])
                return
            }
            val ratio = 0.3f + random.nextFloat() * 0.4f
            if (width >= height) {
                val middle = left + width * ratio
                split(left, top, middle, bottom, depth + 1)
                split(middle, top, right, bottom, depth + 1)
            } else {
                val middle = top + height * ratio
                split(left, top, right, middle, depth + 1)
                split(left, middle, right, bottom, depth + 1)
            }
        }
        split(0f, 0f, 1f, 1f, 0)
        return blocks
    }

    // -----------------------------------------------------------------------------------------
    // Accadde oggi
    // -----------------------------------------------------------------------------------------

    /** La foto è stata scattata oggi (stesso giorno e mese) in un anno passato. */
    fun sameDayPastYear(takenMillis: Long, nowMillis: Long): Boolean {
        val taken = Calendar.getInstance().apply { timeInMillis = takenMillis }
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return taken.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
            taken.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH) &&
            taken.get(Calendar.YEAR) < now.get(Calendar.YEAR)
    }

    /** Anni passati da una foto di "accadde oggi". */
    fun yearsAgo(takenMillis: Long, nowMillis: Long): Int {
        val taken = Calendar.getInstance().apply { timeInMillis = takenMillis }
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return now.get(Calendar.YEAR) - taken.get(Calendar.YEAR)
    }

    const val MAX_QUESTIONS = 30
}

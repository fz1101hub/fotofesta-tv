package it.fotofesta.tv.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Ogni traduzione deve avere le stesse chiavi e gli stessi segnaposto dell'inglese (predefinito) e
 * le forme plurali che la lingua usa.
 */
class TranslationsTest {
    private val placeholder = Regex("""%\d+\$[sd]""")

    /** Forme plurali richieste da ciascuna lingua (CLDR, come le controlla Android Lint). */
    private val requiredQuantities = mapOf(
        "values" to setOf("one", "other"),
        "values-it" to setOf("one", "many", "other"),
        "values-fr" to setOf("one", "many", "other"),
        "values-es" to setOf("one", "many", "other"),
        "values-de" to setOf("one", "other"),
    )

    private val translations = requiredQuantities.keys - "values"

    @Test
    fun `translations match default resources`() {
        val english = load("values")
        translations.forEach { folder ->
            val translated = load(folder)
            assertEquals("Chiavi diverse tra values e $folder", english.keys, translated.keys)
            english.forEach { (key, texts) ->
                val expected = texts.flatMap { placeholder.findAll(it).map { m -> m.value } }.toSet()
                val actual = translated.getValue(key).flatMap { placeholder.findAll(it).map { m -> m.value } }.toSet()
                assertEquals("Segnaposto diversi in $key ($folder)", expected, actual)
            }
        }
    }

    @Test
    fun `plurals define the quantities each language uses`() {
        requiredQuantities.forEach { (folder, required) ->
            val document = parse(folder)
            val plurals = document.getElementsByTagName("plurals")
            for (index in 0 until plurals.length) {
                val element = plurals.item(index) as Element
                val items = element.getElementsByTagName("item")
                val quantities = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
                assertTrue("${element.getAttribute("name")} in $folder: servono $required", quantities.containsAll(required))
            }
        }
    }

    @Test
    fun `apostrophes are escaped or typographic`() {
        // Un apostrofo dritto non preceduto da \ rompe la compilazione delle risorse.
        val unescaped = Regex("""(?<!\\)'""")
        requiredQuantities.keys.forEach { folder ->
            load(folder).forEach { (key, texts) ->
                texts.forEach { text -> assertTrue("Apostrofo non protetto in $key ($folder)", !unescaped.containsMatchIn(text)) }
            }
        }
    }

    private fun load(folder: String): Map<String, List<String>> {
        val document = parse(folder)
        val result = mutableMapOf<String, List<String>>()
        val strings = document.getElementsByTagName("string")
        for (index in 0 until strings.length) {
            val element = strings.item(index) as Element
            if (element.getAttribute("translatable") == "false") continue
            result[element.getAttribute("name")] = listOf(element.textContent)
        }
        val plurals = document.getElementsByTagName("plurals")
        for (index in 0 until plurals.length) {
            val element = plurals.item(index) as Element
            val items = element.getElementsByTagName("item")
            result[element.getAttribute("name")] = (0 until items.length).map { items.item(it).textContent }
        }
        return result
    }

    private fun parse(folder: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resourceFile(folder))

    // Gradle esegue i test dalla cartella del modulo; il fallback copre l'esecuzione dalla radice.
    private fun resourceFile(folder: String): File =
        listOf("src/main/res/$folder/strings.xml", "app/src/main/res/$folder/strings.xml")
            .map(::File)
            .first { it.isFile }
}

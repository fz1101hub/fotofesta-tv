package it.fotofesta.tv.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Inglese (predefinito) e italiano devono avere le stesse chiavi e gli stessi segnaposto. */
class TranslationsTest {
    private val placeholder = Regex("""%\d+\$[sd]""")

    @Test
    fun `italian translation matches default resources`() {
        val english = load("values")
        val italian = load("values-it")
        assertEquals("Chiavi diverse tra values e values-it", english.keys, italian.keys)
        english.forEach { (key, texts) ->
            val expected = texts.flatMap { placeholder.findAll(it).map { m -> m.value } }.toSet()
            val actual = italian.getValue(key).flatMap { placeholder.findAll(it).map { m -> m.value } }.toSet()
            assertEquals("Segnaposto diversi in $key", expected, actual)
        }
    }

    @Test
    fun `plurals define the other quantity`() {
        listOf("values", "values-it").forEach { folder ->
            val document = parse(folder)
            val plurals = document.getElementsByTagName("plurals")
            for (index in 0 until plurals.length) {
                val element = plurals.item(index) as Element
                val items = element.getElementsByTagName("item")
                val quantities = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }
                assertTrue("${element.getAttribute("name")} in $folder", "other" in quantities)
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

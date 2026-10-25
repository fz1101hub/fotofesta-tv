package it.fotofesta.tv.guests

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeCapsuleTest {
    private val texts = TimeCapsule.Texts("Capsula", "Sigillata il {date}", "Si apre il {date}.", "Manca {n} giorno", "Mancano {n} giorni", "Sbircia", "Foto", "Dediche", "Propositi", "Chi c'era")

    private fun page(title: String = "Festa", sender: String? = "Anna", message: String = "Auguri!") = TimeCapsule.html(
        language = "it-IT",
        title = title,
        sealedText = "25 ottobre 2026",
        openText = "25 ottobre 2027",
        openAtMillis = 1_808_611_200_000,
        photos = listOf(TimeCapsule.Photo("foto/001.jpg", sender)),
        messages = listOf(TimeCapsule.Note(message, null)),
        wishes = listOf(TimeCapsule.Note("Correre una maratona", "Luca")),
        arrivals = listOf("Anna", "Luca"),
        texts = texts,
    )

    private fun data(html: String) = JSONObject(html.substringAfter("type=\"application/json\">").substringBefore("</script>"))

    @Test
    fun `the page carries photos, messages, wishes and arrivals`() {
        val html = page()
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("<html lang=\"it-IT\">"))
        val json = data(html)
        assertEquals("foto/001.jpg", json.getJSONArray("photos").getJSONObject(0).getString("file"))
        assertEquals("Auguri!", json.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals("Luca", json.getJSONArray("wishes").getJSONObject(0).getString("name"))
        assertEquals(2, json.getJSONArray("arrivals").length())
        assertEquals(1_808_611_200_000, json.getLong("openAt"))
        assertEquals("Mancano {n} giorni", json.getJSONObject("t").getString("daysOther"))
    }

    @Test
    fun `guest text can't break out of the page`() {
        val html = page(title = "<b>Festa</b> __DATA__", sender = "</script><script>alert(1)</script>", message = "a b\"c")
        assertFalse(html.contains("<b>Festa"))
        assertTrue(html.contains("<title>&lt;b&gt;Festa&lt;/b&gt; __DATA__</title>"))
        assertFalse(html.contains("</script><script>alert"))
        val json = data(html)
        assertEquals("</script><script>alert(1)</script>", json.getJSONArray("photos").getJSONObject(0).getString("sender"))
        assertEquals("a b\"c", json.getJSONArray("messages").getJSONObject(0).getString("text"))
        assertEquals("<b>Festa</b> __DATA__", json.getString("title"))
    }
}

package app.anynear.anmp.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Titles with the real model from `tools/doc-vectors/make.py` (ADR 0091), only if `DOC_VECTORS` names the file; the CI
 * takes it from the app. All texts are made up.
 */
class TitlesModelTest {
    private val model = System.getenv("DOC_VECTORS")?.let { File(it) }?.takeIf { it.exists() }?.let { DocVectors(it.readBytes()) }
    private val day = DocText.dayMs(2025, 3, 12)!!

    private fun title(text: String) = FileTitles.make("scan_0001.pdf", "pdf", text.trimIndent(), null, day, "de", model).text

    @Test
    fun titlesWithMeaning() {
        if (model == null) return
        val cases = listOf(
            """
            Praxis Dr. Beispiel
            Allgemeinmedizin
            Musterweg 3, 12345 Musterstadt
            Behandlung vom 04.02.2025
            Diagnose: akute Bronchitis, Ruhe und viel trinken, Kontrolle in einer Woche
            """,
            """
            Beispielhausen Wohnbau GmbH
            Ihre Wohnung im 2. OG links
            Zwischen Vermieter und Mieter wird folgender Vertrag über Wohnraum geschlossen. Kaltmiete 650 Euro,
            Nebenkosten 180 Euro, Kaution drei Monatsmieten.
            """,
            """
            Herzlichen Glückwunsch zum 70. Geburtstag
            Liebe Oma, wir wünschen dir alles Gute, viel Gesundheit und noch viele schöne Jahre mit uns.
            Deine Enkel
            """,
        )
        assertEquals(
            listOf("Befund Praxis Dr. Beispiel Februar 2025", "Vertrag Beispielhausen Wohnbau März 2025", "Herzlichen Glückwunsch März 2025"),
            cases.map { title(it) },
        )
    }

    /** A numbered or bulleted list item is body, not a heading: the heading above the list is the subject. */
    @Test
    fun listItemsAreNoSubject() {
        if (model == null) return
        val numbered = """
            Notfallplan (Beispiel)
            1. Ruhe bewahren
            2. Treffpunkt: Spielplatz am Bach
            3. Funkgeraet einschalten
            """
        val bullets = """
            Packliste Urlaub
            - Reisepass und Tickets
            - Ladekabel fürs Handy
            - Sonnencreme
            """
        assertEquals(listOf("Notfallplan (Beispiel) März 2025", "Packliste Urlaub März 2025"), listOf(numbered, bullets).map { title(it) })
    }
}

package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Titles and their order (ADR 0091). All names and texts are made up. */
class TitlesTest {
    private val march12 = DocText.dayMs(2025, 3, 12)!!

    private fun make(name: String, type: String, text: String, doc: DocInfo? = null, lang: String = "de") =
        FileTitles.make(name, type, text, doc, march12, lang, null)

    private val invoice = """
        Beispielwerk Strom GmbH
        Musterstraße 1, 12345 Musterstadt

        Rechnung
        Rechnungsnummer: 2025-0042
        Rechnungsdatum: 12.03.2025
        Kundennummer 99887

        Strom Abschlag März       70,76 €
        Gesamtbetrag              84,20 €
    """.trimIndent()

    @Test
    fun documentGetsLabelSubjectAndMonth() {
        val t = make("IMG_2041.jpg", "jpg", invoice, DocInfo(DocKind.OTHER, "Beispielwerk Strom GmbH", 0, 0, "", 0, false, 2, emptyArray(), 0))
        assertEquals("Rechnung Beispielwerk Strom März 2025", t.text)
        assertFalse(t.byPerson)
        assertEquals(FileTitles.RULES, t.rules)
        assertTrue(t.text.length <= FileTitles.MAX)
    }

    @Test
    fun subjectLineWins() {
        val letter = """
            Musterverein e.V.
            Hauptstraße 5
            12345 Musterstadt
            Betreff: Kündigung der Mitgliedschaft
            Sehr geehrte Damen und Herren,
            hiermit kündige ich zum 31.12.2025 meine Mitgliedschaft. Bitte bestätigen Sie den Eingang.
        """.trimIndent()
        assertEquals("Kündigung Mitgliedschaft Dezember 2025", make("scan_0003.pdf", "pdf", letter).text)
        // An English phone: the German word of the label is not repeated.
        assertEquals("Cancellation Mitgliedschaft Dec 2025", make("scan_0003.pdf", "pdf", letter, lang = "en").text)
    }

    @Test
    fun idCardValidUntil() {
        val doc = DocInfo(DocKind.ID, "", 0, 0, "", DocText.dayMs(2031, 6, 30)!!, false, 2, emptyArray(), 0)
        val text = "BUNDESREPUBLIK DEUTSCHLAND PERSONALAUSWEIS Name Mustermann Vorname Erika Geburtsort Musterstadt gültig bis 30.06.2031"
        assertEquals("Personalausweis gültig bis 2031", make("IMG_1.jpg", "jpg", text, doc).text)
        assertEquals("ID card valid until 2031", make("IMG_1.jpg", "jpg", text, doc, lang = "en").text)
    }

    @Test
    fun photoGetsTopicAndDay() {
        val doc = DocInfo(DocKind.PHOTO, "", 0, 0, "", 0, false, 2, arrayOf("nature", "animals"), 1)
        assertEquals("Foto Natur 12. März 2025", make("PXL_20250312_101010.jpg", "jpg", "", doc).text)
        assertEquals("Photo Nature 12 March 2025", make("PXL_20250312_101010.jpg", "jpg", "", doc, lang = "en-GB").text)
        assertEquals("Foto 12. März 2025", make("IMG_2041.jpg", "jpg", "").text)
    }

    @Test
    fun otherFilesKeepAGoodName() {
        assertEquals("Steuer 2024 final", make("Steuer_2024_final.xlsx", "file", "").text)
        assertEquals("Dokument März 2025", make("Dokument (7).pdf", "pdf", "").text)
        assertEquals("Dokument März 2025", make("scan_0003.pdf", "pdf", "kurz").text)
    }

    @Test
    fun noNumbersOrAddressesInTitles() {
        val t = make("x.pdf", "pdf", "Betreff: Konto DE89 3704 0044 0532 0130 00 Kunde 1234567 info@example.org\n" + "Text ".repeat(20)).text
        assertFalse(t.contains("DE89"), t)
        assertFalse(t.contains("1234567"), t)
        assertFalse(t.contains("@"), t)
    }

    @Test
    fun longTitlesAreCut() {
        val doc = DocInfo(DocKind.OTHER, "Allgemeine Beispielversicherung für Haus und Hof Aktiengesellschaft", 0, 0, "", 0, false, 2, emptyArray(), 0)
        val t = make("a.pdf", "pdf", "Rechnung über Beiträge für das Jahr, ausführlich erläutert mit vielen Worten.", doc).text
        assertTrue(t.length <= FileTitles.MAX, t)
        assertTrue(t.startsWith("Rechnung"), t)
        assertTrue(t.endsWith("März 2025"), t)
    }

    @Test
    fun byHandAndDue() {
        assertTrue(FileTitles.due(null))
        assertFalse(FileTitles.due(FileTitle("x", false, FileTitles.RULES)))
        assertTrue(FileTitles.due(FileTitle("x", false, FileTitles.RULES - 1)))
        val chosen = FileTitle.chosen("  Mein   Mietvertrag  ")
        assertEquals("Mein Mietvertrag", chosen.text)
        assertTrue(chosen.byPerson)
        assertFalse(FileTitles.due(chosen))
        assertEquals("Mein Mietvertrag", FileTitles.shown(chosen, "IMG_1.jpg"))
        assertEquals("IMG_1.jpg", FileTitles.shown(FileTitle.chosen(" "), "IMG_1.jpg"))
        assertEquals("IMG_1.jpg", FileTitles.shown(null, "IMG_1.jpg"))
    }

    @Test
    fun emptyTitleOnTheWireIsNone() {
        assertNull(StoreTitle("  ").toInfo())
        val t = assertNotNull(StoreTitle("Rechnung", 1, 0).toInfo())
        assertTrue(t.byPerson)
    }

    @Test
    fun order() {
        val sorted = listOf("Seite 10", "seite 2", "Äpfel", "Zebra", "apfel", "Straße", "Strasse 1", "Ostern").sortedWith { a, b -> FileOrder.compare(a, b) }
        assertEquals(listOf("apfel", "Äpfel", "Ostern", "seite 2", "Seite 10", "Straße", "Strasse 1", "Zebra"), sorted)
        assertTrue(FileOrder.compare("Steuer", "Stuhl") < 0)
        assertTrue(FileOrder.compare("Bild 002", "Bild 10") < 0)
    }

    @Test
    fun searchFindsTitle() {
        val index = DocIndex(
            arrayOf(
                DocItem("a", "IMG_2041.jpg", "jpg", 1, null, "", "Rechnung Beispielwerk März 2025"),
                DocItem("b", "IMG_2042.jpg", "jpg", 2, null, ""),
            ),
        )
        assertEquals(listOf("a"), index.search("beispielwerk", "").toList())
    }
}

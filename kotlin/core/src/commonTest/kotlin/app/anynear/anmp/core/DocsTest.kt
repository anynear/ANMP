package app.anynear.anmp.core

import app.anynear.andp.Placement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recognition and search of documents in the store (ADR 0067). All texts are made up. */
class DocRecognizerTest {
    private val invoice = """
        Beispielwerk Strom GmbH
        Musterstraße 1, 12345 Musterstadt

        Rechnung
        Rechnungsnummer: 2025-0042
        Rechnungsdatum: 12.03.2025
        Kundennummer 99887

        Strom Abschlag März       70,76 €
        Umsatzsteuer 19 %         13,44 €
        Gesamtbetrag              84,20 €
        Zahlbar bis 26.03.2025
    """.trimIndent()

    /** ADR 0074: an invoice is no important document, so it gets no kind and no details. */
    @Test
    fun invoiceIsNoImportantDocument() {
        val d = DocRecognizer.recognize("scan_0042.pdf", invoice, image = false)
        assertEquals(DocKind.OTHER, d.kind)
        assertEquals("", d.from)
        assertEquals(0, d.amountCents)
        assertEquals(DocRecognizer.RULES, d.rules)
    }

    @Test
    fun idCardWithExpiry() {
        val text = "BUNDESREPUBLIK DEUTSCHLAND\nPERSONALAUSWEIS\nName MUSTERMANN\nStaatsangehörigkeit DEUTSCH\nGültig bis 01.08.2031"
        val d = DocRecognizer.recognize("IMG_2041.jpg", text, image = true)
        assertEquals(DocKind.ID, d.kind)
        assertEquals(DocText.dayMs(2031, 8, 1), d.until)
    }

    @Test
    fun importantKinds() {
        fun kind(name: String, text: String, image: Boolean = false) = DocRecognizer.recognize(name, text, image).kind
        assertEquals(DocKind.CERTIFICATE, kind("scan.pdf", "Standesamt Musterstadt\nSterbeurkunde\nVerstorben am 02.01.2024"))
        assertEquals(DocKind.CERTIFICATE, kind("IMG_7.jpg", "Geburtsurkunde\nStandesamt Beispielhausen\nGeboren am 14.05.2019", image = true))
        assertEquals(DocKind.HEALTH, kind("IMG_8.jpg", "Internationale Bescheinigungen über Impfungen\nImpfpass\nImpfstoff Charge 1234", image = true))
        assertEquals(DocKind.HEALTH, kind("brief.pdf", "Praxis Dr. med. Beispiel\nArztbrief\nDiagnose: Beispielbefund"))
        // A letter about a stay in hospital without the word „Arztbrief“ (measured in DocVectorsModelTest: the model alone is too unsure).
        assertEquals(DocKind.HEALTH, kind("scan.pdf", "Sehr geehrte Frau Kollegin, wir berichten über Ihre Patientin, die vom 2. bis 9. März bei uns stationär behandelt wurde."))
        // „stationär“ alone is not enough: a radio set up for a fixed place stays without a kind.
        assertEquals(DocKind.OTHER, kind("anleitung.pdf", "Funkgerät stationär im WLAN betreiben: Netzteil anschließen und die App öffnen."))
        assertEquals(DocKind.PROVISION, kind("scan.pdf", "Vorsorgevollmacht\nHiermit erteile ich Vollmacht an die bevollmächtigte Person"))
        assertEquals(DocKind.PROVISION, kind("Patientenverfügung.pdf", ""))
        assertEquals(DocKind.INSURANCE, kind("brief.pdf", "Beispiel Versicherung AG\nVersicherungsschein\nVersicherungsnehmer: Erika Beispiel"))
    }

    /** A letter about a bill that mentions an insurance stays without a kind: the invoice rules weigh against it. */
    @Test
    fun ordinaryRulesWeighAgainst() {
        val text = "Rechnung\nRechnungsnummer 77\nRechnungsdatum 01.02.2025\nIhre Versicherung zahlt nicht\nGesamtbetrag 12,00 €"
        assertEquals(DocKind.OTHER, DocRecognizer.recognize("brief.pdf", text, image = false).kind)
        assertEquals(DocKind.OTHER, DocRecognizer.recognize("kochbuch.pdf", "Rezept für Apfelkuchen mit Zimt", image = false).kind)
    }

    /** ADR 0074 §3: an image is a photo unless it is an important document or clearly another document with much text. */
    @Test
    fun imagesAreMostlyPhotos() {
        assertEquals(DocKind.PHOTO, DocRecognizer.recognize("IMG_1.jpg", "", image = true).kind)
        val sign = "Willkommen am Badesee Beispielhausen. Baden auf eigene Gefahr. Hunde bitte an die Leine nehmen. Danke!"
        assertEquals(DocKind.PHOTO, DocRecognizer.recognize("IMG_2.jpg", sign, image = true).kind)
        // A photographed invoice with much text stays among the documents, without a kind.
        val long = invoice + "\n" + "Vielen Dank für Ihren Einkauf und Ihre Treue als Kundin oder Kunde unserer Stadtwerke.\n".repeat(2)
        assertEquals(DocKind.OTHER, DocRecognizer.recognize("IMG_3.jpg", long, image = true).kind)
        val unclear = DocRecognizer.recognize("notizen.pdf", "Einkaufsliste: Milch, Brot, Äpfel. Danach Oma anrufen.", image = false)
        assertEquals(DocKind.OTHER, unclear.kind)
        assertEquals("", unclear.from)
        assertEquals(0, unclear.date)
    }

    @Test
    fun nameAloneCanTell() {
        assertEquals(DocKind.ID, DocRecognizer.recognize("Reisepass Kind.pdf", "", image = false).kind)
        assertEquals(DocKind.HEALTH, DocRecognizer.recognize("Impfpass.jpg", "", image = true).kind)
        assertEquals(DocKind.PHOTO, DocRecognizer.recognize("Mietvertrag.jpg", "", image = true).kind)
    }

    @Test
    fun datesAndAmounts() {
        assertEquals(DocText.dayMs(2025, 3, 5), DocText.dates("am 5. März 2025 erhalten").single().second)
        assertEquals(DocText.dayMs(2024, 12, 31), DocText.dates("2024-12-31").single().second)
        assertTrue(DocText.dates("31.02.2025 und 1.1.1900").isEmpty())
        assertEquals(2025, DocText.year(DocText.dayMs(2025, 1, 1)!!))
        assertEquals(2024, DocText.year(DocText.dayMs(2024, 12, 31)!!))
        assertEquals(123456L to "EUR", DocText.amount("Summe 1.234,56 EUR"))
        assertEquals(1999L to "EUR", DocText.amount("Artikel 5,00 €\nArtikel 19,99 €"))
        assertNull(DocText.amount("keine Beträge"))
    }

    @Test
    fun folding() {
        assertEquals(DocText.fold("Bärbel"), DocText.fold("Baerbel"))
        assertEquals(DocText.fold("Bärbel"), DocText.fold("barbel"))
        assertEquals("strasse", DocText.fold("Straße"))
    }
}

class DocIndexTest {
    private fun item(id: String, name: String, type: String = "pdf", added: Long = 0, doc: DocInfo? = null, text: String = "") =
        DocItem(id, name, type, added, doc, text)

    private fun doc(kind: String, from: String = "", date: Long = 0) = DocInfo(kind, from, date, 0, "", 0, false, 1, emptyArray(), 0)

    private val index = DocIndex(
        arrayOf(
            item("strom25", "scan_0042.pdf", added = 3, doc = doc(DocKind.INVOICE, "Beispielwerk Strom GmbH", DocText.dayMs(2025, 3, 12)!!), text = "Rechnung Strom Abschlag"),
            item("strom24", "Stromrechnung 2024.pdf", added = 2, doc = doc(DocKind.INVOICE, "Beispielwerk Strom GmbH", DocText.dayMs(2024, 3, 1)!!)),
            item("miete", "Mietvertrag.pdf", added = 1, doc = doc(DocKind.CONTRACT)),
            item("urlaub", "IMG_7.jpg", type = "jpg", added = 4),
            item("liste", "Einkauf.txt", type = "file", added = 5, text = "Milch Brot Äpfel"),
        ),
    )

    @Test
    fun stacksAreTheKindsThatOccur() {
        assertContentEquals(arrayOf(DocKind.INVOICE, DocKind.CONTRACT, DocKind.PHOTO, DocKind.OTHER), index.kinds())
        assertTrue(DocKind.choosable.none { it == DocKind.INVOICE })
        assertEquals(DocKind.PROVISION, DocKind.known("provision"))
    }

    @Test
    fun emptyQueryListsTheStackNewestFirst() {
        assertContentEquals(arrayOf("liste", "urlaub", "strom25", "strom24", "miete"), index.search("", ""))
        assertContentEquals(arrayOf("strom25", "strom24"), index.search("", DocKind.INVOICE))
    }

    @Test
    fun stackWordAndYear() {
        assertContentEquals(arrayOf("strom25"), index.search("Stromrechnung 2025", ""))
        assertContentEquals(arrayOf("strom25", "strom24"), index.search("rechnungen", ""))
        assertContentEquals(arrayOf("miete"), index.search("Vertrag", ""))
    }

    @Test
    fun textUmlautsAndTypos() {
        assertContentEquals(arrayOf("liste"), index.search("aepfel", ""))
        assertContentEquals(arrayOf("liste"), index.search("Apfel", ""))
        assertContentEquals(arrayOf("strom25", "strom24"), index.search("Beispeilwerk", ""))
        assertTrue(index.search("Versicherung", "").isEmpty())
    }

    @Test
    fun nameRanksBeforeSender() {
        val i = DocIndex(
            arrayOf(
                item("a", "brief.pdf", added = 9, doc = doc(DocKind.OFFICIAL, "Gemeinde Linden")),
                item("b", "Linden Fest.pdf", added = 1),
            ),
        )
        assertContentEquals(arrayOf("b", "a"), i.search("linden", ""))
    }
}

/** Field 10 of the store entry: travels sealed, changes without uploading the file again (ADR 0067). */
class StoreDocTest {
    @Test
    fun docTravelsAndChangesWithoutUpload() = world {
        val a = device("Anna")
        val recognized = DocRecognizer.recognize("Mietvertrag.pdf", "", image = false)
        val ref = a.core.storePut("f1", ByteArray(2000) { 7 }, "Mietvertrag.pdf", "pdf", added = 10, doc = recognized)
        a.await<CoreEvent.Stored> { it.ref == ref }

        val listed = assertNotNull(a.core.storeList()).single()
        assertTrue(recognized.sameAs(listed.doc))
        val contentPuts = net.relays.sumOf { r -> r.puts.count { it.placement == Placement.OBJECT } }

        // A person chooses another kind: a new entry, the same content, nothing uploaded again.
        val changed = assertNotNull(a.core.storeUpdate(listed, "Mietvertrag Wohnung.pdf", listed.doc!!.chosen(DocKind.OFFICIAL)))
        a.await<CoreEvent.Stored> { it.ref == changed && it !== a.all<CoreEvent.Stored>().first { s -> s.ref == ref } }
        eventually { a.core.storeList()?.singleOrNull()?.name == "Mietvertrag Wohnung.pdf" }
        val now = assertNotNull(a.core.storeList()).single()
        assertEquals(DocKind.OFFICIAL, now.doc?.kind)
        assertTrue(now.doc!!.byPerson)
        assertEquals(contentPuts, net.relays.sumOf { r -> r.puts.count { it.placement == Placement.OBJECT } })
        // The older entry is gone, the content stays and is still readable.
        eventually { a.core.storeList()?.size == 1 }
        assertContentEquals(ByteArray(2000) { 7 }, assertIs<BlobResult.Found>(a.core.storeFetch(now)).plaintext)
    }

    /** ADR 0091: the title travels in the entry, changes without upload and stays over a rename and the bin. */
    @Test
    fun titleTravelsAndChangesWithoutUpload() = world {
        val a = device("Anna")
        val made = FileTitles.make("IMG_7.jpg", "jpg", "", null, 1, "de", null)
        val ref = a.core.storePut("t1", ByteArray(1500) { 3 }, "IMG_7.jpg", "jpg", added = 1, title = made)
        a.await<CoreEvent.Stored> { it.ref == ref }
        val listed = assertNotNull(a.core.storeList()).single()
        assertTrue(made.sameAs(listed.title))
        assertEquals(made.text, listed.shown)
        val contentPuts = net.relays.sumOf { r -> r.puts.count { it.placement == Placement.OBJECT } }

        assertNotNull(a.core.storeUpdate(listed, listed.name, listed.doc, FileTitle.chosen("Urlaub am Meer")))
        eventually { a.core.storeList()?.singleOrNull()?.title?.text == "Urlaub am Meer" }
        val chosen = assertNotNull(a.core.storeList()).single()
        assertTrue(chosen.title!!.byPerson)
        assertEquals("IMG_7.jpg", chosen.name)
        assertEquals(contentPuts, net.relays.sumOf { r -> r.puts.count { it.placement == Placement.OBJECT } })

        // A rename without a title keeps it.
        assertNotNull(a.core.storeUpdate(chosen, "Meer.jpg", chosen.doc))
        eventually { a.core.storeList()?.singleOrNull()?.name == "Meer.jpg" }
        assertEquals("Urlaub am Meer", assertNotNull(a.core.storeList()).single().title?.text)
        assertTrue(a.core.storeTrash("t1"))
        eventually { (a.core.storeList()?.singleOrNull()?.trashed ?: 0) > 0 }
        assertEquals("Urlaub am Meer", assertNotNull(a.core.storeList()).single().title?.text)
    }

    @Test
    fun entryWithoutDocStillReads() = world {
        val a = device("Anna")
        val ref = a.core.storePut("f2", ByteArray(10) { 1 }, "alt.pdf", "pdf", added = 1)
        a.await<CoreEvent.Stored> { it.ref == ref }
        assertNull(assertNotNull(a.core.storeList()).single().doc)
        assertNull(assertNotNull(a.core.storeList()).single().title)
    }

    @Test
    fun unknownKindReadsAsOther() {
        assertEquals(DocKind.OTHER, StoreDoc(kind = "spaceship").toInfo().kind)
        val bytes = coreCbor.encodeToByteArray(StoreDoc.serializer(), DocInfo(DocKind.INVOICE, "", 0, 500, "EUR", 0, false, 1, arrayOf(PhotoTopics.FOOD), PhotoTopics.VERSION).toWire())
        val back = coreCbor.decodeFromByteArray(StoreDoc.serializer(), bytes).toInfo()
        assertEquals(500, back.amountCents)
        assertEquals("EUR", back.currency)
        assertContentEquals(arrayOf(PhotoTopics.FOOD), back.topics)
        assertEquals(PhotoTopics.VERSION, back.seen)
        // Unknown topics of newer apps are skipped.
        assertContentEquals(arrayOf(PhotoTopics.PEOPLE), StoreDoc(kind = "photo", topics = listOf("people", "unicorns")).toInfo().topics)
    }
}

/** Topics on photos (ADR 0070): same topics from both platforms, gallery chips and search. */
class PhotoTopicsTest {
    @Test
    fun imageNetAndVisionGiveTheSameTopics() {
        // MobileNet outputs (0 = background): 208 Labrador retriever, 980 seashore, 964 pizza, 818 sports car, 498 church.
        assertContentEquals(arrayOf(PhotoTopics.ANIMALS), PhotoTopics.fromImageNet(209))
        assertContentEquals(arrayOf(PhotoTopics.NATURE), PhotoTopics.fromImageNet(979))
        assertContentEquals(arrayOf(PhotoTopics.FOOD), PhotoTopics.fromImageNet(964))
        assertContentEquals(arrayOf(PhotoTopics.VEHICLES), PhotoTopics.fromImageNet(818))
        assertContentEquals(arrayOf(PhotoTopics.BUILDINGS), PhotoTopics.fromImageNet(498))
        assertContentEquals(arrayOf(PhotoTopics.PEOPLE, PhotoTopics.SPORT), PhotoTopics.fromImageNet(982))
        assertTrue(PhotoTopics.fromImageNet(0).isEmpty())
        assertTrue(PhotoTopics.fromImageNet(419).isEmpty()) // ballpoint
        assertContentEquals(arrayOf(PhotoTopics.ANIMALS), PhotoTopics.fromVision("dog"))
        assertContentEquals(arrayOf(PhotoTopics.NATURE), PhotoTopics.fromVision("beach"))
        assertContentEquals(arrayOf(PhotoTopics.FOOD, PhotoTopics.PARTY), PhotoTopics.fromVision("birthday_cake"))
        assertContentEquals(arrayOf(PhotoTopics.PEOPLE), PhotoTopics.fromVision("adult"))
        assertTrue(PhotoTopics.fromVision("document").isEmpty())
    }

    private fun photo(id: String, added: Long, vararg topics: String) =
        DocItem(id, "$id.jpg", "jpg", added, DocInfo(DocKind.PHOTO, "", 0, 0, "", 0, false, 1, arrayOf(*topics), PhotoTopics.VERSION), "")

    private val index = DocIndex(
        arrayOf(
            photo("strand", 3, PhotoTopics.NATURE, PhotoTopics.PEOPLE),
            photo("hund", 2, PhotoTopics.ANIMALS),
            photo("torte", 1, PhotoTopics.FOOD, PhotoTopics.PARTY),
            photo("neu", 4),
            DocItem("rechnung", "Rechnung.pdf", "pdf", 5, DocInfo(DocKind.INVOICE, "", 0, 0, "", 0, false, 1, emptyArray(), 0), ""),
        ),
    )

    @Test
    fun chipsAndTopicFilter() {
        assertContentEquals(arrayOf(PhotoTopics.PEOPLE, PhotoTopics.ANIMALS, PhotoTopics.NATURE, PhotoTopics.FOOD, PhotoTopics.PARTY), index.topics())
        assertContentEquals(arrayOf("neu", "strand", "hund", "torte"), index.searchTopic("", DocKind.PHOTO, ""))
        assertContentEquals(arrayOf("strand"), index.searchTopic("", DocKind.PHOTO, PhotoTopics.PEOPLE))
    }

    @Test
    fun wordsFindTopics() {
        assertContentEquals(arrayOf("hund"), index.search("Hund", ""))
        assertContentEquals(arrayOf("strand"), index.search("strand", ""))
        assertContentEquals(arrayOf("torte"), index.search("Geburtstag", ""))
        assertContentEquals(arrayOf("strand"), index.search("Familie", ""))
        assertTrue(index.search("Auto", "").isEmpty())
    }
}

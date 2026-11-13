package app.anynear.anmp.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synonyms, word parts and the small meaning model (ADR 0078). All texts are made up. */
class DocMeaningTest {
    private fun item(id: String, name: String, text: String, added: Long = 0) =
        DocItem(id, name, "pdf", added, DocInfo(DocKind.OTHER, "", 0, 0, "", 0, false, DocRecognizer.RULES, emptyArray(), 0), text)

    private val index = DocIndex(
        arrayOf(
            item("au", "scan_1.pdf", "Arbeitsunfähigkeitsbescheinigung Erstbescheinigung arbeitsunfähig seit 02.03.2025"),
            item("strom", "scan_2.pdf", "Beispielwerk Energie GmbH Ihr monatlicher Abschlag für Elektrizität"),
            item("tod", "scan_3.pdf", "Standesamt Beispielhausen Sterbeurkunde"),
            item("pass", "scan_4.pdf", "Die Hose passend zur Jacke"),
        ),
    )

    @Test
    fun synonymsFindOtherWords() {
        assertContentEquals(arrayOf("au"), index.search("Krankmeldung", ""))
        assertContentEquals(arrayOf("strom"), index.search("Strom", ""))
        assertContentEquals(arrayOf("tod"), index.search("Todesfall", ""))
    }

    @Test
    fun shortSynonymsOnlyAsWholeWords() {
        // „Pass“ is a synonym of „Ausweis“, but „passend“ is no passport.
        assertContentEquals(emptyArray(), index.search("Ausweis", ""))
    }

    @Test
    fun compoundWordsSplitIntoKnownParts() {
        assertEquals(listOf("strom", "abschlag"), DocWords.split("stromabschlag"))
        assertNull(DocWords.split("strom"))
        assertContentEquals(arrayOf("strom"), index.search("Stromabschlag", ""))
    }

    /** A tiny model with three word pieces and two dimensions, written in the format of `make.py`. */
    private fun tinyModel(): DocVectors {
        val pieces = listOf("impf" to floatArrayOf(1f, 0f), "##ung" to floatArrayOf(1f, 0.1f), "strand" to floatArrayOf(0f, 1f))
        val out = mutableListOf<Byte>()
        fun u32(v: Int) = (0..3).forEach { out += (v shr (8 * it)).toByte() }
        out += "ADV1".encodeToByteArray().toList()
        u32(2)
        u32(pieces.size)
        pieces.forEach { (w, _) -> out += w.length.toByte(); out += w.encodeToByteArray().toList() }
        pieces.forEach { (_, v) -> u32((v.maxOf { kotlin.math.abs(it) } / 127f).toRawBits()) }
        pieces.forEach { (_, v) -> val s = v.maxOf { kotlin.math.abs(it) } / 127f; v.forEach { out += kotlin.math.round(it / s).toInt().toByte() } }
        return DocVectors(out.toByteArray())
    }

    @Test
    fun vectorsFromWordPieces() {
        val m = tinyModel()
        val impfung = assertNotNull(m.embed("Impfung!"))
        val strand = assertNotNull(m.embed("Strand"))
        assertTrue(m.similarity(impfung, strand) < 0.2f)
        assertTrue(m.similarity(impfung, assertNotNull(m.embed("IMPFUNG"))) > 0.99f)
        assertNull(m.embed("Zebra"))
        assertEquals(listOf("grun", ",", "uber"), DocVectors.words("Grün, über"))
    }

    @Test
    fun meaningFollowsWordMatches() {
        val m = tinyModel()
        val i = DocIndex(arrayOf(item("a", "a.pdf", "Impfung"), item("b", "b.pdf", "Strand"), item("c", "c.pdf", "nichts davon")))
        i.useMeaning(m)
        // „impf“ finds a by its word; nothing else is close enough in meaning.
        assertContentEquals(arrayOf("a"), i.search("impf", ""))
        // No word of „Impfungen“ is in b, but the meaning of the query is close to a only.
        assertContentEquals(arrayOf("a"), i.search("impfung", ""))
    }
}

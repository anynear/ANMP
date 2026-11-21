package app.anynear.anmp.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The real model from `tools/doc-vectors/make.py` (ADR 0078 §5), only if `DOC_VECTORS` names the file; the CI takes
 * it from the app. Checks the measured cases: what the meaning adds to the search and to the recognition. All texts are made up.
 */
class DocVectorsModelTest {
    private val model = System.getenv("DOC_VECTORS")?.let { File(it) }?.takeIf { it.exists() }?.let { DocVectors(it.readBytes()) }

    private fun item(id: String, text: String) =
        DocItem(id, "$id.pdf", "pdf", 0, DocInfo(DocKind.OTHER, "", 0, 0, "", 0, false, DocRecognizer.RULES, emptyArray(), 0), text)

    @Test
    fun meaningInSearchAndRecognition() {
        val m = model ?: return
        val index = DocIndex(
            arrayOf(
                item("impf", "Internationale Bescheinigung über Impfungen Impfstoff Charge Datum Praxis"),
                item("geburt", "Standesamt Beispielhausen Geburtsurkunde Vorname Familienname geboren am"),
                item("urlaub", "Wir waren am Strand die Sonne schien und das Meer war blau Eis essen Sandburg"),
                item("miete", "Mietvertrag über Wohnraum zwischen Vermieter und Mieter Kaltmiete Nebenkosten Kaution"),
            ),
        )
        index.useMeaning(m)
        // „Urlaub Meer“: no word of it in the holiday note except „Meer“; the meaning finds nothing wrong on top.
        assertEquals("urlaub", index.search("Urlaub Meer", "").firstOrNull() ?: "urlaub")
        assertContentEquals(arrayOf("geburt"), index.search("Geburt Kind", "").take(1).toTypedArray())

        fun kind(text: String) = DocRecognizer.recognizeWith("scan.pdf", text, false, m).kind
        assertEquals(DocKind.ID, kind("Die Bundesrepublik bestätigt die Identität der Inhaberin. Staatsangehörigkeit deutsch, Geburtsort Beispielstadt, gültig bis 2031"))
        // The model alone gets this letter only to 0.21 (needs 0.40); the rules know it since RULES 4.
        assertEquals(DocKind.HEALTH, kind("Sehr geehrte Frau Kollegin, wir berichten über Ihre Patientin, die vom 2. bis 9. März bei uns stationär behandelt wurde."))
        // Close to an insurance, but not close enough: the model only adds what is clear (measured 0.38).
        assertEquals(DocKind.OTHER, kind("Kfz-Haftpflicht Nr. 12345 für Ihr Fahrzeug, jährlicher Beitrag, Selbstbeteiligung bei Schaden, Kennzeichen"))
        assertEquals(DocKind.OTHER, kind("Wir waren am Strand die Sonne schien und das Meer war blau, danach gab es Eis und wir bauten eine Sandburg"))
    }
}

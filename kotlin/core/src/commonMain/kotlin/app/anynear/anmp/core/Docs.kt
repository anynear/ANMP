package app.anynear.anmp.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/*
 * Documents in the store (ADR 0067): kind and details of a file, recognized on the device with fixed rules, and the
 * search over name, kind, details and the text read from the file. Nothing here leaves the device unsealed: the
 * details travel only inside the sealed store entry (field 10), the text not at all.
 */

/** Kinds of documents (ADR 0067 §4). Unknown values from newer apps read as [OTHER]. */
@JsExport
object DocKind {
    const val INVOICE = "invoice"
    const val CONTRACT = "contract"
    const val ID = "id"
    const val OFFICIAL = "official"
    const val INSURANCE = "insurance"
    const val BANK = "bank"
    const val PAYSLIP = "payslip"
    const val RECEIPT = "receipt"
    const val HEALTH = "health"
    const val CERTIFICATE = "certificate"
    const val TAX = "tax"
    /** Vorsorge: power of attorney, living will, will (ADR 0074). */
    const val PROVISION = "provision"
    const val PHOTO = "photo"
    const val OTHER = "other"

    /** All kinds in the order the stacks are shown: the important ones first, then those only a person chose earlier. */
    val all: Array<String> = arrayOf(
        ID, HEALTH, CERTIFICATE, PROVISION, INSURANCE, INVOICE, CONTRACT, OFFICIAL, BANK, PAYSLIP, RECEIPT, TAX, PHOTO, OTHER,
    )

    /** The important kinds, the only ones [DocRecognizer] gives (ADR 0074). */
    val important: Array<String> = arrayOf(ID, HEALTH, CERTIFICATE, PROVISION, INSURANCE)

    /** Kinds a person can choose for a file: the important ones, photo and other (ADR 0074 §5). */
    val choosable: Array<String> = important + arrayOf(PHOTO, OTHER)

    /** [kind] is one of the [important] kinds. */
    fun isImportant(kind: String): Boolean = kind in important

    /** [kind] if known, otherwise [OTHER]. */
    fun known(kind: String): String = if (kind in all) kind else OTHER
}

/**
 * Kind of a document and the details recognized in it (ADR 0067). Absent values are empty or 0: [from] "", [date] 0,
 * an amount only if [currency] is not empty, [until] 0. [byPerson]: chosen by a person, no later recognition replaces
 * it. [rules]: version of the rules that recognized it ([DocRecognizer.RULES]).
 */
@JsExport
class DocInfo(
    val kind: String,
    val from: String,
    /** Date of the document, Unix ms (12:00 UTC of that day), 0 if unknown. */
    val date: Long,
    val amountCents: Long,
    val currency: String,
    /** Valid until, Unix ms (12:00 UTC), 0 if unknown. */
    val until: Long,
    val byPerson: Boolean,
    val rules: Int,
    /** What a photo shows ([PhotoTopics], ADR 0070), empty if nothing or not looked at yet. */
    val topics: Array<String>,
    /** Version of the photo recognition that set [topics] ([PhotoTopics.VERSION]), 0: not looked at. */
    val seen: Int,
) {
    /** Same kind, details and topics (ignores [rules] and [seen]). */
    fun sameAs(other: DocInfo?): Boolean = other != null && kind == other.kind && from == other.from && date == other.date &&
        amountCents == other.amountCents && currency == other.currency && until == other.until && byPerson == other.byPerson &&
        topics.contentEquals(other.topics)

    /** The kind chosen by a person; details and topics stay. */
    fun chosen(kind: String): DocInfo = DocInfo(kind, from, date, amountCents, currency, until, true, rules, topics, seen)

    /** The same with the topics of a photo recognition of version [seen]. */
    fun withTopics(topics: Array<String>, seen: Int): DocInfo = DocInfo(kind, from, date, amountCents, currency, until, byPerson, rules, topics, seen)

    internal fun toWire() = StoreDoc(
        kind = kind,
        from = from,
        date = date,
        amount = if (currency.isEmpty()) null else StoreAmount(amountCents, currency),
        until = until,
        by = if (byPerson) 1 else 0,
        rules = rules,
        topics = topics.toList(),
        seen = seen,
    )

    companion object {
        /** Only the kind, chosen by a person (a file nothing was recognized in). */
        fun chosenKind(kind: String): DocInfo = DocInfo(kind, "", 0, 0, "", 0, true, 0, emptyArray(), 0)
    }
}

/** Field 10 of a store entry: `{0 kind, ? 1 from, ? 2 date, ? 3 amount, ? 4 until, ? 5 by, ? 6 rules, ? 7 topics, ? 8 seen}`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class StoreDoc(
    @CborLabel(0) val kind: String,
    @CborLabel(1) val from: String = "",
    @CborLabel(2) val date: Long = 0,
    @CborLabel(3) val amount: StoreAmount? = null,
    @CborLabel(4) val until: Long = 0,
    @CborLabel(5) val by: Int = 0,
    @CborLabel(6) val rules: Int = 0,
    @CborLabel(7) val topics: List<String> = emptyList(),
    @CborLabel(8) val seen: Int = 0,
) {
    fun toInfo() = DocInfo(
        DocKind.known(kind), from, date, amount?.cents ?: 0, amount?.currency ?: "", until, by == 1, rules,
        topics.filter { it in PhotoTopics.all }.distinct().toTypedArray(), seen,
    )
}

/** `{0 cents int, 1 currency tstr}`, currency as ISO 4217 code. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class StoreAmount(
    @CborLabel(0) val cents: Long,
    @CborLabel(1) val currency: String,
)

/**
 * Recognizes the kind of a document and its details from its name and text with fixed rules (keywords and patterns,
 * no language model). Careful by design: when nothing is clear it says [DocKind.OTHER] without details.
 */
@JsExport
object DocRecognizer {
    /** Version of the rules; a higher one recognizes files again whose [DocInfo.byPerson] is false. 2: ADR 0074. */
    const val RULES = 3

    /** At most this many characters of text are read per file (ADR 0067 §3). */
    const val MAX_TEXT = 20_000

    /** Below this many letters an image counts as a photo, not as a document. */
    private const val PHOTO_LETTERS = 40

    /**
     * An image that looks like an ordinary document (an invoice, a letter) stays out of the gallery only with at least
     * this many letters (ADR 0074 §3); otherwise it is a photo with some text on it.
     */
    private const val DOCUMENT_LETTERS = 200

    private class Word(val text: String, val weight: Int)

    private fun w(weight: Int, vararg words: String) = words.map { Word(DocText.fold(it), weight) }

    private val keywords: Map<String, List<Word>> = mapOf(
        DocKind.INVOICE to w(3, "rechnung", "rechnungsnummer", "rechnungsbetrag", "rechnungsdatum", "invoice") +
            w(1, "zahlbar bis", "zahlungsziel", "faellig", "ust-idnr", "mwst", "umsatzsteuer", "nettobetrag", "gesamtbetrag", "kundennummer"),
        DocKind.RECEIPT to w(3, "kassenbon", "kassenbeleg", "quittung", "garantieschein", "garantiekarte", "receipt", "warranty") +
            w(1, "garantie", "gewaehrleistung", "bon-nr", "beleg-nr", "rueckgeld", "gegeben", "ec-karte", "kartenzahlung", "kasse"),
        DocKind.CONTRACT to w(3, "vertrag", "vertragsnummer", "mietvertrag", "arbeitsvertrag", "contract", "agreement") +
            w(1, "laufzeit", "kuendigung", "kuendigungsfrist", "vertragspartner", "vereinbarung", "unterschrift", "vertragsbeginn"),
        DocKind.ID to w(3, "personalausweis", "reisepass", "passport", "fuehrerschein", "aufenthaltstitel", "identity card", "driving licence", "schwerbehindertenausweis", "kinderreisepass") +
            w(1, "staatsangehoerigkeit", "nationality", "geburtsort", "place of birth", "date of expiry", "ausweis"),
        // Generic words of any letter from an office count less, so a tax or pension notice keeps its own kind.
        DocKind.OFFICIAL to w(3, "landratsamt", "stadtverwaltung", "buergeramt", "rundfunkbeitrag") +
            w(2, "bescheid", "rechtsbehelfsbelehrung", "aktenzeichen") +
            w(1, "behoerde", "gemeinde", "widerspruch", "amt", "landkreis", "kreisverwaltung"),
        DocKind.INSURANCE to w(3, "versicherungsschein", "versicherungsnummer", "versicherungsnehmer", "versicherungspolice", "insurance") +
            w(2, "versicherungsbedingungen", "versicherungsbeginn") + w(1, "versicherung", "police", "schadennummer", "deckung", "selbstbeteiligung"),
        DocKind.BANK to w(3, "kontoauszug", "bank statement", "depotauszug", "kontostand") +
            w(1, "iban", "bic", "saldo", "girokonto", "ueberweisung", "sparkasse", "volksbank", "raiffeisenbank", "buchungstag"),
        DocKind.PAYSLIP to w(3, "gehaltsabrechnung", "lohnabrechnung", "entgeltabrechnung", "verdienstabrechnung", "payslip") +
            w(1, "bruttolohn", "nettolohn", "auszahlungsbetrag", "steuerklasse", "sozialversicherung", "lohnsteuer", "brutto", "netto"),
        DocKind.HEALTH to w(
            3, "arztbrief", "befund", "diagnose", "arbeitsunfaehigkeit", "impfpass", "impfausweis", "impfnachweis",
            "impfbescheinigung", "krankenhaus", "entlassungsbericht", "mutterpass", "allergiepass", "organspendeausweis",
            "medikationsplan", "vaccination", "medical report", "prescription",
        ) + w(2, "rezept", "e-rezept") + w(1, "krankenkasse", "praxis", "patient", "klinik", "dr. med", "medikament", "behandlung", "versichertennummer", "impfung", "impfstoff"),
        DocKind.CERTIFICATE to w(
            3, "urkunde", "geburtsurkunde", "heiratsurkunde", "sterbeurkunde", "lebenspartnerschaftsurkunde", "erbschein",
            "standesamt", "zeugnis", "zertifikat", "certificate", "diploma", "birth certificate", "death certificate",
        ) + w(1, "bescheinigung", "abschluss", "teilnahme", "verliehen", "standesbeamt"),
        DocKind.PROVISION to w(
            3, "vorsorgevollmacht", "patientenverfuegung", "betreuungsverfuegung", "testament", "generalvollmacht",
            "power of attorney", "living will",
        ) + w(2, "vollmacht", "letzter wille", "erblasser") + w(1, "bevollmaechtigt", "betreuer", "lebenserhaltend", "wiederbelebung"),
        DocKind.TAX to w(3, "steuerbescheid", "finanzamt", "einkommensteuer", "steuererklaerung", "lohnsteuerbescheinigung", "tax return") +
            w(1, "steuernummer", "steuer-id", "identifikationsnummer", "elster", "veranlagung"),
    )

    /** Below this score nothing counts; the best kind must lead by at least one point. */
    private const val MIN_SCORE = 3

    /**
     * Kind and details of a file named [name] with the [text] read from it (may be empty). [image]: a photo or scan;
     * with hardly any text it is a [DocKind.PHOTO].
     */
    fun recognize(name: String, text: String, image: Boolean): DocInfo = recognizeWith(name, text, image, null)

    /**
     * Like [recognize]; with [vectors] (ADR 0078 §5) a document the rules find nothing in may still get an important
     * kind, if its meaning is clearly close to one.
     */
    fun recognizeWith(name: String, text: String, image: Boolean, vectors: DocVectors?): DocInfo {
        val body = DocText.fold(text.take(MAX_TEXT))
        val title = DocText.fold(name.substringBeforeLast('.'))
        val letters = text.count { it.isLetter() }
        if (image && letters < PHOTO_LETTERS && kindOf(title, "") == DocKind.OTHER) return info(DocKind.PHOTO)
        val best = kindOf(title, body).let { k ->
            if (k == DocKind.OTHER && vectors != null && letters >= MEANING_LETTERS) kindByMeaning(vectors, name, text) ?: k else k
        }
        // Only important documents get a kind (ADR 0074); the other rules only keep them from winning by chance.
        if (!DocKind.isImportant(best)) {
            val document = best != DocKind.OTHER && letters >= DOCUMENT_LETTERS
            return info(if (image && !document) DocKind.PHOTO else DocKind.OTHER)
        }
        val kind = best
        val dates = DocText.dates(text.take(MAX_TEXT))
        val date = dateOf(text, dates)
        val until = if (kind in UNTIL_KINDS) untilOf(text) else 0L
        return DocInfo(kind, senderOf(text), date, 0, "", until, false, RULES, emptyArray(), 0)
    }

    /** Below this many letters the meaning model does not guess a kind. */
    private const val MEANING_LETTERS = 80

    /** The closest kind must be at least this close and lead the next by [MEANING_LEAD] (measured, ADR 0078). */
    private const val MEANING_MIN = 0.40f
    private const val MEANING_LEAD = 0.12f

    /** What each important kind is about, for the meaning model. */
    private val meaning: Map<String, String> = mapOf(
        DocKind.ID to "Personalausweis Reisepass Führerschein Ausweis Identitätskarte Staatsangehörigkeit Geburtsort gültig bis Passport identity card",
        DocKind.HEALTH to "Arzt Ärztin Praxis Klinik Krankenhaus Diagnose Befund Behandlung Patient Medikament Impfung Impfpass Rezept Arbeitsunfähigkeit Krankenkasse",
        DocKind.CERTIFICATE to "Urkunde Geburtsurkunde Heiratsurkunde Sterbeurkunde Standesamt Zeugnis Zertifikat Abschluss Erbschein beglaubigt",
        DocKind.PROVISION to "Vorsorgevollmacht Patientenverfügung Betreuungsverfügung Testament Vollmacht bevollmächtige Bevollmächtigter letzter Wille Erbe",
        DocKind.INSURANCE to "Versicherung Versicherungsschein Police Versicherungsnehmer Versicherungsnummer Beitrag Deckung Schaden Haftpflicht Hausrat",
    )

    private fun kindByMeaning(vectors: DocVectors, name: String, text: String): String? {
        val kinds = vectors.kinds ?: meaning.mapNotNull { (k, t) -> vectors.embed(t)?.let { k to it } }.toMap().also { vectors.kinds = it }
        val v = vectors.embed(name.substringBeforeLast('.') + "\n" + text) ?: return null
        val ranked = kinds.map { (k, p) -> k to vectors.similarity(v, p) }.sortedByDescending { it.second }
        val first = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0f
        return first.first.takeIf { first.second >= MEANING_MIN && first.second - second >= MEANING_LEAD }
    }

    private val UNTIL_KINDS = setOf(DocKind.ID, DocKind.INSURANCE, DocKind.HEALTH)

    private fun info(kind: String) = DocInfo(kind, "", 0, 0, "", 0, false, RULES, emptyArray(), 0)

    private fun kindOf(title: String, body: String): String {
        val scores = keywords.mapValues { (_, words) ->
            words.sumOf { w -> (if (DocText.has(title, w.text)) 2 * w.weight else 0) + (if (DocText.has(body, w.text)) w.weight else 0) }
        }
        val sorted = scores.entries.sortedByDescending { it.value }
        val best = sorted.first()
        val second = sorted.getOrNull(1)?.value ?: 0
        return if (best.value >= MIN_SCORE && best.value > second) best.key else DocKind.OTHER
    }

    private val dateLabels = Regex("(rechnungsdatum|belegdatum|datum|ausgestellt am|vom|date)\\s*:?\\s*", RegexOption.IGNORE_CASE)
    private val untilLabels = Regex("(gültig bis|gueltig bis|ablaufdatum|date of expiry|läuft ab|garantie bis|vertragsende|ende der laufzeit)\\s*:?\\s*", RegexOption.IGNORE_CASE)

    /** Date after a label like „Datum:“, otherwise the first date in the text. */
    private fun dateOf(text: String, dates: List<Pair<Int, Long>>): Long {
        for (m in dateLabels.findAll(text)) dates.firstOrNull { it.first in m.range.last + 1..m.range.last + 3 }?.let { return it.second }
        return dates.firstOrNull()?.second ?: 0
    }

    private fun untilOf(text: String): Long {
        val dates = DocText.dates(text)
        for (m in untilLabels.findAll(text)) dates.firstOrNull { it.first in m.range.last + 1..m.range.last + 3 }?.let { return it.second }
        return 0
    }

    private val company = Regex(
        "\\b(GmbH|AG|KG|KGaA|OHG|e\\.\\s?V\\.|UG|SE|Stadtwerke|Sparkasse|Volksbank|Bank|Versicherung|Finanzamt|Landratsamt|Stadt|Gemeinde|Praxis|Klinik|Krankenkasse)\\b",
    )

    /** Sender: among the first lines the first one naming a company, an office or a practice; otherwise nothing. */
    private fun senderOf(text: String): String =
        text.lineSequence().map { it.trim() }.filter { it.length in 3..60 }.take(15)
            .firstOrNull { company.containsMatchIn(it) }?.let { DocText.clean(it).take(40) } ?: ""
}

/** Text helpers of the recognition and the search. */
internal object DocText {
    /** Lower case, umlauts and ß folded, so „Bärbel“, „Baerbel“ and „Barbel“ are equal. */
    fun fold(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s.lowercase()) when (c) {
            'ä', 'à', 'á', 'â' -> b.append('a')
            'ö', 'ó', 'ò', 'ô' -> b.append('o')
            'ü', 'ú', 'ù', 'û' -> b.append('u')
            'é', 'è', 'ê' -> b.append('e')
            'ß' -> b.append("ss")
            else -> b.append(c)
        }
        return b.toString().replace("ae", "a").replace("oe", "o").replace("ue", "u")
    }

    /** Words of a folded text: letters and digits. */
    fun words(folded: String): List<String> = folded.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** [word] (folded) occurs in [folded]: as a part of a word if it is long, otherwise at the start of a word. */
    fun has(folded: String, word: String): Boolean {
        if (folded.isEmpty()) return false
        if (word.length >= 6) return folded.contains(word)
        var i = folded.indexOf(word)
        while (i >= 0) {
            if (i == 0 || !folded[i - 1].isLetterOrDigit()) return true
            i = folded.indexOf(word, i + 1)
        }
        return false
    }

    fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim().trimEnd(',', ';', ':')

    private val months = mapOf(
        "januar" to 1, "jan" to 1, "january" to 1, "februar" to 2, "feb" to 2, "february" to 2, "marz" to 3, "maerz" to 3, "mär" to 3,
        "march" to 3, "april" to 4, "apr" to 4, "mai" to 5, "may" to 5, "juni" to 6, "june" to 6, "jun" to 6, "juli" to 7, "july" to 7,
        "jul" to 7, "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9, "oktober" to 10, "october" to 10, "okt" to 10,
        "oct" to 10, "november" to 11, "nov" to 11, "dezember" to 12, "december" to 12, "dez" to 12, "dec" to 12,
    )

    private val numeric = Regex("\\b(\\d{1,2})\\.\\s?(\\d{1,2})\\.\\s?(\\d{4}|\\d{2})\\b|\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")
    private val named = Regex("\\b(\\d{1,2})\\.?\\s+([A-Za-zÄäÖöÜü]{3,9})\\.?\\s+(\\d{4})\\b")

    /** Every date in [text] with its position, in order; only real days between 1950 and 2100. */
    fun dates(text: String): List<Pair<Int, Long>> {
        val found = mutableListOf<Pair<Int, Long>>()
        for (m in numeric.findAll(text)) {
            val g = m.groupValues
            val ms = if (g[1].isNotEmpty()) {
                val y = g[3].toInt().let { if (g[3].length == 2) (if (it < 50) 2000 + it else 1900 + it) else it }
                dayMs(y, g[2].toInt(), g[1].toInt())
            } else dayMs(g[4].toInt(), g[5].toInt(), g[6].toInt())
            if (ms != null) found += m.range.first to ms
        }
        for (m in named.findAll(text)) {
            val month = months[m.groupValues[2].lowercase()] ?: months[fold(m.groupValues[2])] ?: continue
            dayMs(m.groupValues[3].toInt(), month, m.groupValues[1].toInt())?.let { found += m.range.first to it }
        }
        return found.sortedBy { it.first }
    }

    /** 12:00 UTC of that day in Unix ms, `null` if it is no real day or outside 1950–2100. */
    fun dayMs(y: Int, m: Int, d: Int): Long? {
        if (y !in 1950..2100 || m !in 1..12 || d < 1) return null
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (d > days[m - 1]) return null
        // Days from civil (Howard Hinnant).
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val epochDays = era * 146097L + doe - 719468
        return epochDays * 86_400_000L + 43_200_000L
    }

    /** Year of a value from [dayMs] (or any Unix ms), in UTC. */
    fun year(ms: Long): Int {
        val z = ms.floorDiv(86_400_000L) + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val m = if (mp < 10) mp + 3 else mp - 9
        return (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
    }

    private val totalLabels = Regex("(gesamtbetrag|rechnungsbetrag|zu zahlen|zahlbetrag|endbetrag|gesamtsumme|summe|gesamt|total|betrag)", RegexOption.IGNORE_CASE)
    private val money = Regex("(?:(€|EUR)\\s?)?(\\d{1,3}(?:[.\\s]\\d{3})*|\\d+),(\\d{2})(?!\\d)(?:\\s?(€|EUR))?")

    /** Amount in cents and currency: after a label like „Gesamtbetrag“ (the last such), otherwise the largest in euros. */
    fun amount(text: String): Pair<Long, String>? {
        fun cents(m: MatchResult) = m.groupValues[2].replace(".", "").replace(" ", "").toLong() * 100 + m.groupValues[3].toLong()
        val all = money.findAll(text).toList()
        if (all.isEmpty()) return null
        val labelled = totalLabels.findAll(text).mapNotNull { l -> all.firstOrNull { it.range.first in l.range.last + 1..l.range.last + 40 } }.lastOrNull()
        if (labelled != null) return cents(labelled) to "EUR"
        val euros = all.filter { it.groupValues[1].isNotEmpty() || it.groupValues[4].isNotEmpty() }
        return euros.maxByOrNull { cents(it) }?.let { cents(it) to "EUR" }
    }
}

/** One file for [DocIndex]: what the app knows of it. [text] is the text read on this device, may be empty. */
@JsExport
class DocItem(
    val id: String,
    val name: String,
    /** Attachment type (`jpg`, `pdf`, `file`). */
    val type: String,
    val added: Long,
    val doc: DocInfo?,
    val text: String,
    /** Title of the file (ADR 0091), empty if none; found like the name. */
    val title: String = "",
)

/**
 * Search over the files of one store (ADR 0067 §2): name and title (ADR 0091), kind, details and read text. Build it again when the files
 * or their texts change; searching itself is quick.
 */
@JsExport
class DocIndex(items: Array<DocItem>) {
    private class Entry(
        val item: DocItem,
        val kind: String,
        val name: List<String>,
        val from: List<String>,
        val text: String,
        /** Distinct words of the text, split once when building, so searching while typing stays quick. */
        val textWords: List<String>,
        val year: Int,
        val topics: Set<String>,
        /** All distinct words of name and text, for synonyms that must match a whole word. */
        val allWords: Set<String>,
    ) {
        /** Meaning of name and text ([useMeaning]), `null` without a model or text. */
        var vector: FloatArray? = null
    }

    private val entries = items.map { i ->
        Entry(
            item = i,
            kind = kindOf(i),
            name = DocText.words(DocText.fold(i.name + " " + i.title)),
            from = DocText.words(DocText.fold(i.doc?.from ?: "")),
            text = DocText.fold(i.text.take(DocRecognizer.MAX_TEXT)),
            textWords = DocText.words(DocText.fold(i.text.take(DocRecognizer.MAX_TEXT))).filter { it.length >= 4 }.distinct(),
            year = DocText.year(i.doc?.date?.takeIf { it > 0 } ?: i.added),
            topics = i.doc?.topics?.toSet().orEmpty(),
            allWords = (DocText.words(DocText.fold(i.name + " " + i.title)) + DocText.words(DocText.fold(i.text.take(DocRecognizer.MAX_TEXT)))).toSet(),
        )
    }

    private var vectors: DocVectors? = null

    /**
     * Use the meaning model (ADR 0078 §5): files whose meaning is close to the search follow below the word matches.
     * Computes a vector per file with text, once; call it right after building the index.
     */
    fun useMeaning(vectors: DocVectors) {
        this.vectors = vectors
        for (e in entries) if (e.item.text.isNotBlank()) e.vector = vectors.embed(e.item.name.substringBeforeLast('.') + "\n" + e.item.text)
    }

    /** Kinds that occur, in the order of [DocKind.all]: the stacks to show. */
    fun kinds(): Array<String> {
        val present = entries.map { it.kind }.toSet()
        return DocKind.all.filter { it in present }.toTypedArray()
    }

    /** Topics that occur among the photos ([DocKind.PHOTO]), in the order of [PhotoTopics.all]: the chips of the gallery. */
    fun topics(): Array<String> {
        val present = entries.filter { it.kind == DocKind.PHOTO }.flatMap { it.topics }.toSet()
        return PhotoTopics.all.filter { it in present }.toTypedArray()
    }

    /**
     * IDs of the files that match [query] within the stack [kind] (empty: all), best first. An empty query lists the
     * stack newest first.
     */
    fun search(query: String, kind: String): Array<String> = searchTopic(query, kind, "")

    /** Like [search], only files showing [topic] (empty: any), ADR 0070. */
    fun searchTopic(query: String, kind: String, topic: String): Array<String> {
        val q = DocQuery.parse(query)
        val candidates = entries
            .filter { kind.isEmpty() || it.kind == kind }
            .filter { topic.isEmpty() || topic in it.topics }
            .filter { q.year == 0 || it.year == q.year }
        val found = candidates
            .mapNotNull { e -> score(e, q)?.let { e to it } }
            .sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenByDescending { it.first.item.added })
            .map { it.first }
        // Close in meaning, but without the words: below the word matches (ADR 0078 §5).
        val model = vectors
        val words = q.terms.joinToString(" ") { it.word }
        val near = if (model == null || words.isBlank()) emptyList() else {
            val qv = model.embed(query)
            if (qv == null) emptyList() else {
                val have = found.toSet()
                candidates.asSequence()
                    .filter { it !in have }
                    .mapNotNull { e -> e.vector?.let { e to model.similarity(qv, it) } }
                    .filter { it.second >= MEANING_SEARCH_MIN }
                    .sortedByDescending { it.second }
                    .map { it.first }
                    .toList()
            }
        }
        return (found + near).map { it.item.id }.toTypedArray()
    }

    /** `null` if a word of the query is missing. */
    private fun score(e: Entry, q: DocQuery): Int? {
        var total = 0
        for (t in q.terms) {
            val s = when {
                matches(e.name, t.word) -> 3
                matches(e.from, t.word) -> 2
                t.word.length >= 3 && DocText.has(e.text, t.word) -> 1
                t.word.length >= 5 && fuzzy(e.textWords, t.word) -> 1
                // Synonyms (ADR 0078 §4): short ones only as a whole word, long ones also inside a compound.
                t.variants.any { v -> v != t.word && (v in e.allWords || (v.length >= 6 && DocText.has(e.text, v))) } -> 1
                else -> 0
            }
            when {
                s > 0 -> total += s
                t.topic != null && t.topic in e.topics -> total += 2
                t.kind != null && e.kind == t.kind -> total += 1
                else -> return null
            }
        }
        return total
    }

    private fun matches(words: List<String>, token: String): Boolean =
        words.any { it.startsWith(token) || (token.length >= 5 && it.contains(token)) } || (token.length >= 5 && fuzzy(words, token))

    /** Some word starts with [token] up to one typo (ADR 0067 §2: from five letters). */
    private fun fuzzy(words: List<String>, token: String): Boolean =
        words.any { w -> w.length >= token.length - 1 && (token.length - 1..token.length + 1).any { n -> n <= w.length && editsAtMostOne(w.take(n), token) } }

    /** At most one insertion, deletion, change or swap of two neighbouring letters. */
    private fun editsAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length == b.length) {
            val diff = a.indices.filter { a[it] != b[it] }
            if (diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]]) return true
        }
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    companion object {
        /** A file must be at least this close in meaning to follow the word matches (measured, ADR 0078). */
        internal const val MEANING_SEARCH_MIN = 0.30f

        /** Stack of a file: its recognized kind, otherwise photo for images, otherwise other. */
        fun kindOf(i: DocItem): String = i.doc?.let { DocKind.known(it.kind) } ?: if (i.type == "jpg") DocKind.PHOTO else DocKind.OTHER
    }
}

/** A search split into words; a year filters by date, a word naming a stack may also match by kind. */
internal class DocQuery(val terms: List<Term>, val year: Int) {
    /** A word of the query; it also matches files of the stack [kind] or photos of the [topic] it names. */
    class Term(val word: String, val kind: String?, val topic: String? = null) {
        /** The word and its synonyms ([DocWords]). */
        val variants: Set<String> = DocWords.variants(word)
    }

    companion object {
        private val kindWords: Map<String, String> = buildMap {
            fun add(kind: String, vararg w: String) = w.forEach { put(DocText.fold(it), kind) }
            add(DocKind.INVOICE, "rechnung", "rechnungen", "invoice", "invoices")
            add(DocKind.CONTRACT, "vertrag", "verträge", "vertrage", "contract", "contracts")
            add(DocKind.ID, "ausweis", "ausweise", "pass", "reisepass", "führerschein", "passport")
            add(DocKind.OFFICIAL, "bescheid", "bescheide", "amt", "behörde", "official")
            add(DocKind.INSURANCE, "versicherung", "versicherungen", "police", "insurance")
            add(DocKind.BANK, "bank", "kontoauszug", "kontoauszüge", "konto")
            add(DocKind.PAYSLIP, "gehalt", "lohn", "gehaltsabrechnung", "lohnabrechnung", "payslip", "payslips")
            add(DocKind.RECEIPT, "kassenbon", "kassenbons", "bon", "quittung", "garantie", "receipt", "receipts", "warranty")
            add(DocKind.HEALTH, "gesundheit", "arzt", "befund", "befunde", "rezept", "impfpass", "impfausweis", "impfung", "health", "medical")
            add(DocKind.CERTIFICATE, "urkunde", "urkunden", "zeugnis", "zeugnisse", "zertifikat", "certificate", "certificates")
            add(DocKind.PROVISION, "vorsorge", "vollmacht", "vollmachten", "patientenverfügung", "testament", "provision")
            add(DocKind.TAX, "steuer", "steuern", "finanzamt", "tax")
            add(DocKind.PHOTO, "foto", "fotos", "bild", "bilder", "photo", "photos")
        }

        fun parse(query: String): DocQuery {
            var year = 0
            val terms = mutableListOf<Term>()
            for (word in DocText.words(DocText.fold(query))) {
                if (word.length == 4 && word.all { it.isDigit() } && word.toInt() in 1950..2100) {
                    year = word.toInt()
                    continue
                }
                val kind = kindWords[word]
                val topic = PhotoTopics.topicOf(word)
                if (kind != null || topic != null) {
                    terms += Term(word, kind, topic)
                    continue
                }
                // „Stromrechnung“: a word ending in a long stack word is that stack plus the rest.
                val tail = kindWords.keys.filter { it.length >= 6 && word.length - it.length >= 3 && word.endsWith(it) }.maxByOrNull { it.length }
                val parts = if (tail == null) DocWords.split(word) else null
                when {
                    tail != null -> {
                        terms += Term(tail, kindWords.getValue(tail))
                        terms += Term(word.dropLast(tail.length), null)
                    }
                    // „Stromabschlag“: known word parts, each with its synonyms (ADR 0078 §4).
                    parts != null -> parts.forEach { terms += Term(it, kindWords[it]) }
                    else -> terms += Term(word, null)
                }
            }
            return DocQuery(terms, year)
        }
    }
}

/**
 * What a photo shows (ADR 0070): a short, fixed list of topics. The app recognizes them on the device (Android: an
 * ImageNet model plus face detection, iOS: Vision); this object maps both to the same topics, so devices agree.
 * Only that people are shown, never who: no face features, no comparison between photos.
 */
@JsExport
object PhotoTopics {
    /** Version of the mapping; a higher one makes devices look at photos again ([DocInfo.seen]). */
    const val VERSION = 1

    const val PEOPLE = "people"
    const val ANIMALS = "animals"
    const val NATURE = "nature"
    const val FOOD = "food"
    const val BUILDINGS = "buildings"
    const val VEHICLES = "vehicles"
    const val HOME = "home"
    const val SPORT = "sport"
    const val PARTY = "party"

    /** All topics in the order the chips are shown. */
    val all: Array<String> = arrayOf(PEOPLE, ANIMALS, NATURE, FOOD, BUILDINGS, VEHICLES, HOME, SPORT, PARTY)

    /** Below this score a label of the model does not count. */
    const val MIN_SCORE = 0.3f

    private fun ranges(vararg r: IntRange): Set<Int> = r.flatMap { it.toList() }.toSet()

    /** Indices of the 1001 outputs of the quantized MobileNet v1 (0 = background), by topic. */
    private val imageNet: Map<String, Set<Int>> = mapOf(
        ANIMALS to ranges(1..398),
        FOOD to ranges(924..958, 960..968, 970..970, 929..930),
        NATURE to ranges(971..981, 985..999),
        PEOPLE to setOf(982, 983, 984),
        BUILDINGS to setOf(
            416, 425, 426, 438, 443, 450, 455, 461, 468, 484, 498, 499, 501, 526, 537, 539, 563, 581, 583, 625, 650, 664,
            669, 683, 699, 719, 728, 744, 763, 789, 822, 833, 840, 854, 859, 861, 866, 874, 889, 901, 916,
        ),
        VEHICLES to setOf(
            404, 405, 406, 408, 409, 437, 445, 451, 467, 469, 473, 476, 480, 485, 511, 512, 538, 548, 555, 556, 562, 566,
            570, 574, 576, 577, 587, 596, 604, 610, 613, 626, 628, 629, 655, 657, 661, 662, 666, 671, 672, 676, 691, 706,
            718, 735, 752, 758, 780, 781, 803, 804, 813, 815, 818, 821, 830, 834, 848, 865, 867, 868, 871, 872, 875, 881,
            896, 914, 915,
        ),
        HOME to setOf(
            422, 432, 436, 454, 493, 494, 496, 517, 521, 527, 533, 535, 540, 546, 549, 557, 565, 599, 620, 652, 722, 751,
            754, 761, 766, 790, 795, 800, 828, 832, 847, 852, 860, 862, 883, 895, 897, 898, 905, 906,
        ),
        SPORT to setOf(417, 423, 430, 431, 523, 544, 561, 575, 603, 703, 723, 747, 748, 753, 769, 782, 796, 806, 853, 891, 982),
        PARTY to setOf(418, 471, 477, 497, 608, 646, 724),
    )

    /** Topics of an output index of the ImageNet model (Android). */
    fun fromImageNet(index: Int): Array<String> = all.filter { imageNet[it]?.contains(index) == true }.toTypedArray()

    /** Words in the identifiers of Apple Vision (`VNClassifyImageRequest`, e.g. `dog`, `beach`, `birthday_cake`). */
    private val vision: Map<String, Set<String>> = mapOf(
        PEOPLE to setOf("people", "person", "adult", "child", "baby", "teen", "crowd", "portrait", "selfie"),
        ANIMALS to setOf(
            "animal", "mammal", "dog", "canine", "cat", "feline", "bird", "fish", "horse", "cow", "cattle", "sheep",
            "insect", "reptile", "rabbit", "pet",
        ),
        NATURE to setOf(
            "nature", "landscape", "beach", "shore", "sea", "ocean", "lake", "river", "mountain", "hill", "forest", "tree",
            "flower", "plant", "garden", "sky", "sunset", "sunrise", "snow", "waterfall", "field", "desert", "canyon",
        ),
        FOOD to setOf("food", "meal", "dish", "fruit", "vegetable", "dessert", "cake", "bread", "pizza", "drink", "coffee", "beverage"),
        BUILDINGS to setOf(
            "building", "structure", "architecture", "house", "church", "castle", "tower", "bridge", "city", "cityscape",
            "skyscraper", "monument", "ruins", "street",
        ),
        VEHICLES to setOf("vehicle", "car", "automobile", "bicycle", "bike", "motorcycle", "bus", "train", "aircraft", "airplane", "boat", "ship", "truck"),
        HOME to setOf("furniture", "sofa", "couch", "bed", "kitchen", "interior_room", "living_room", "bedroom", "bathroom", "table"),
        SPORT to setOf("sport", "sports", "ball", "soccer", "football", "tennis", "basketball", "ski", "skiing", "swimming", "gym"),
        PARTY to setOf("celebration", "party", "birthday", "balloon", "christmas", "fireworks", "wedding", "candle"),
    )

    /** Topics of a Vision identifier: its words (split at `_`) or the whole identifier. */
    fun fromVision(identifier: String): Array<String> {
        val id = identifier.lowercase()
        val words = id.split('_').toSet() + id
        return all.filter { t -> vision[t].orEmpty().any { it in words } }.toTypedArray()
    }

    /** Words of a search that mean a topic (German and English, folded like [DocText.fold]). */
    private val searchWords: Map<String, String> = buildMap {
        fun add(topic: String, vararg w: String) = w.forEach { put(DocText.fold(it), topic) }
        add(PEOPLE, "menschen", "mensch", "leute", "person", "personen", "familie", "kinder", "kind", "baby", "freunde", "people", "family", "kids")
        add(ANIMALS, "tiere", "tier", "hund", "hunde", "katze", "katzen", "vogel", "vögel", "pferd", "pferde", "fisch", "animals", "animal", "dog", "cat", "bird")
        add(NATURE, "natur", "landschaft", "strand", "meer", "berg", "berge", "wald", "blume", "blumen", "baum", "bäume", "garten", "nature", "beach", "mountain", "flower", "forest")
        add(FOOD, "essen", "mahlzeit", "kuchen", "torte", "pizza", "obst", "gemüse", "food", "cake", "fruit")
        add(BUILDINGS, "gebäude", "haus", "häuser", "kirche", "burg", "schloss", "stadt", "brücke", "building", "church", "castle", "city")
        add(VEHICLES, "fahrzeuge", "fahrzeug", "auto", "autos", "fahrrad", "zug", "bahn", "flugzeug", "boot", "schiff", "car", "bike", "train", "plane", "boat")
        add(HOME, "zuhause", "wohnung", "wohnzimmer", "küche", "sofa", "bett", "home", "kitchen")
        add(SPORT, "sport", "fußball", "tennis", "ski", "sports", "football")
        add(PARTY, "feiern", "feier", "party", "geburtstag", "fest", "weihnachten", "hochzeit", "birthday", "christmas", "wedding")
    }

    /** The topic a (folded) search word names, if any. */
    fun topicOf(word: String): String? = searchWords[word]
}

package app.anynear.anmp.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.CborLabel
import kotlin.js.JsExport

/*
 * Titles of files in the store and their order (ADR 0091). A title is put together on the device from fixed parts:
 * what it is, who or what it is about, and when. The meaning model (ADR 0078) only chooses among the parts; it writes
 * no text. Like the document details the title travels only inside the sealed store entry (field 13).
 */

/**
 * Title of a file (ADR 0091). [byPerson]: set by a person, never replaced automatically. [rules]: version of
 * [FileTitles] that made it, 0 for a title set by hand.
 */
@JsExport
class FileTitle(val text: String, val byPerson: Boolean, val rules: Int) {
    /** Same text and origin (ignores [rules]). */
    fun sameAs(other: FileTitle?): Boolean = other != null && text == other.text && byPerson == other.byPerson

    internal fun toWire() = StoreTitle(text, if (byPerson) 1 else 0, rules)

    companion object {
        /** A title typed by a person; cleaned and cut like a made one. Empty text gives the title back to anynear. */
        fun chosen(text: String): FileTitle = FileTitle(FileTitles.tidy(text), true, 0)
    }
}

/** Field 13 of a store entry: `{0 text, ? 1 by, ? 2 rules}`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class StoreTitle(
    @CborLabel(0) val text: String,
    @CborLabel(1) val by: Int = 0,
    @CborLabel(2) val rules: Int = 0,
) {
    /** An empty text counts as no title. */
    fun toInfo(): FileTitle? = text.trim().takeIf { it.isNotEmpty() }?.let { FileTitle(it.take(FileTitles.MAX), by == 1, rules) }
}

/**
 * Makes the title of a file (ADR 0091 §2): kind or label · sender or subject · time, at most [MAX] characters, without
 * extension, numbers or addresses. Parts it is not sure about are left out.
 */
@JsExport
object FileTitles {
    /** Version of these rules; a device with a higher one makes the titles not set by hand anew. */
    const val RULES = 2

    /** Longest title. */
    const val MAX = 40

    /** [title] should be made (again): none yet, or made by older rules and not set by a person. */
    fun due(title: FileTitle?): Boolean = title == null || (!title.byPerson && title.rules < RULES)

    /** Title shown for a file: its [title] if it has one, otherwise its [name]. */
    fun shown(title: FileTitle?, name: String): String = title?.text?.takeIf { it.isNotBlank() } ?: name

    /**
     * Title of a file named [name] of [type] (`jpg`, `pdf`, `file`, …) with the [text] read from it (may be empty),
     * its recognized [doc] and when it was [added] (Unix ms). [lang]: language of the app (`de…` German, otherwise
     * English). [vectors]: the meaning model, if loaded; without it only the rules choose.
     */
    fun make(name: String, type: String, text: String, doc: DocInfo?, added: Long, lang: String, vectors: DocVectors?): FileTitle {
        val de = lang.lowercase().startsWith("de")
        val body = text.take(DocRecognizer.MAX_TEXT)
        val letters = body.count { it.isLetter() }
        val image = type == "jpg"
        val kind = doc?.kind ?: if (image && letters < DOCUMENT_LETTERS) DocKind.PHOTO else DocKind.OTHER
        val made = when {
            kind == DocKind.PHOTO || (image && letters < DOCUMENT_LETTERS) -> photo(name, doc, added, de)
            letters < DOCUMENT_LETTERS -> plainFile(name, image, added, de)
            else -> document(name, body, doc, kind, added, de, vectors)
        }
        return FileTitle(made, false, RULES)
    }

    /** Typed text cleaned like a made title: one line, no extension of the file name it may come from, at most [MAX]. */
    fun tidy(text: String): String = cut(text.replace(Regex("\\s+"), " ").trim())

    // --- Parts ----------------------------------------------------------------------------------------------------

    private fun photo(name: String, doc: DocInfo?, added: Long, de: Boolean): String {
        val topic = doc?.topics?.firstOrNull()?.let { topicNames[it]?.let { n -> if (de) n.first else n.second } }
        return join(if (de) "Foto" else "Photo", topic ?: "", day(added, de))
    }

    private fun plainFile(name: String, image: Boolean, added: Long, de: Boolean): String {
        val clean = cleanName(name)
        if (clean.isNotEmpty() && !generic.matches(DocText.fold(name.substringBeforeLast('.')))) return cut(clean)
        return join(if (image) (if (de) "Foto" else "Photo") else (if (de) "Dokument" else "Document"), "", if (image) day(added, de) else month(added, de))
    }

    private fun document(name: String, text: String, doc: DocInfo?, kind: String, added: Long, de: Boolean, vectors: DocVectors?): String {
        val label = labelOf(name, text, kind, vectors)
        val named = label?.let { if (de) it.de else it.en } ?: kindNames[kind]?.let { if (de) it.first else it.second }
        val labelText = named ?: if (de) "Dokument" else "Document"
        // The label in both languages and its words: a subject that starts with one of them does not repeat it.
        val labelWords = listOfNotNull(labelText, label?.de, label?.en).map { DocText.fold(it) } + label?.folded.orEmpty()
        val subject = withoutLabel(doc?.from?.takeIf { it.isNotBlank() }?.let { scrub(it) } ?: subjectOf(text, vectors), labelWords)
        // Nothing says what it is, but a line does: the line alone („Herzlichen Glückwunsch …“), not „Dokument …“.
        val first = if (named == null && subject.isNotEmpty()) "" else labelText
        val until = doc?.until?.takeIf { it > 0 && kind in UNTIL_KINDS }
        val day = doc?.date?.takeIf { it > 0 } ?: DocText.dates(text).firstOrNull()?.second ?: added
        val time = if (until != null) (if (de) "gültig bis " else "valid until ") + DocText.year(until) else month(day, de)
        val short = if (until != null) (if (de) "bis " else "until ") + DocText.year(until) else month(day, de, short = true)
        return join(first, subject, time, short)
    }

    /** [subject] without the label it starts with („Kündigung der Mitgliedschaft“ → „der Mitgliedschaft“); empty if it says no more. */
    private fun withoutLabel(subject: String, labels: List<String>): String {
        val words = subject.split(' ').filter { it.isNotEmpty() }
        val first = words.firstOrNull()?.let { DocText.fold(it) }
        val rest = if (first != null && labels.any { it.isNotEmpty() && first.startsWith(it) }) words.drop(1) else words
        val s = rest.joinToString(" ")
        return if (s.isEmpty() || labels.any { it.contains(DocText.fold(s)) }) "" else s
    }

    /** Label + subject + time; the subject is shortened word by word, then left out, so the whole fits into [MAX]. */
    private fun join(label: String, subject: String, time: String, shortTime: String = time): String {
        var words = trimSmall(subject.split(' ').filter { it.isNotEmpty() })
        fun text(w: List<String>, t: String) = listOf(label, w.joinToString(" "), t).filter { it.isNotEmpty() }.joinToString(" ")
        fun fits(w: List<String>) = text(w, time).length <= MAX || text(w, shortTime).length <= MAX
        // Too long: first without the small words inside, then the short time („Dez. 2025“), then shorter from the end.
        if (!fits(words)) words = trimSmall(words.filter { it.lowercase() !in small })
        while (true) {
            val full = text(words, time)
            if (full.length <= MAX) return cut(capital(full))
            val brief = text(words, shortTime)
            if (brief.length <= MAX || words.isEmpty()) return cut(capital(brief))
            words = trimSmall(words.dropLast(1))
        }
    }

    /** Without articles and small words at the start and end („der Mitgliedschaft“ → „Mitgliedschaft“). */
    private fun trimSmall(words: List<String>): List<String> =
        words.dropWhile { it.lowercase() in small }.dropLastWhile { it.lowercase() in small || it.length == 1 || it.none(Char::isLetter) }

    private val small = setOf(
        "der", "die", "das", "des", "dem", "den", "ein", "eine", "einer", "eines", "einem", "einen", "und", "oder", "für",
        "von", "vom", "zum", "zur", "zu", "im", "in", "am", "an", "auf", "mit", "über", "bei", "nach", "aus", "ihre", "ihr",
        "ihrer", "unsere", "the", "a", "an", "of", "for", "and", "or", "to", "on", "at", "by", "with", "your", "our",
    )

    private fun capital(s: String) = s.replaceFirstChar { it.uppercaseChar() }

    /** At most [MAX] characters, cut at a word if possible. */
    private fun cut(s: String): String {
        if (s.length <= MAX) return s
        val head = s.take(MAX)
        val space = head.lastIndexOf(' ')
        return (if (space >= MAX / 2) head.take(space) else head).trimEnd(' ', ',', '-', '·')
    }

    /** File name without extension, `_`, `-` and `.` as spaces, numbers of five digits or more removed. */
    internal fun cleanName(name: String): String =
        scrub(name.substringBeforeLast('.').replace(Regex("[_.]+"), " ").replace(Regex("\\s-\\s|-{2,}"), " "))

    /** Without long numbers, IBANs, e-mail and web addresses, `/` and `_`; one line. */
    internal fun scrub(s: String): String = s
        .replace(Regex("\\b[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){3,7}(?:\\s?[A-Z0-9]{1,3})?\\b"), " ")
        .replace(Regex("\\S+@\\S+|https?://\\S+|www\\.\\S+"), " ")
        .replace(Regex("\\S*\\d{5,}\\S*"), " ")
        .replace(Regex("[/_|\\\\]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim().trim(',', ';', ':', '-', '.', ' ')

    /** Names like `IMG_2041`, `scan_0003`, `Dokument (7)`, `PXL_20250312_…`: they say nothing (folded, no extension). */
    private val generic = Regex(
        "^(img|image|dsc|dscn|dscf|pxl|mvimg|photo|foto|bild|picture|scan|scanned|screenshot|bildschirmfoto|dokument|document|doc|unbenannt|untitled|datei|file|download|whatsapp image|signal|telegram|new|neu)?[\\s_\\-.()0-9]*$",
    )

    // --- Label ----------------------------------------------------------------------------------------------------

    private class Label(val de: String, val en: String, val kind: String?, val words: List<String>, val meaning: String) {
        val folded = words.map { DocText.fold(it) }
    }

    private fun l(de: String, en: String, kind: String?, words: String, meaning: String = "") =
        Label(de, en, kind, words.split(',').map { it.trim() }.filter { it.isNotEmpty() }, meaning.ifEmpty { "$de $en ${words.replace(',', ' ')}" })

    /**
     * The labels a title can start with (ADR 0091 §2): the words that name them in a document, and what they are about
     * for the meaning model. A label sets no kind (ADR 0074 stays); [Label.kind] only keeps a recognized important
     * document to its own labels. More specific labels come first.
     */
    private val labels: List<Label> = listOf(
        l("Personalausweis", "ID card", DocKind.ID, "personalausweis,identity card,carte d'identite"),
        l("Reisepass", "Passport", DocKind.ID, "reisepass,passport"),
        l("Führerschein", "Driving licence", DocKind.ID, "führerschein,fuehrerschein,driving licence,driver's license"),
        l("Aufenthaltstitel", "Residence permit", DocKind.ID, "aufenthaltstitel,residence permit"),
        l("Impfpass", "Vaccination record", DocKind.HEALTH, "impfpass,impfausweis,impfbescheinigung,vaccination"),
        l("Arztbrief", "Medical letter", DocKind.HEALTH, "arztbrief,entlassbrief,entlassungsbericht,befundbericht,medical report"),
        l("Befund", "Findings", DocKind.HEALTH, "befund,laborbefund,diagnose,findings"),
        l("Rezept", "Prescription", DocKind.HEALTH, "rezept,verordnung,prescription"),
        l("Krankmeldung", "Sick note", DocKind.HEALTH, "arbeitsunfähigkeitsbescheinigung,arbeitsunfaehigkeit,krankmeldung,sick note"),
        l("Mutterpass", "Maternity record", DocKind.HEALTH, "mutterpass"),
        l("Geburtsurkunde", "Birth certificate", DocKind.CERTIFICATE, "geburtsurkunde,birth certificate"),
        l("Heiratsurkunde", "Marriage certificate", DocKind.CERTIFICATE, "heiratsurkunde,eheurkunde,marriage certificate"),
        l("Sterbeurkunde", "Death certificate", DocKind.CERTIFICATE, "sterbeurkunde,death certificate"),
        l("Erbschein", "Certificate of inheritance", DocKind.CERTIFICATE, "erbschein"),
        l("Arbeitszeugnis", "Reference", DocKind.CERTIFICATE, "arbeitszeugnis,zwischenzeugnis"),
        l("Zeugnis", "Certificate", DocKind.CERTIFICATE, "zeugnis,abschlusszeugnis,diploma"),
        l("Urkunde", "Certificate", DocKind.CERTIFICATE, "urkunde,certificate"),
        l("Vorsorgevollmacht", "Power of attorney", DocKind.PROVISION, "vorsorgevollmacht,generalvollmacht,power of attorney"),
        l("Patientenverfügung", "Living will", DocKind.PROVISION, "patientenverfügung,patientenverfuegung,living will"),
        l("Betreuungsverfügung", "Care directive", DocKind.PROVISION, "betreuungsverfügung,betreuungsverfuegung"),
        l("Testament", "Will", DocKind.PROVISION, "testament,letzter wille,last will"),
        l("Vollmacht", "Power of attorney", DocKind.PROVISION, "vollmacht"),
        l("Versicherungsschein", "Insurance policy", DocKind.INSURANCE, "versicherungsschein,police,insurance policy"),
        l("Versicherung", "Insurance", DocKind.INSURANCE, "versicherung,insurance"),
        l("Rechnung", "Invoice", null, "rechnung,invoice,abrechnung"),
        l("Mahnung", "Reminder", null, "mahnung,zahlungserinnerung,payment reminder"),
        l("Quittung", "Receipt", null, "quittung,kassenbon,kassenbeleg,receipt"),
        l("Kontoauszug", "Bank statement", null, "kontoauszug,bank statement"),
        l("Kreditvertrag", "Loan agreement", null, "kreditvertrag,darlehensvertrag,loan agreement"),
        l("Lohnabrechnung", "Payslip", null, "lohnabrechnung,gehaltsabrechnung,entgeltabrechnung,verdienstabrechnung,payslip"),
        l("Steuerbescheid", "Tax assessment", null, "steuerbescheid,einkommensteuerbescheid,tax assessment"),
        l("Steuererklärung", "Tax return", null, "steuererklärung,steuererklaerung,tax return"),
        l("Bescheid", "Notice", null, "bescheid,notice"),
        l("Mietvertrag", "Tenancy agreement", null, "mietvertrag,tenancy agreement,lease"),
        l("Arbeitsvertrag", "Employment contract", null, "arbeitsvertrag,employment contract"),
        l("Kaufvertrag", "Purchase agreement", null, "kaufvertrag,purchase agreement"),
        l("Vertrag", "Contract", null, "vertrag,contract,agreement"),
        l("Kündigung", "Cancellation", null, "kündigung,kuendigung,cancellation,termination"),
        l("Kündigungsbestätigung", "Cancellation confirmation", null, "kündigungsbestätigung,kuendigungsbestaetigung"),
        l("Auftragsbestätigung", "Order confirmation", null, "auftragsbestätigung,bestellbestätigung,order confirmation"),
        l("Angebot", "Quote", null, "angebot,kostenvoranschlag,quote,quotation"),
        l("Lieferschein", "Delivery note", null, "lieferschein,delivery note"),
        l("Garantie", "Warranty", null, "garantie,garantieschein,warranty,guarantee"),
        l("Bedienungsanleitung", "Manual", null, "bedienungsanleitung,gebrauchsanweisung,betriebsanleitung,user manual,manual"),
        l("Bestätigung", "Confirmation", null, "bestätigung,bestaetigung,confirmation"),
        l("Bescheinigung", "Certificate", null, "bescheinigung,nachweis"),
        l("Antrag", "Application", null, "antrag,application form"),
        l("Ticket", "Ticket", null, "ticket,fahrkarte,eintrittskarte,boarding pass,bordkarte"),
        l("Buchungsbestätigung", "Booking confirmation", null, "buchungsbestätigung,reservierungsbestätigung,booking confirmation"),
        l("Nebenkostenabrechnung", "Service charge statement", null, "nebenkostenabrechnung,betriebskostenabrechnung"),
        l("Jahresabrechnung", "Annual statement", null, "jahresabrechnung,jahresverbrauchsabrechnung,annual statement"),
        l("Abschlag", "Instalment", null, "abschlagsplan,abschlagszahlung,abschlag"),
        l("Rentenbescheid", "Pension notice", null, "rentenbescheid,renteninformation,rentenauskunft"),
        l("Zulassung", "Vehicle registration", null, "zulassungsbescheinigung,fahrzeugschein,fahrzeugbrief"),
        l("Protokoll", "Minutes", null, "protokoll,niederschrift,minutes"),
        l("Lebenslauf", "CV", null, "lebenslauf,curriculum vitae,resume"),
        l("Bewerbung", "Application", null, "bewerbung"),
        l("Brief", "Letter", null, "sehr geehrte,dear sir,dear madam"),
    )

    private class Vectors(val model: DocVectors, val labels: List<Pair<Label, FloatArray>>)

    private var cached: Vectors? = null

    private fun labelVectors(model: DocVectors): List<Pair<Label, FloatArray>> {
        cached?.takeIf { it.model === model }?.let { return it.labels }
        val made = labels.mapNotNull { lb -> model.embed(lb.meaning)?.let { lb to it } }
        cached = Vectors(model, made)
        return made
    }

    /**
     * Label of a document: the label whose word comes first in the name or near the top of the text; otherwise the
     * one closest in meaning, if clearly closest. A recognized important document keeps to its own labels.
     */
    private fun labelOf(name: String, text: String, kind: String, vectors: DocVectors?): Label? {
        val allowed = if (DocKind.isImportant(kind)) labels.filter { it.kind == kind } else labels
        val head = DocText.fold(name.substringBeforeLast('.') + "\n" + text.lineSequence().take(HEAD_LINES).filterNot { listItem.containsMatchIn(it) }.joinToString("\n"))
        val byWord = allowed.mapNotNull { lb -> lb.folded.mapNotNull { w -> firstWord(head, w) }.minOrNull()?.let { lb to it } }
            .minWithOrNull(compareBy({ it.second }, { allowed.indexOf(it.first) }))?.first
        if (byWord != null) return byWord
        val model = vectors ?: return null
        val v = model.embed(name.substringBeforeLast('.') + "\n" + text) ?: return null
        val ranked = labelVectors(model).filter { it.first in allowed }.map { it.first to model.similarity(v, it.second) }.sortedByDescending { it.second }
        val first = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0f
        return first.first.takeIf { first.second >= LABEL_MIN && first.second - second >= LABEL_LEAD }
    }

    /** Position of [word] as a whole word (or the start of a compound) in [folded], `null` if absent. */
    private fun firstWord(folded: String, word: String): Int? {
        var i = folded.indexOf(word)
        while (i >= 0) {
            if (i == 0 || !folded[i - 1].isLetterOrDigit()) return i
            i = folded.indexOf(word, i + 1)
        }
        return null
    }

    // --- Subject --------------------------------------------------------------------------------------------------

    private val subjectLead = Regex("^\\s*(betreff|betr\\.|betrifft|subject|re|aw|wg|fw|fwd|thema)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val greeting = Regex("^(sehr geehrte|liebe|lieber|hallo|guten tag|dear|hello|hi|mit freundlichen|freundliche gr|viele gr|best regards|kind regards|seite|page|tel|telefon|fax|e-mail|email|iban|bic|ust|steuer-?nr|kundennummer|rechnungsnummer|datum|date)\\b", RegexOption.IGNORE_CASE)
    private val sender = Regex(
        "\\b(GmbH|AG|KG|KGaA|OHG|e\\.\\s?V\\.|UG|SE|Stadtwerke|Sparkasse|Volksbank|Bank|Versicherung|Finanzamt|Landratsamt|Stadt|Gemeinde|Praxis|Klinik|Krankenkasse|Amt|Schule|Verein)\\b",
    )
    /** A numbered or bulleted list item („2. Treffpunkt …“, „a) …“, „- Reisepass …“): body of the text, no heading. */
    private val listItem = Regex("^\\s*(\\d{1,2}[.)]|[a-z][)]|[-–•*·])\\s+\\S")

    private val address = Regex("\\b\\d{5}\\s+\\p{L}|\\p{L}+(straße|strasse|str\\.|weg|allee|platz|gasse|ring)\\s*\\d", RegexOption.IGNORE_CASE)

    /** A line from the top of the text that says what the document is about; empty if none is clear. */
    private fun subjectOf(text: String, vectors: DocVectors?): String {
        val lines = text.lineSequence().map { it.trim() }.take(HEAD_LINES * 2).toList()
        lines.firstOrNull { subjectLead.containsMatchIn(it) }?.let { line ->
            scrub(line.replace(subjectLead, "")).takeIf { it.count(Char::isLetter) >= 3 }?.let { return it }
        }
        val candidates = lines.take(HEAD_LINES).withIndex().filter { !it.value.trimEnd().endsWith(',') && !listItem.containsMatchIn(it.value) }.map { (i, s) -> i to scrub(s) }.filter { (_, s) ->
            s.length in 3..60 && s.count(Char::isLetter) >= 3 && s.count(Char::isLetter) * 2 >= s.length &&
                s.split(' ').size <= LINE_WORDS && !greeting.containsMatchIn(s) && !address.containsMatchIn(s) && DocText.dates(s).isEmpty()
        }
        if (candidates.isEmpty()) return ""
        // A sender near the top (company, office, practice) says most about a letter.
        candidates.firstOrNull { (_, s) -> sender.containsMatchIn(s) }?.let { return it.second }
        val model = vectors ?: return ""
        val whole = model.embed(text) ?: return ""
        val best = candidates.mapNotNull { (i, s) ->
            model.embed(s)?.let { s to model.similarity(whole, it) + LINE_EARLY * (HEAD_LINES - i) / HEAD_LINES }
        }.maxByOrNull { it.second } ?: return ""
        return best.first.takeIf { best.second >= LINE_MIN } ?: ""
    }

    // --- Time -----------------------------------------------------------------------------------------------------

    private val monthsDe = arrayOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
    private val monthsEn = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** „März 2025“ / „March 2025“, [short] „Dez. 2025“ / „Dec 2025“, in UTC. */
    internal fun month(ms: Long, de: Boolean, short: Boolean = false): String {
        val (y, m, _) = civil(ms)
        val name = (if (de) monthsDe else monthsEn)[m - 1]
        val shown = if (!short || name.length <= 4) name else if (de) name.take(3) + "." else name.take(3)
        return "$shown $y"
    }

    /** „12. März 2025“ / „12 March 2025“, in UTC. */
    internal fun day(ms: Long, de: Boolean): String {
        val (y, m, d) = civil(ms)
        return if (de) "$d. ${monthsDe[m - 1]} $y" else "$d ${monthsEn[m - 1]} $y"
    }

    /** Year, month, day of Unix ms in UTC (Howard Hinnant). */
    private fun civil(ms: Long): Triple<Int, Int, Int> {
        val z = ms.floorDiv(86_400_000L) + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((yoe + era * 400 + if (m <= 2) 1 else 0).toInt(), m, d)
    }

    // --- Names ----------------------------------------------------------------------------------------------------

    private val kindNames: Map<String, Pair<String, String>> = mapOf(
        DocKind.ID to ("Ausweis" to "ID"),
        DocKind.HEALTH to ("Gesundheit" to "Health"),
        DocKind.CERTIFICATE to ("Urkunde" to "Certificate"),
        DocKind.PROVISION to ("Vorsorge" to "Provision"),
        DocKind.INSURANCE to ("Versicherung" to "Insurance"),
        DocKind.INVOICE to ("Rechnung" to "Invoice"),
        DocKind.CONTRACT to ("Vertrag" to "Contract"),
        DocKind.OFFICIAL to ("Bescheid" to "Notice"),
        DocKind.BANK to ("Bank" to "Bank"),
        DocKind.PAYSLIP to ("Lohnabrechnung" to "Payslip"),
        DocKind.RECEIPT to ("Quittung" to "Receipt"),
        DocKind.TAX to ("Steuer" to "Tax"),
    )

    private val topicNames: Map<String, Pair<String, String>> = mapOf(
        "people" to ("Menschen" to "People"),
        "animals" to ("Tiere" to "Animals"),
        "nature" to ("Natur" to "Nature"),
        "food" to ("Essen" to "Food"),
        "buildings" to ("Gebäude" to "Buildings"),
        "vehicles" to ("Fahrzeuge" to "Vehicles"),
        "home" to ("Zuhause" to "Home"),
        "sport" to ("Sport" to "Sport"),
        "party" to ("Feier" to "Party"),
    )

    private val UNTIL_KINDS = setOf(DocKind.ID, DocKind.INSURANCE, DocKind.CERTIFICATE)

    /** Fewer letters than this: no document, the title comes from the name or the photo. */
    private const val DOCUMENT_LETTERS = 40

    /** Lines from the top where label and subject are looked for. */
    private const val HEAD_LINES = 15

    /** A label chosen by meaning must be this close and lead the next by [LABEL_LEAD] (like the kinds, ADR 0078). */
    private const val LABEL_MIN = 0.40f
    private const val LABEL_LEAD = 0.08f

    /** A line must be this close to the whole text (with its bonus for being early) to become the subject. */
    private const val LINE_MIN = 0.45f
    private const val LINE_EARLY = 0.10f

    /** A line with more words is a sentence of the body, not a heading. */
    private const val LINE_WORDS = 8
}

/**
 * Order of titles and file names (ADR 0091 §7): without case, umlauts as their base letter, ß as ss, numbers by their
 * value („Seite 2“ before „Seite 10“). The same on Android, iOS and in the browser.
 */
@JsExport
object FileOrder {
    /** Negative if [a] comes before [b], 0 if they count as equal, positive otherwise. */
    fun compare(a: String, b: String): Int {
        val x = fold(a.trim())
        val y = fold(b.trim())
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val c = x[i]
            val d = y[j]
            if (c.isDigit() && d.isDigit()) {
                val si = i
                val sj = j
                while (i < x.length && x[i].isDigit()) i++
                while (j < y.length && y[j].isDigit()) j++
                val n = x.substring(si, i).trimStart('0')
                val m = y.substring(sj, j).trimStart('0')
                if (n.length != m.length) return n.length - m.length
                val cmp = n.compareTo(m)
                if (cmp != 0) return cmp
                continue
            }
            if (c != d) return c.compareTo(d)
            i++
            j++
        }
        val rest = (x.length - i) - (y.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    /** Lower case, umlauts and accents as their base letter, ß as ss (unlike [DocText.fold], „ue“ stays). */
    private fun fold(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s.lowercase()) when (c) {
            'ä', 'à', 'á', 'â' -> b.append('a')
            'ö', 'ó', 'ò', 'ô' -> b.append('o')
            'ü', 'ú', 'ù', 'û' -> b.append('u')
            'é', 'è', 'ê', 'ë' -> b.append('e')
            'ß' -> b.append("ss")
            else -> b.append(c)
        }
        return b.toString()
    }
}

package app.anynear.anmp.core

/*
 * Synonyms and word parts for the search in the store (ADR 0078 §4): a fixed list of groups in German and English. A
 * word of the search finds every word of its group, also as part of a compound word („Krankmeldung“ finds
 * „Arbeitsunfähigkeitsbescheinigung“). No language model, no data from outside: the list is the same on every device.
 */
internal object DocWords {
    private val groups: List<List<String>> = listOf(
        // Important documents (ADR 0074).
        listOf("ausweis", "personalausweis", "perso", "reisepass", "pass", "passport", "identity card", "id card", "kinderreisepass"),
        listOf("führerschein", "fahrerlaubnis", "driving licence", "driver's license"),
        listOf("aufenthaltstitel", "aufenthaltserlaubnis", "residence permit", "visum", "visa"),
        listOf("schwerbehindertenausweis", "behindertenausweis", "schwerbehinderung"),
        listOf("krankmeldung", "arbeitsunfähigkeit", "arbeitsunfähigkeitsbescheinigung", "krankschreibung", "au-bescheinigung", "sick note"),
        listOf("arztbrief", "befund", "befundbericht", "entlassungsbericht", "entlassbrief", "medical report", "discharge letter"),
        listOf("impfpass", "impfausweis", "impfung", "impfungen", "impfnachweis", "impfbescheinigung", "impfstoff", "vaccination", "vaccine"),
        listOf("rezept", "e-rezept", "verordnung", "medikament", "medikamente", "medikationsplan", "prescription", "medication"),
        listOf("arzt", "ärztin", "praxis", "hausarzt", "klinik", "krankenhaus", "doctor", "hospital", "clinic"),
        listOf("krankenkasse", "krankenversicherung", "gesundheitskarte", "versichertenkarte", "health insurance"),
        listOf("mutterpass", "schwangerschaft", "pregnancy"),
        listOf("allergiepass", "allergie", "allergien", "allergy"),
        listOf("organspendeausweis", "organspende", "organ donor"),
        listOf("geburtsurkunde", "geburt", "geboren", "birth certificate", "birth"),
        listOf("heiratsurkunde", "eheurkunde", "hochzeit", "heirat", "eheschließung", "marriage certificate", "marriage"),
        listOf("sterbeurkunde", "tod", "verstorben", "todesfall", "death certificate", "death"),
        listOf("urkunde", "urkunden", "standesamt", "certificate", "registry office"),
        listOf("zeugnis", "zeugnisse", "abschlusszeugnis", "abitur", "diplom", "diploma", "transcript"),
        listOf("erbschein", "erbe", "erbschaft", "nachlass", "inheritance"),
        listOf("testament", "letzter wille", "last will"),
        listOf("vollmacht", "vorsorgevollmacht", "generalvollmacht", "bevollmächtigt", "power of attorney"),
        listOf("patientenverfügung", "betreuungsverfügung", "vorsorge", "living will", "advance directive"),
        listOf("versicherung", "versicherungsschein", "police", "versicherungspolice", "insurance", "policy"),
        listOf("haftpflicht", "haftpflichtversicherung", "liability"),
        listOf("hausrat", "hausratversicherung", "household insurance"),
        listOf("kfz", "auto", "fahrzeug", "kfz-versicherung", "fahrzeugschein", "zulassungsbescheinigung", "car", "vehicle"),
        // Everyday words people search for, even if those documents have no kind of their own.
        listOf("strom", "energie", "elektrizität", "abschlag", "stromrechnung", "electricity", "energy"),
        listOf("gas", "heizung", "heizkosten", "fernwärme", "heating"),
        listOf("wasser", "abwasser", "water"),
        listOf("miete", "mietvertrag", "wohnung", "vermieter", "nebenkosten", "kaution", "rent", "tenancy", "flat", "apartment"),
        listOf("rechnung", "rechnungen", "quittung", "beleg", "invoice", "bill", "receipt"),
        listOf("vertrag", "verträge", "vereinbarung", "contract", "agreement"),
        listOf("kündigung", "gekündigt", "cancellation", "termination"),
        listOf("steuer", "steuern", "finanzamt", "steuerbescheid", "steuererklärung", "tax"),
        listOf("gehalt", "lohn", "gehaltsabrechnung", "lohnabrechnung", "verdienst", "salary", "payslip"),
        listOf("rente", "rentenbescheid", "rentenversicherung", "pension"),
        listOf("bank", "konto", "kontoauszug", "girokonto", "überweisung", "account", "statement"),
        listOf("telefon", "handy", "mobilfunk", "internet", "phone", "mobile"),
        listOf("schule", "kita", "kindergarten", "school"),
        listOf("kind", "kinder", "sohn", "tochter", "child", "children"),
    )

    /** Each folded word to the folded words of its groups (a word may be in more than one group). */
    private val index: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for (group in groups) {
            val folded = group.map { DocText.fold(it) }
            for (w in folded) getOrPut(w) { mutableSetOf() }.addAll(folded)
        }
    }

    /** Folded words of the list, longest first (for splitting compound words). */
    private val byLength: List<String> = index.keys.filter { ' ' !in it && it.length >= 4 }.sortedByDescending { it.length }

    /** The folded [word] and every word of its groups; just the word if it is in no group. */
    fun variants(word: String): Set<String> = index[word] ?: setOf(word)

    /** True if [word] (folded) is in a group. */
    fun known(word: String): Boolean = word in index

    /**
     * A compound word of the search split into known parts: „stromabrechnung“ → „strom“ + „abrechnung“. Only when a
     * known word of at least four letters starts or ends the word and at least three letters remain.
     */
    fun split(word: String): List<String>? {
        if (known(word)) return null
        for (k in byLength) {
            if (word.length - k.length < 3) continue
            if (word.endsWith(k)) return listOf(word.dropLast(k.length), k)
            if (word.startsWith(k)) return listOf(k, word.drop(k.length))
        }
        return null
    }
}

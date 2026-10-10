package com.heyhooky.sdk

import java.text.BreakIterator
import java.util.Calendar

// Le fil mis en lignes, comme threadNodes() dans widget.js : le jour
// au-dessus d'une série, le nom de l'auteur de l'équipe, les messages
// groupés (même expéditeur, même auteur, moins de 5 minutes d'écart), l'heure
// sous le dernier d'une série, « Lu » sous le dernier message du visiteur.
// Fonction pure : l'écran ne fait que dessiner ces lignes.
internal sealed interface Ligne {
    val cle: String

    data object Plus : Ligne { override val cle = "plus" }
    data class Jour(val texte: String, override val cle: String) : Ligne
    data class Auteur(val nom: String, override val cle: String) : Ligne
    // traduisible : un message de l'équipe dans une autre langue (le lien
    // « Traduire » dessous) ; traduction : où il en est.
    data class Bulle(
        val message: Message,
        val suite: Boolean,
        val fin: Boolean,
        val emoji: Int,
        val traduisible: Boolean = false,
        val traduction: EtatTraduction? = null,
    ) : Ligne {
        override val cle = "m" + message.id
        // Le texte affiché : la traduction, sauf si le visiteur relit l'original.
        val texte: String
            get() = (traduction as? EtatTraduction.Faite)?.takeIf { !it.original }?.texte ?: message.texte
    }
    data class Heure(val texte: String, val visiteur: Boolean, override val cle: String) : Ligne
    data class Envoi(val envoi: EnvoiEnCours) : Ligne { override val cle = "envoi" + envoi.debut }
    data class Frappe(val nom: String) : Ligne { override val cle = "frappe" }
    data class Intro(val texte: String) : Ligne { override val cle = "intro" }
    data object Carte : Ligne { override val cle = "carte" }
    data object Fermee : Ligne { override val cle = "fermee" }
}

internal object MiseEnLignes {
    private const val SERIE = 5 * 60 * 1000L

    fun lignes(
        etat: EtatChat,
        textes: Textes,
        identifie: Boolean,
        jour: (Long) -> String,
        heure: (Long) -> String,
        aConversation: Boolean = etat.conversation != null,
        traduisible: (Message) -> Boolean = { false },
    ): List<Ligne> {
        val messages = etat.messages
        val sortie = ArrayList<Ligne>()

        // Pas encore de conversation : le message du site, puis le prénom et
        // l'e-mail (facultatifs, sauf si l'app a déjà dit qui c'est).
        if (!aConversation || messages.isEmpty()) {
            sortie += Ligne.Intro(etat.site?.accueil ?: textes.t("accueil"))
            if (!identifie && !aConversation) sortie += Ligne.Carte
            etat.envoiVisible?.let { sortie += Ligne.Envoi(it) }
            return sortie
        }

        if (etat.encore) sortie += Ligne.Plus
        val dernierVisiteur = messages.lastOrNull { it.expediteur == Expediteur.VISITEUR }
        val lu = dernierVisiteur != null && dernierVisiteur === messages.last() &&
            etat.luParEquipe != null && etat.luParEquipe >= dernierVisiteur.creeLe

        messages.forEachIndexed { i, message ->
            val avant = messages.getOrNull(i - 1)
            val apres = messages.getOrNull(i + 1)
            val nouveauJour = avant == null || !memeJour(avant.creeLe, message.creeLe)
            val suite = !nouveauJour && serie(avant, message)
            val fin = apres == null || !serie(message, apres) || !memeJour(apres.creeLe, message.creeLe)

            if (nouveauJour) sortie += Ligne.Jour(jour(message.creeLe), "j" + debutDuJour(message.creeLe))
            if (message.expediteur == Expediteur.EQUIPE && !suite && message.auteur != null) {
                sortie += Ligne.Auteur(message.auteur, "a" + message.id)
            }
            val emoji = if (message.pieces.isEmpty()) nombreEmoji(message.texte) else 0
            val traduire = traduisible(message)
            sortie += Ligne.Bulle(message, suite, fin, emoji, traduire, if (traduire) etat.traductions[message.id] else null)
            if (fin) {
                val texte = heure(message.creeLe) + if (message === dernierVisiteur && lu) " · " + textes.t("lu") else ""
                sortie += Ligne.Heure(texte, message.expediteur == Expediteur.VISITEUR, "h" + message.id)
            }
        }

        etat.envoiVisible?.let { sortie += Ligne.Envoi(it) }
        etat.equipeEcrit?.let { sortie += Ligne.Frappe(textes.t("ecrit", "nom" to it)) }
        if (etat.conversation?.fermee == true && etat.envoiVisible == null) sortie += Ligne.Fermee
        return sortie
    }

    private fun serie(a: Message?, b: Message?): Boolean =
        a != null && b != null && a.expediteur == b.expediteur && a.auteur == b.auteur && b.creeLe - a.creeLe < SERIE

    fun memeJour(a: Long, b: Long): Boolean = debutDuJour(a) == debutDuJour(b)

    fun debutDuJour(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // Un message fait de 1 à 3 emoji seulement s'affiche en grand, comme dans
    // Messages (et la bulle web). Rend leur nombre, 0 sinon.
    fun nombreEmoji(texte: String): Int {
        val compact = texte.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || texte.length > 40) return 0
        if (compact.all { it.isDigit() || it == '#' || it == '*' }) return 0
        var i = 0
        while (i < compact.length) {
            val point = compact.codePointAt(i)
            if (!pictogramme(point)) return 0
            i += Character.charCount(point)
        }
        val graphemes = BreakIterator.getCharacterInstance()
        graphemes.setText(compact)
        var n = 0
        while (graphemes.next() != BreakIterator.DONE) n++
        return if (n in 1..3) n else 0
    }

    // Les points de code d'un emoji (Extended_Pictographic et ses modificateurs),
    // à la main : Character ne connaît pas ces propriétés avant Android 14.
    private fun pictogramme(p: Int): Boolean =
        p in 0x1F000..0x1FAFF || p in 0x2600..0x27BF || p in 0x2300..0x23FF || p in 0x2B00..0x2BFF ||
            p in 0x1F1E6..0x1F1FF || p in 0xE0020..0xE007F || p in 0x2190..0x21FF ||
            p == 0x200D || p == 0xFE0F || p == 0x20E3 || p == 0x00A9 || p == 0x00AE || p == 0x203C ||
            p == 0x2049 || p == 0x2122 || p == 0x2139 || p == 0x2934 || p == 0x2935 || p == 0x3030 ||
            p == 0x303D || p == 0x3297 || p == 0x3299 || p in 0x30..0x39 || p == 0x23 || p == 0x2A

    // Les adresses web deviennent des liens, comme texteAvecLiens() du web :
    // la liste des [début, fin) de chaque lien.
    private val LIEN = Regex("""\bhttps?://[^\s<>"]+[^\s<>"'.,;:!?)\]»]""")

    fun liens(texte: String): List<IntRange> = LIEN.findAll(texte).map { it.range }.toList()
}

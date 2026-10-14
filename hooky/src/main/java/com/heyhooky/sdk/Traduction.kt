package com.heyhooky.sdk

import java.util.Locale

// La traduction des messages de l'équipe, comme dans widget/widget.js : un
// message écrit dans une autre langue que celle de la bulle porte un lien
// « Traduire » ; traduit, il montre le texte dans la langue de la bulle et
// « Traduit de l’anglais · Voir l’original ». « Toujours traduire » (le
// bouton bascule de l'en-tête, gardé par site sur l'appareil, actif par
// défaut) les traduit dès leur arrivée. L'état vit dans le Moteur ; ici, les
// règles.

// Où en est la traduction d'un message.
internal sealed interface EtatTraduction {
    data object EnCours : EtatTraduction
    // original : le visiteur a choisi de relire le texte d'origine.
    data class Faite(val texte: String, val source: String, val original: Boolean = false) : EtatTraduction
    data object Echec : EtatTraduction
}

internal object RegleTraduction {
    // « en-US » → « en » : les langues se comparent sur leur code court.
    fun codeCourt(langue: String?): String =
        langue.orEmpty().lowercase(Locale.ROOT).split('-', '_').first()

    // Un message de l'équipe, avec du texte (pas seulement des emoji), dont la
    // langue est connue et n'est pas celle de la bulle. Un serveur d'avant la
    // traduction n'envoie pas la langue : aucun bouton.
    fun traduisible(message: Message, lecteur: String): Boolean {
        if (message.expediteur != Expediteur.EQUIPE || message.texte.isBlank()) return false
        val source = codeCourt(message.langue)
        if (source.isEmpty() || source == codeCourt(lecteur)) return false
        return MiseEnLignes.nombreEmoji(message.texte) == 0
    }

    // Le nom d'une langue dans celle de la bulle (« anglais »), null si le
    // système ne sait pas le dire.
    fun nomLangue(code: String, langue: String): String? {
        val nom = Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(langue))
        return nom.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
    }

    private val VOYELLE = Regex("^[aeiouyhàâéèêëîïôûü]", RegexOption.IGNORE_CASE)
    private val LO = Regex("^(s[^aeiou]|z|gn|ps|x)", RegexOption.IGNORE_CASE)

    // « Traduit de l’anglais », avec la bonne préposition dans chaque langue
    // (comme traduitDe() de widget.js) ; « Traduit (EN) » pour une langue
    // sans nom.
    fun traduitDe(code: String, textes: Textes): String {
        val nom = nomLangue(code, textes.langue) ?: return textes.t("traduit_code", "code" to code.uppercase(Locale.ROOT))
        val voyelle = VOYELLE.containsMatchIn(nom)
        return when (textes.langue) {
            "fr" -> "Traduit " + (if (voyelle) "de l’" else "du ") + nom
            "es" -> "Traducido del $nom"
            "pt" -> "Traduzido do $nom"
            "it" -> "Tradotto " + (if (voyelle) "dall’" else if (LO.containsMatchIn(nom)) "dallo " else "dal ") + nom
            "de" -> "Übersetzt aus dem " + if (nom.endsWith("sch")) nom + "en" else nom
            else -> "Translated from $nom"
        }
    }
}

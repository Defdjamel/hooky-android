package com.heyhooky.sdk

// Ce que rend l'API de la bulle (back/app/controllers/widget/*), décodé par
// Decodage. Les dates sont en millisecondes depuis 1970 (UTC).

// GET /v1/widget/site : le nom et l'apparence du site.
internal data class Site(
    val nom: String,
    val couleur: String,
    val position: String,
    val accueil: String?,
    // L'agent IA du site répond (avant le premier message, la bulle le dit).
    val agent: Boolean = false,
)

// Conversation#visitor_payload : le jeton est la seule clé du visiteur.
internal data class Conversation(
    val jeton: String,
    val nomVisiteur: String?,
    val emailVisiteur: String?,
    val fermee: Boolean,
    val luParEquipe: Long?,
    val creeLe: Long?,
    // L'agent IA répond en ce moment (agent), ou a passé la main à l'équipe
    // (handed_off) ; faux pour un ancien serveur.
    val agent: Boolean = false,
    val passeeAUnHumain: Boolean = false,
)

internal enum class Expediteur { VISITEUR, EQUIPE }

// MessageAttachment#payload : une photo (kind image, avec miniature et
// dimensions) ou un fichier. Les liens signés valent une heure.
internal data class PieceJointe(
    val id: Long,
    val nom: String,
    val type: String,
    val taille: Long,
    val image: Boolean,
    val url: String,
    val miniature: String?,
    val largeur: Int?,
    val hauteur: Int?,
)

internal data class Message(
    val id: Long,
    val expediteur: Expediteur,
    val auteur: String?,
    val texte: String,
    val pieces: List<PieceJointe>,
    val creeLe: Long,
    // Quand ce message (et ses liens) a été reçu de l'API.
    val recuLe: Long = 0,
    // La langue du texte (« en », « pt-BR »…), absente d'un serveur d'avant
    // la traduction (alors, pas de bouton « Traduire »).
    val langue: String? = null,
    // Un message de l'agent IA (arrivé comme un message de l'équipe) et son
    // contenu enrichi ; reponseA et choix : le choix rapide qu'un message du
    // visiteur a touché.
    val bot: Boolean = false,
    val blocs: List<Bloc> = emptyList(),
    val reponseA: Long? = null,
    val choix: String? = null,
)

// Une fiche produit d'un message de l'agent : le nom, le prix et la
// disponibilité tels que le catalogue les écrit, le lien et la photo.
internal data class FicheProduit(
    val nom: String,
    val prix: String?,
    val disponibilite: String?,
    val url: String?,
    val image: String?,
)

// Un choix rapide : son id (renvoyé tel quel, en texte) et son libellé.
internal data class ChoixRapide(val id: String, val libelle: String)

// Les blocs sous le texte d'un message de l'agent, dans l'ordre reçu.
internal sealed interface Bloc {
    data class Produits(val fiches: List<FicheProduit>) : Bloc
    data class Lien(val libelle: String?, val url: String) : Bloc
    data class Choix(val options: List<ChoixRapide>) : Bloc
}

// POST /v1/widget/conversation/messages/:id/translation : le texte traduit
// vers la langue de la bulle, et la langue d'origine détectée.
internal data class Traduction(
    val texte: String,
    val source: String?,
)

// Un fil : la conversation, une page de messages et s'il en reste avant.
internal data class Fil(
    val conversation: Conversation,
    val messages: List<Message>,
    val encore: Boolean,
)

// Ce qui arrive par Action Cable sur ConversationChannel.
internal sealed interface EvenementConversation {
    data class NouveauMessage(val message: Message) : EvenementConversation
    data class Ecrit(val expediteur: Expediteur, val auteur: String?) : EvenementConversation
    data class Lu(val expediteur: Expediteur, val le: Long?) : EvenementConversation
    // L'agent IA a passé la main (raison : visitor, agent, quota, error, team).
    data class Passage(val raison: String?) : EvenementConversation
}

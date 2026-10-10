package com.heyhooky.sdk

// Ce que rend l'API de la bulle (back/app/controllers/widget/*), décodé par
// Decodage. Les dates sont en millisecondes depuis 1970 (UTC).

// GET /v1/widget/site : le nom et l'apparence du site.
internal data class Site(
    val nom: String,
    val couleur: String,
    val position: String,
    val accueil: String?,
)

// Conversation#visitor_payload : le jeton est la seule clé du visiteur.
internal data class Conversation(
    val jeton: String,
    val nomVisiteur: String?,
    val emailVisiteur: String?,
    val fermee: Boolean,
    val luParEquipe: Long?,
    val creeLe: Long?,
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
)

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
}

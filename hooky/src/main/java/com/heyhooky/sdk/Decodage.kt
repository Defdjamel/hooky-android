package com.heyhooky.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

// Le JSON de l'API, lu à la main avec org.json (fourni par Android) : pas de
// bibliothèque de sérialisation à embarquer chez l'app qui utilise le SDK.
// Un champ absent ou inattendu ne fait jamais planter : il prend sa valeur
// par défaut.
internal object Decodage {

    fun site(json: JSONObject): Site {
        val site = json.optJSONObject("site") ?: json
        return Site(
            nom = site.chaine("name") ?: "",
            couleur = site.chaine("color") ?: "#8B6CFF",
            position = site.chaine("position") ?: "right",
            accueil = site.chaine("greeting")?.takeIf { it.isNotBlank() },
        )
    }

    fun conversation(json: JSONObject): Conversation = Conversation(
        jeton = json.chaine("token") ?: "",
        nomVisiteur = json.chaine("visitor_name"),
        emailVisiteur = json.chaine("visitor_email"),
        fermee = json.optBoolean("closed", false),
        luParEquipe = date(json.chaine("team_read_at")),
        creeLe = date(json.chaine("created_at")),
    )

    fun fil(json: JSONObject, recuLe: Long = System.currentTimeMillis()): Fil = Fil(
        conversation = conversation(json.optJSONObject("conversation") ?: JSONObject()),
        messages = messages(json.optJSONArray("messages"), recuLe),
        encore = json.optBoolean("has_more", false),
    )

    fun messages(liste: JSONArray?, recuLe: Long = System.currentTimeMillis()): List<Message> {
        if (liste == null) return emptyList()
        return (0 until liste.length()).mapNotNull { i -> liste.optJSONObject(i)?.let { message(it, recuLe) } }
    }

    fun message(json: JSONObject, recuLe: Long = System.currentTimeMillis()): Message? {
        val id = json.optLong("id", -1)
        if (id < 0) return null
        val pieces = json.optJSONArray("attachments")
        return Message(
            id = id,
            expediteur = expediteur(json.chaine("sender")),
            auteur = json.chaine("author_name")?.takeIf { it.isNotBlank() },
            texte = json.chaine("body") ?: "",
            pieces = if (pieces == null) emptyList() else (0 until pieces.length()).mapNotNull { pieces.optJSONObject(it)?.let(::piece) },
            creeLe = date(json.chaine("created_at")) ?: recuLe,
            recuLe = recuLe,
            langue = json.chaine("locale")?.takeIf { it.isNotBlank() },
        )
    }

    // { "translation": { "message_id", "locale", "source_locale", "body" } }.
    fun traduction(json: JSONObject): Traduction? {
        val t = json.optJSONObject("translation") ?: return null
        val texte = t.chaine("body") ?: return null
        return Traduction(texte = texte, source = t.chaine("source_locale")?.takeIf { it.isNotBlank() })
    }

    fun piece(json: JSONObject): PieceJointe? {
        val url = json.chaine("url") ?: return null
        return PieceJointe(
            id = json.optLong("id"),
            nom = json.chaine("filename") ?: "",
            type = json.chaine("content_type") ?: "",
            taille = json.optLong("byte_size"),
            image = json.chaine("kind") == "image",
            url = url,
            miniature = json.chaine("thumbnail_url"),
            largeur = json.optInt("width", 0).takeIf { it > 0 },
            hauteur = json.optInt("height", 0).takeIf { it > 0 },
        )
    }

    fun expediteur(valeur: String?): Expediteur = if (valeur == "team") Expediteur.EQUIPE else Expediteur.VISITEUR

    // Un événement de ConversationChannel (le « message » d'une trame).
    fun evenement(json: JSONObject): EvenementConversation? = when (json.chaine("type")) {
        "message" -> json.optJSONObject("message")?.let(::message)?.let { EvenementConversation.NouveauMessage(it) }
        "typing" -> EvenementConversation.Ecrit(expediteur(json.chaine("sender")), json.chaine("author_name")?.takeIf { it.isNotBlank() })
        "read" -> EvenementConversation.Lu(expediteur(json.chaine("sender")), date(json.chaine("read_at")))
        else -> null
    }

    // Le corps d'une erreur : { "error": "file_too_large" }.
    fun codeErreur(corps: String?): String? = try {
        corps?.takeIf { it.isNotBlank() }?.let { JSONObject(it).chaine("error") }
    } catch (_: Exception) {
        null
    }

    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?\s*(Z|[+-]\d{2}:?\d{2})?$""")

    // « 2026-10-10T14:02:03.123+02:00 » (Rails) → millisecondes UTC. Écrit à
    // la main : java.time n'existe qu'à partir d'Android 8, et le « X » de
    // SimpleDateFormat qu'à partir d'Android 7.
    fun date(texte: String?): Long? {
        val m = ISO.matchEntire(texte?.trim() ?: return null) ?: return null
        val (annee, mois, jour, heure, minute, seconde) = m.destructured
        val fraction = m.groupValues[7]
        val zone = m.groupValues[8]
        val calendrier = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(annee.toInt(), mois.toInt() - 1, jour.toInt(), heure.toInt(), minute.toInt(), seconde.toInt())
        }
        var millis = calendrier.timeInMillis + if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').take(3).toLong()
        if (zone.isNotEmpty() && zone != "Z") {
            val signe = if (zone[0] == '-') -1 else 1
            val chiffres = zone.substring(1).replace(":", "")
            val decalage = (chiffres.take(2).toInt() * 60 + chiffres.drop(2).toInt()) * 60_000L
            millis -= signe * decalage
        }
        return millis
    }

    // optString rend « null » (la chaîne) pour un JSON null : on n'en veut pas.
    private fun JSONObject.chaine(cle: String): String? =
        if (!has(cle) || isNull(cle)) null else opt(cle)?.toString()
}

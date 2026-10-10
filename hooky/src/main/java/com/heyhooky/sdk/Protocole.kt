package com.heyhooky.sdk

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

// Le protocole d'Action Cable (sous-protocole actioncable-v1-json), réduit à
// ce dont la bulle a besoin : s'abonner à ConversationChannel, dire « en
// train d'écrire », et lire ce qui arrive. Fonctions pures, testées sur la JVM.
internal object Protocole {
    const val SOUS_PROTOCOLES = "actioncable-v1-json, actioncable-unsupported"

    // Le visiteur n'écoute que sa conversation, quel que soit l'id : la bulle
    // web envoie 0 (le jeton ne dit pas l'id), on fait pareil.
    val IDENTIFIANT: String = JSONObject().put("channel", "ConversationChannel").put("id", 0).toString()

    fun abonnement(identifiant: String = IDENTIFIANT): String =
        JSONObject().put("command", "subscribe").put("identifier", identifiant).toString()

    fun frappe(identifiant: String = IDENTIFIANT): String =
        JSONObject()
            .put("command", "message")
            .put("identifier", identifiant)
            .put("data", JSONObject().put("action", "typing").toString())
            .toString()

    // https://api… → wss://…/cable?conversation_token=…&client=…&app=… (un
    // WebSocket ne porte pas toujours les en-têtes jusqu'au serveur : client
    // et app voyagent aussi dans l'adresse).
    fun adresse(api: String, jeton: String, client: String, app: String): HttpUrl =
        api.toHttpUrl().newBuilder()
            .addPathSegment("cable")
            .addQueryParameter("conversation_token", jeton)
            .addQueryParameter("client", client)
            .addQueryParameter("app", app)
            .build()

    // OkHttp ouvre un WebSocket avec une adresse http(s) : il fait lui-même
    // la montée en ws(s).
    sealed interface Trame {
        data object Bienvenue : Trame
        data object Ping : Trame
        data object Confirme : Trame
        data object Refuse : Trame
        // reconnect: false → le jeton ne vaut plus rien.
        data class Deconnexion(val reconnecter: Boolean, val raison: String?) : Trame
        data class Evenement(val evenement: EvenementConversation) : Trame
        data object Inconnue : Trame
    }

    fun lire(texte: String, identifiant: String = IDENTIFIANT): Trame {
        val json = try {
            JSONObject(texte)
        } catch (_: Exception) {
            return Trame.Inconnue
        }
        return when (json.optString("type")) {
            "welcome" -> Trame.Bienvenue
            "ping" -> Trame.Ping
            "confirm_subscription" -> Trame.Confirme
            "reject_subscription" -> Trame.Refuse
            "disconnect" -> Trame.Deconnexion(json.optBoolean("reconnect", true), json.optString("reason").takeIf { it.isNotEmpty() })
            else -> {
                val message = json.optJSONObject("message")
                if (message != null && memeIdentifiant(json.optString("identifier"), identifiant)) {
                    Decodage.evenement(message)?.let { Trame.Evenement(it) } ?: Trame.Inconnue
                } else {
                    Trame.Inconnue
                }
            }
        }
    }

    // Rails renvoie l'identifiant tel qu'on l'a envoyé, mais on compare le
    // contenu, pas l'écriture (ordre des clés, espaces).
    private fun memeIdentifiant(a: String, b: String): Boolean = try {
        val x = JSONObject(a)
        val y = JSONObject(b)
        x.optString("channel") == y.optString("channel") && x.optString("id") == y.optString("id")
    } catch (_: Exception) {
        a == b
    }
}

// Le délai avant de se reconnecter : 1 s, puis le double à chaque échec,
// 30 s au plus, comme la bulle web ; remis à 1 s dès qu'une connexion tient.
internal class Reconnexion(
    private val premier: Long = 1_000,
    private val plafond: Long = 30_000,
) {
    private var prochain = premier

    fun suivant(): Long {
        val delai = prochain
        prochain = (prochain * 2).coerceAtMost(plafond)
        return delai
    }

    fun reussie() {
        prochain = premier
    }
}

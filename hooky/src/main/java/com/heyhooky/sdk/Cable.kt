package com.heyhooky.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

// Le temps réel : un WebSocket Action Cable sur ConversationChannel, ouvert
// tant que l'app est au premier plan et qu'une conversation existe.
//
// - à l'ouverture, on s'abonne ; à la confirmation, on relit le fil
//   (surAbonne) pour rattraper ce qui a pu passer pendant une coupure ;
// - une coupure : on se reconnecte après 1 s, 2 s, 4 s… 30 s au plus ;
// - Rails envoie un ping toutes les 3 s : sans nouvelles depuis SILENCE_MAX,
//   la connexion est tenue pour morte (réseau changé, veille) et refaite ;
// - un abonnement refusé ou une déconnexion sans reconnexion : le jeton ne
//   vaut plus rien (surRefus).
//
// Les rappels d'OkHttp arrivent sur ses fils : tout repasse par la portée
// (le fil principal), et un rappel d'une ancienne connexion est ignoré.
internal class Cable(
    private val client: OkHttpClient,
    private val portee: CoroutineScope,
    private val adresse: () -> HttpUrl?,
    private val surEvenement: (EvenementConversation) -> Unit,
    private val surAbonne: () -> Unit,
    private val surRefus: () -> Unit,
    private val reconnexion: Reconnexion = Reconnexion(),
    private val horloge: () -> Long = System::currentTimeMillis,
    private val silenceMax: Long = SILENCE_MAX,
) {
    enum class Etat { FERME, CONNEXION, ABONNE }

    var etat: Etat = Etat.FERME
        private set

    // Vrai tant que l'on veut être connecté (premier plan, conversation).
    private var voulu = false
    private var socket: WebSocket? = null
    private var generation = 0
    private var attente: Job? = null
    private var veille: Job? = null
    private var dernierSigne = 0L
    private var derniereFrappe = 0L

    // Nombre de connexions ouvertes depuis le début (pour les tests).
    var connexions: Int = 0
        private set

    fun ouvrir() {
        voulu = true
        if (socket == null && attente?.isActive != true) connecter()
    }

    fun fermer() {
        voulu = false
        attente?.cancel()
        veille?.cancel()
        generation++
        socket?.close(1000, null)
        socket = null
        etat = Etat.FERME
    }

    // « En train d'écrire », pas plus d'une fois toutes les 2 s (le serveur
    // n'en relaie de toute façon pas davantage).
    fun frappe(): Boolean {
        val maintenant = horloge()
        if (etat != Etat.ABONNE || maintenant - derniereFrappe < FRAPPE_INTERVALLE) return false
        derniereFrappe = maintenant
        return socket?.send(Protocole.frappe()) == true
    }

    private fun connecter() {
        val url = adresse() ?: return
        val numero = ++generation
        etat = Etat.CONNEXION
        connexions++
        dernierSigne = horloge()
        val requete = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", Protocole.SOUS_PROTOCOLES)
            .build()
        socket = client.newWebSocket(requete, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Protocole.abonnement())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                portee.launch { if (numero == generation) recevoir(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                portee.launch { if (numero == generation) coupure() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                portee.launch { if (numero == generation) coupure() }
            }
        })
        surveiller(numero)
    }

    private fun recevoir(texte: String) {
        dernierSigne = horloge()
        when (val trame = Protocole.lire(texte)) {
            Protocole.Trame.Confirme -> {
                etat = Etat.ABONNE
                reconnexion.reussie()
                surAbonne()
            }
            Protocole.Trame.Refuse -> {
                fermer()
                surRefus()
            }
            is Protocole.Trame.Deconnexion -> if (!trame.reconnecter) {
                fermer()
                surRefus()
            }
            is Protocole.Trame.Evenement -> surEvenement(trame.evenement)
            else -> Unit
        }
    }

    private fun coupure() {
        socket = null
        veille?.cancel()
        etat = Etat.FERME
        if (!voulu) return
        val delai = reconnexion.suivant()
        attente?.cancel()
        attente = portee.launch {
            delay(delai)
            if (voulu && socket == null) connecter()
        }
    }

    // Une connexion muette depuis trop longtemps est coupée : onFailure suit,
    // puis la reconnexion.
    private fun surveiller(numero: Int) {
        veille?.cancel()
        veille = portee.launch {
            while (isActive && numero == generation) {
                delay(silenceMax / 3)
                if (numero == generation && horloge() - dernierSigne > silenceMax) {
                    val mort = socket
                    socket = null
                    generation++
                    mort?.cancel()
                    coupure()
                    return@launch
                }
            }
        }
    }

    companion object {
        const val FRAPPE_INTERVALLE = 2_000L
        const val SILENCE_MAX = 12_000L
    }
}

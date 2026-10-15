package com.heyhooky.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// Le Moteur contre un faux serveur Hooky (MockWebServer) : HTTP et Action
// Cable par un vrai WebSocket. On vérifie le protocole de bout en bout :
// premier message, jeton gardé, en-têtes, temps réel, non-lus, coupure et
// reconnexion avec rattrapage, jeton refusé, erreurs.
class MoteurTest {
    private val serveur = MockWebServer()
    private lateinit var fil: ExecutorCoroutineDispatcher
    private lateinit var portee: CoroutineScope
    private val requetes = CopyOnWriteArrayList<RecordedRequest>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val trames = CopyOnWriteArrayList<String>()
    private var lectures = 0
    private var refuserLeJeton = false
    private val moteurs = CopyOnWriteArrayList<Moteur>()

    private val messageEquipe = """{"id": 2, "sender": "team", "author_name": "Sam", "body": "Bonjour !", "attachments": [], "created_at": "2026-10-10T12:01:00Z"}"""

    @Before
    fun preparer() {
        fil = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        portee = CoroutineScope(SupervisorJob() + fil)
        serveur.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requetes += request
                val chemin = request.path!!.substringBefore('?')
                return when {
                    chemin == "/v1/widget/site" -> json(200, """{"site": {"name": "Boutique", "color": "#FF5B3A", "position": "right", "greeting": "Une question ?"}}""")
                    chemin == "/v1/widget/conversations" -> json(201, filJson(listOf(premierMessage(request))))
                    chemin == "/v1/widget/conversation/messages" -> json(201, """{"message": {"id": 5, "sender": "visitor", "body": "Suite", "attachments": [], "created_at": "2026-10-10T12:03:00Z"}}""")
                    chemin == "/v1/widget/conversation" -> {
                        lectures++
                        if (refuserLeJeton) json(401, """{"error": "unauthorized"}""") else json(200, filJson(listOf(premierMessage(null), messageEquipe)))
                    }
                    chemin == "/v1/widget/conversation/read" -> MockResponse().setResponseCode(204)
                    chemin == "/cable" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets += webSocket
                            webSocket.send("""{"type":"welcome"}""")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            trames += text
                            val trame = JSONObject(text)
                            if (trame.getString("command") == "subscribe") {
                                webSocket.send(JSONObject().put("identifier", trame.getString("identifier")).put("type", "confirm_subscription").toString())
                            }
                        }
                    })
                    else -> json(404, """{"error": "not_found"}""")
                }
            }
        }
        serveur.start()
    }

    @After
    fun ranger() {
        runBlocking(fil) { moteurs.forEach { it.cable.fermer() } }
        sockets.forEach { runCatching { it.close(1000, null) } }
        portee.cancel()
        fil.close()
        // Un WebSocket à moitié fermé peut faire traîner l'arrêt : sans importance ici.
        runCatching { serveur.shutdown() }
    }

    private fun premierMessage(requete: RecordedRequest?): String {
        val corps = requete?.body?.clone()?.readUtf8()?.let { runCatching { JSONObject(it) }.getOrNull() }
        val texte = corps?.optString("body") ?: "Bonjour"
        return JSONObject().put("id", 1).put("sender", "visitor").put("body", texte).put("attachments", org.json.JSONArray())
            .put("created_at", "2026-10-10T12:00:00Z").toString()
    }

    private fun filJson(messages: List<String>) =
        """{"conversation": {"token": "jeton-1", "visitor_name": "Lou", "closed": false, "team_read_at": null, "created_at": "2026-10-10T12:00:00Z"},
            "messages": [${messages.joinToString(",")}], "has_more": false}"""

    private fun json(statut: Int, corps: String) = MockResponse().setResponseCode(statut).setHeader("Content-Type", "application/json").setBody(corps)

    private fun moteur(stockage: Stockage = StockageMemoire()): Moteur {
        val api = Api(serveur.url("/").toString(), OkHttpClient(), Identite("hooky-android/1.0.1", "com.exemple 2.0 (3)"))
        return Moteur(
            cleSite = "pk_test",
            api = api,
            stockage = stockage,
            portee = portee,
            textes = Textes("fr"),
            identite = Identite("hooky-android/1.0.1", "com.exemple 2.0 (3)"),
            fuseau = "Europe/Paris",
            page = "android-app://com.exemple",
            reconnexion = Reconnexion(premier = 50, plafond = 200),
        ).also { moteurs += it }
    }

    private fun sur(bloc: suspend () -> Unit) = runBlocking(fil) { bloc() }

    private fun attendre(message: String, condition: () -> Boolean) {
        val fin = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > fin) throw AssertionError("Toujours pas : $message")
            Thread.sleep(20)
        }
    }

    @Test
    fun `le premier message ouvre la conversation, garde le jeton et branche le temps réel`() {
        val stockage = StockageMemoire()
        val moteur = moteur(stockage)
        moteur.nomVisiteur = "Lou"
        sur {
            moteur.demarrer()
            moteur.premierPlan(true)
        }
        attendre("le site") { moteur.etat.value.site != null }
        assertEquals("Boutique", moteur.etat.value.site!!.nom)

        val parti = runBlocking(fil) { moteur.envoyer("Bonjour", emptyList()) }
        assertTrue(parti)
        assertEquals("jeton-1", stockage.jeton)
        assertEquals(1, moteur.etat.value.messages.size)
        assertNull(moteur.etat.value.envoi)

        val ouverture = requetes.first { it.path == "/v1/widget/conversations" }
        assertEquals("hooky-android/1.0.1", ouverture.getHeader("X-Hooky-Client"))
        assertEquals("com.exemple 2.0 (3)", ouverture.getHeader("X-Hooky-App"))

        // Le WebSocket s'ouvre avec le jeton, le client et l'app dans l'adresse,
        // le sous-protocole d'Action Cable, et s'abonne.
        attendre("l'abonnement") { moteur.cable.etat == Cable.Etat.ABONNE }
        val cable = requetes.first { it.path!!.startsWith("/cable") }
        assertTrue(cable.path!!.contains("conversation_token=jeton-1"))
        assertTrue(cable.path!!.contains("client=hooky-android%2F1.0.1"))
        assertEquals("hooky-android/1.0.1", cable.getHeader("X-Hooky-Client"))
        assertTrue(cable.getHeader("Sec-WebSocket-Protocol")!!.contains("actioncable-v1-json"))
    }

    @Test
    fun `les champs du premier message, comme la bulle web`() {
        val moteur = moteur()
        val parti = runBlocking(fil) { moteur.envoyer("  Bonjour  ", emptyList(), nom = "Lou", email = "lou@exemple.fr") }
        assertTrue(parti)
        val corps = JSONObject(requetes.first { it.path == "/v1/widget/conversations" }.body.readUtf8())
        assertEquals("pk_test", corps.getString("site_key"))
        assertEquals("Bonjour", corps.getString("body"))
        assertEquals("Lou", corps.getString("visitor_name"))
        assertEquals("lou@exemple.fr", corps.getString("visitor_email"))
        assertEquals("fr", corps.getString("locale"))
        assertEquals("Europe/Paris", corps.getString("time_zone"))
        assertEquals("android-app://com.exemple", corps.getString("page_url"))
    }

    @Test
    fun `avec des fichiers, l'envoi part en multipart et suit sa progression`() {
        val moteur = moteur(StockageMemoire().apply { jeton = "jeton-1" })
        val progressions = CopyOnWriteArrayList<Float>()
        val octets = ByteArray(200_000) { (it % 251).toByte() }
        val photo = FichierChoisi("photo.jpg", "image/jpeg", octets.size.toLong(), null) { octets.inputStream() }
        val surveillance = portee.launchCollect(moteur) { it.envoi?.progres?.let(progressions::add) }
        val parti = runBlocking(fil) { moteur.envoyer("Suite", listOf(photo)) }
        surveillance.cancel()
        assertTrue(parti)
        val requete = requetes.first { it.path == "/v1/widget/conversation/messages" }
        assertEquals("Bearer jeton-1", requete.getHeader("Authorization"))
        assertTrue(requete.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val corps = requete.body.readUtf8()
        assertTrue(corps.contains("name=\"files[]\"; filename=\"photo.jpg\""))
        assertTrue(corps.contains("name=\"body\""))
        assertTrue("la progression avance : $progressions", progressions.any { it > 0.5f })
    }

    @Test
    fun `une réponse de l'équipe arrive en direct et compte comme non lue, jusqu'à l'ouverture`() {
        val moteur = moteur(StockageMemoire().apply { jeton = "jeton-1" })
        sur {
            moteur.demarrer()
            moteur.premierPlan(true)
        }
        attendre("l'abonnement") { moteur.cable.etat == Cable.Etat.ABONNE }
        // Le fil relu au démarrage contient déjà la réponse 2 : non lue.
        attendre("le fil") { moteur.etat.value.messages.size == 2 }
        assertEquals(1, moteur.nonLus.value)

        val identifiant = JSONObject(trames.first()).getString("identifier")
        sockets.last().send(
            JSONObject().put("identifier", identifiant).put("message", JSONObject()
                .put("type", "message").put("message", JSONObject("""{"id": 3, "sender": "team", "author_name": "Sam", "body": "Encore moi", "attachments": [], "created_at": "2026-10-10T12:02:00Z"}"""))).toString()
        )
        attendre("le message en direct") { moteur.etat.value.messages.any { it.id == 3L } }
        assertEquals(2, moteur.nonLus.value)

        // « … écrit » s'affiche, puis l'ouverture marque tout comme lu.
        sockets.last().send(JSONObject().put("identifier", identifiant).put("message", JSONObject().put("type", "typing").put("sender", "team").put("author_name", "Sam")).toString())
        attendre("« écrit »") { moteur.etat.value.equipeEcrit == "Sam" }
        sur { moteur.ouvert(true) }
        attendre("les non-lus à zéro") { moteur.nonLus.value == 0 }
        attendre("le POST read") { requetes.any { it.path == "/v1/widget/conversation/read" } }
    }

    @Test
    fun `une coupure, reconnexion, nouvel abonnement et rattrapage du fil`() {
        val moteur = moteur(StockageMemoire().apply { jeton = "jeton-1" })
        sur {
            moteur.demarrer()
            moteur.premierPlan(true)
        }
        attendre("l'abonnement") { moteur.cable.etat == Cable.Etat.ABONNE }
        val lecturesAvant = lectures
        sockets.last().close(1011, "redémarrage")
        attendre("la reconnexion") { moteur.cable.connexions == 2 && moteur.cable.etat == Cable.Etat.ABONNE }
        attendre("le rattrapage") { lectures > lecturesAvant }
        assertEquals(2, trames.count { JSONObject(it).getString("command") == "subscribe" })
    }

    @Test
    fun `en arrière-plan, le temps réel se coupe, au retour, il revient`() {
        val moteur = moteur(StockageMemoire().apply { jeton = "jeton-1" })
        sur {
            moteur.demarrer()
            moteur.premierPlan(true)
        }
        attendre("l'abonnement") { moteur.cable.etat == Cable.Etat.ABONNE }
        sur { moteur.premierPlan(false) }
        assertEquals(Cable.Etat.FERME, moteur.cable.etat)
        Thread.sleep(300)
        assertEquals("pas de reconnexion en arrière-plan", 1, moteur.cable.connexions)
        sur { moteur.premierPlan(true) }
        attendre("la reconnexion") { moteur.cable.connexions == 2 && moteur.cable.etat == Cable.Etat.ABONNE }
    }

    @Test
    fun `un jeton refusé, la conversation est oubliée`() {
        refuserLeJeton = true
        val stockage = StockageMemoire().apply { jeton = "perime" }
        val moteur = moteur(stockage)
        sur { moteur.demarrer() }
        attendre("l'oubli") { stockage.jeton == null }
        assertFalse(moteur.aConversation)
    }

    @Test
    fun `les erreurs de l'API deviennent des textes clairs`() {
        val moteur = moteur()
        assertEquals(Textes("fr").t("lourd_api"), moteur.texteErreur(ErreurApi(422, "file_too_large")))
        assertEquals(Textes("fr").t("refuse_api"), moteur.texteErreur(ErreurApi(422, "file_type_not_allowed")))
        assertEquals(Textes("fr").t("trop"), moteur.texteErreur(ErreurApi(429, "rate_limited")))
        assertEquals(Textes("fr").t("horsligne"), moteur.texteErreur(ErreurApi(0, null)))
        assertEquals(Textes("fr").t("echec"), moteur.texteErreur(ErreurApi(500, null)))
    }

    @Test
    fun `un envoi raté laisse l'erreur, sans message en route`() {
        serveur.shutdown()
        val moteur = moteur()
        val parti = runBlocking(fil) { moteur.envoyer("Bonjour", emptyList()) }
        assertFalse(parti)
        assertNull(moteur.etat.value.envoi)
        assertNotNull(moteur.etat.value.erreur)
        assertEquals(Textes("fr").t("horsligne"), moteur.etat.value.erreur)
    }

    @Test
    fun `reset oublie tout`() {
        val stockage = StockageMemoire()
        val moteur = moteur(stockage)
        runBlocking(fil) { moteur.envoyer("Bonjour", emptyList()) }
        assertNotNull(stockage.jeton)
        sur { moteur.oublier() }
        assertNull(stockage.jeton)
        assertTrue(moteur.etat.value.messages.isEmpty())
        assertEquals(0, moteur.nonLus.value)
    }
}

private fun CoroutineScope.launchCollect(moteur: Moteur, bloc: (EtatChat) -> Unit) =
    launch(Dispatchers.Default) { moteur.etat.collect { bloc(it) } }

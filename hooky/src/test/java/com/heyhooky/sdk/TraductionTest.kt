package com.heyhooky.sdk

import kotlinx.coroutines.CoroutineScope
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// La traduction des messages de l'équipe : la langue des messages décodée,
// quand le lien « Traduire » apparaît, la route contre un faux serveur, la
// mémoire des traductions, l'erreur réessayable, la langue corrigée, le
// message rediffusé en direct, « Toujours traduire » (actif par défaut,
// gardé par site, même après reset ; coupé, réactivé) et les noms de langue.
class TraductionTest {
    @get:Rule
    val dossier = TemporaryFolder()

    private val serveur = MockWebServer()
    private lateinit var fil: ExecutorCoroutineDispatcher
    private lateinit var portee: CoroutineScope
    private val requetes = CopyOnWriteArrayList<RecordedRequest>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    @Volatile private var identifiant: String? = null
    @Volatile private var statutTraduction = 200
    @Volatile private var source = "en"
    // Le temps que met le faux serveur à rendre une traduction.
    @Volatile private var retardTraduction = 0L
    private val moteurs = CopyOnWriteArrayList<Moteur>()

    // Le visiteur en français, l'équipe en anglais (2 et 3), puis en français (4).
    private val messages = listOf(
        """{"id": 1, "sender": "visitor", "body": "Bonjour", "attachments": [], "created_at": "2026-10-10T12:00:00Z", "locale": "fr"}""",
        """{"id": 2, "sender": "team", "author_name": "Sam", "body": "Hi! Your order shipped this morning.", "attachments": [], "created_at": "2026-10-10T12:01:00Z", "locale": "en"}""",
        """{"id": 3, "sender": "team", "author_name": "Sam", "body": "Anything else?", "attachments": [], "created_at": "2026-10-10T12:02:00Z", "locale": "en-US"}""",
        """{"id": 4, "sender": "team", "author_name": "Sam", "body": "Bonne journée", "attachments": [], "created_at": "2026-10-10T12:03:00Z", "locale": "fr"}""",
    )

    @Before
    fun preparer() {
        fil = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        portee = CoroutineScope(SupervisorJob() + fil)
        serveur.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requetes += request
                val chemin = request.path!!.substringBefore('?')
                return when {
                    chemin == "/v1/widget/site" -> json(200, """{"site": {"name": "Boutique", "color": "#FF5B3A", "position": "right"}}""")
                    chemin == "/v1/widget/conversation" ->
                        json(200, """{"conversation": {"token": "jeton-1", "closed": false}, "messages": [${messages.joinToString(",")}], "has_more": false}""")
                    chemin == "/v1/widget/conversation/read" -> MockResponse().setResponseCode(204)
                    chemin.endsWith("/translation") -> {
                        val id = chemin.split('/').dropLast(1).last()
                        if (statutTraduction != 200) {
                            json(statutTraduction, """{"error": "translation_unavailable"}""")
                        } else {
                            json(200, """{"translation": {"message_id": $id, "locale": "fr", "source_locale": "$source", "body": "Traduction $id"}}""")
                        }.setHeadersDelay(retardTraduction, TimeUnit.MILLISECONDS)
                    }
                    chemin == "/cable" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets += webSocket
                            webSocket.send("""{"type":"welcome"}""")
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val trame = JSONObject(text)
                            if (trame.getString("command") == "subscribe") {
                                identifiant = trame.getString("identifier")
                                webSocket.send(JSONObject().put("identifier", identifiant).put("type", "confirm_subscription").toString())
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
        runCatching { serveur.shutdown() }
    }

    private fun json(statut: Int, corps: String) = MockResponse().setResponseCode(statut).setHeader("Content-Type", "application/json").setBody(corps)

    private fun api() = Api(serveur.url("/").toString(), OkHttpClient(), Identite("hooky-android/1.0.1", "com.exemple 2.0 (3)"))

    private fun moteur(stockage: Stockage = StockageMemoire().apply { jeton = "jeton-1" }, traduction: Boolean = true): Moteur =
        Moteur(
            cleSite = "pk_test",
            api = api(),
            stockage = stockage,
            portee = portee,
            textes = Textes("fr"),
            identite = Identite("hooky-android/1.0.1", "com.exemple 2.0 (3)"),
            fuseau = "Europe/Paris",
            page = null,
            traductionActive = traduction,
        ).also { moteurs += it }

    // « Toujours traduire » coupé : la traduction message par message, au lien.
    private fun manuel() = StockageMemoire().apply { jeton = "jeton-1"; toujoursTraduire = false }

    // Un moteur avec le fil chargé.
    private fun charge(stockage: Stockage = StockageMemoire().apply { jeton = "jeton-1" }, traduction: Boolean = true): Moteur {
        val moteur = moteur(stockage, traduction)
        sur { moteur.demarrer() }
        attendre("le fil") { moteur.etat.value.messages.size == 4 }
        return moteur
    }

    private fun sur(bloc: suspend () -> Unit) = runBlocking(fil) { bloc() }

    private fun attendre(message: String, condition: () -> Boolean) {
        val fin = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > fin) throw AssertionError("Toujours pas : $message")
            Thread.sleep(20)
        }
    }

    private fun Moteur.message(id: Long) = etat.value.messages.first { it.id == id }
    private fun Moteur.traduction(id: Long) = etat.value.traductions[id]
    private val traductions get() = requetes.filter { it.path!!.endsWith("/translation") }

    // Le texte affiché, comme le calcule le fil.
    private fun Moteur.affiche(id: Long): String {
        val lignes = MiseEnLignes.lignes(etat.value, textes, identifie = true, jour = { "" }, heure = { "" }, aConversation = true, traduisible = ::traduisible)
        return lignes.filterIsInstance<Ligne.Bulle>().first { it.message.id == id }.texte
    }

    // ------------------------------------------------------------ décodage --

    @Test
    fun `la langue d'un message est lue, et facultative`() {
        assertEquals("pt-BR", Decodage.message(JSONObject("""{"id": 1, "sender": "team", "body": "Olá", "locale": "pt-BR"}"""))!!.langue)
        assertNull("un serveur d'avant la traduction", Decodage.message(JSONObject("""{"id": 1, "sender": "team", "body": "Hi"}"""))!!.langue)
        assertNull(Decodage.message(JSONObject("""{"id": 1, "sender": "team", "body": "Hi", "locale": null}"""))!!.langue)
        assertNull(Decodage.message(JSONObject("""{"id": 1, "sender": "team", "body": "Hi", "locale": ""}"""))!!.langue)
        assertEquals(
            Traduction("Bonjour\n🙂", "en"),
            Decodage.traduction(JSONObject("""{"translation": {"message_id": 9, "locale": "fr", "source_locale": "en", "body": "Bonjour\n🙂"}}""")),
        )
        assertNull(Decodage.traduction(JSONObject("""{"error": "x"}""")))
    }

    // ------------------------------------------------------------- le lien --

    @Test
    fun `le lien Traduire, seulement sous les messages de l'équipe dans une autre langue`() {
        fun m(expediteur: Expediteur = Expediteur.EQUIPE, texte: String = "Hello there", langue: String?) =
            Message(1, expediteur, null, texte, emptyList(), 0, langue = langue)
        assertTrue(RegleTraduction.traduisible(m(langue = "en"), "fr"))
        assertTrue(RegleTraduction.traduisible(m(texte = "Olá", langue = "pt-BR"), "en"))
        assertFalse("même langue, code long", RegleTraduction.traduisible(m(langue = "en-US"), "en"))
        assertFalse("la casse ne compte pas", RegleTraduction.traduisible(m(langue = "FR"), "fr"))
        assertFalse("jamais ses propres messages", RegleTraduction.traduisible(m(Expediteur.VISITEUR, langue = "en"), "fr"))
        assertFalse("langue inconnue (ancien serveur)", RegleTraduction.traduisible(m(langue = null), "fr"))
        assertFalse(RegleTraduction.traduisible(m(langue = ""), "fr"))
        assertFalse("rien à traduire", RegleTraduction.traduisible(m(texte = "  \n", langue = "en"), "fr"))
        assertFalse("seulement des emoji", RegleTraduction.traduisible(m(texte = "👍🎉", langue = "en"), "fr"))
        assertEquals("pt", RegleTraduction.codeCourt("pt-BR"))
        assertEquals("zh", RegleTraduction.codeCourt("zh_Hant"))
        assertEquals("", RegleTraduction.codeCourt(null))
    }

    @Test
    fun `désactivée par configure, ni lien ni bouton, rien ne se traduit`() {
        val moteur = charge(traduction = false)
        assertFalse(moteur.etat.value.messages.any(moteur::traduisible))
        assertFalse(moteur.proposeTraduction())
        assertTrue("« Toujours traduire » actif par défaut n'y change rien", moteur.etat.value.traductions.isEmpty())
        sur { moteur.traduire(moteur.message(2)) }
        assertNull(moteur.traduction(2))
        assertTrue(traductions.isEmpty())
    }

    // ------------------------------------------------------------ la route --

    @Test
    fun `la route reçoit la langue, avec le jeton`() {
        val traduction = runBlocking { api().traduire("jeton-1", 42, "de") }
        assertEquals(Traduction("Traduction 42", "en"), traduction)
        val requete = requetes.last()
        assertEquals("POST", requete.method)
        assertEquals("/v1/widget/conversation/messages/42/translation", requete.path)
        assertEquals("Bearer jeton-1", requete.getHeader("Authorization"))
        assertTrue(requete.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("de", JSONObject(requete.body.readUtf8()).getString("locale"))

        statutTraduction = 503
        try {
            runBlocking { api().traduire("jeton-1", 42, "de") }
            fail("une 503 doit lever")
        } catch (e: ErreurApi) {
            assertEquals(503, e.statut)
            assertEquals("translation_unavailable", e.code)
        }
    }

    // ---------------------------------- traduire, l'original, la mémoire --

    @Test
    fun `traduire, voir l'original, sans redemander`() {
        val moteur = charge(manuel())
        assertEquals(listOf(2L, 3L), moteur.etat.value.messages.filter(moteur::traduisible).map { it.id })
        assertTrue(moteur.proposeTraduction())

        sur { moteur.traduire(moteur.message(2)) }
        attendre("la traduction") { moteur.traduction(2) == EtatTraduction.Faite("Traduction 2", "en") }
        assertEquals("Traduction 2", moteur.affiche(2))
        assertEquals("fr", JSONObject(traductions.single().body.readUtf8()).getString("locale"))

        sur { moteur.basculerOriginal(moteur.message(2)) }
        assertEquals("Hi! Your order shipped this morning.", moteur.affiche(2))
        sur { moteur.basculerOriginal(moteur.message(2)) }
        assertEquals("Traduction 2", moteur.affiche(2))
        sur { moteur.traduire(moteur.message(2)) }
        assertEquals("une traduction n'est jamais redemandée", 1, traductions.size)

        // Le visiteur ne voit jamais ses messages traduits.
        assertEquals("Bonjour", moteur.affiche(1))
        assertEquals("Bonne journée", moteur.affiche(4))
    }

    @Test
    fun `une erreur se réessaie`() {
        statutTraduction = 503
        val moteur = charge(manuel())
        sur { moteur.traduire(moteur.message(2)) }
        attendre("l'erreur") { moteur.traduction(2) == EtatTraduction.Echec }
        assertEquals("Hi! Your order shipped this morning.", moteur.affiche(2))
        statutTraduction = 200
        sur { moteur.traduire(moteur.message(2)) }
        attendre("la traduction après « Réessayer »") { moteur.affiche(2) == "Traduction 2" }
        assertEquals(2, traductions.size)
    }

    @Test
    fun `déjà dans la langue de la bulle, le lien disparaît`() {
        source = "fr"
        val moteur = charge(manuel())
        sur { moteur.traduire(moteur.message(2)) }
        attendre("la langue corrigée") { moteur.message(2).langue == "fr" }
        assertNull(moteur.traduction(2))
        assertFalse(moteur.traduisible(moteur.message(2)))
    }

    @Test
    fun `un message rediffusé en direct (langue corrigée) remplace l'ancien`() {
        val moteur = charge(manuel())
        sur { moteur.premierPlan(true) }
        attendre("l'abonnement") { identifiant != null && moteur.cable.etat == Cable.Etat.ABONNE }
        val corrige = JSONObject(messages[1]).put("locale", "fr")
        sockets.last().send(JSONObject().put("identifier", identifiant).put("message", JSONObject().put("type", "message").put("message", corrige)).toString())
        attendre("la langue corrigée") { moteur.message(2).langue == "fr" }
        assertEquals("remplacé, pas ajouté", 4, moteur.etat.value.messages.size)
        assertEquals(listOf(3L), moteur.etat.value.messages.filter(moteur::traduisible).map { it.id })
    }

    // -------------------------------------------------- toujours traduire --

    @Test
    fun `par défaut le fil arrive traduit, coupé les originaux, gardé par site`() {
        val stockage = StockageFichier(dossier.root, "pk_test").apply { jeton = "jeton-1" }
        val moteur = charge(stockage)
        assertTrue("actif sans rien d'enregistré", moteur.etat.value.toujoursTraduire)
        assertTrue(moteur.proposeTraduction())
        attendre("le fil traduit") { moteur.affiche(2) == "Traduction 2" && moteur.affiche(3) == "Traduction 3" }
        assertEquals(
            "du plus récent au plus ancien",
            listOf("/v1/widget/conversation/messages/3/translation", "/v1/widget/conversation/messages/2/translation"),
            traductions.map { it.path },
        )
        // Ni le message en français, ni ceux du visiteur.
        assertEquals("Bonne journée", moteur.affiche(4))
        assertEquals("Bonjour", moteur.affiche(1))

        // Coupé : on relit les originaux, et c'est enregistré.
        sur { moteur.toujoursTraduire(false) }
        assertFalse(moteur.etat.value.toujoursTraduire)
        assertFalse(StockageFichier(dossier.root, "pk_test").toujoursTraduire)
        assertEquals("Hi! Your order shipped this morning.", moteur.affiche(2))
        assertEquals(EtatTraduction.Faite("Traduction 3", "en", original = true), moteur.traduction(3))
        assertTrue("le bouton reste, pour réactiver", moteur.proposeTraduction())

        // Réactivé : déjà traduits, affichés traduits sans nouvel appel.
        sur { moteur.toujoursTraduire(true) }
        assertEquals("Traduction 2", moteur.affiche(2))
        assertEquals("Traduction 3", moteur.affiche(3))
        assertEquals(2, traductions.size)

        // Coupé, gardé d'un lancement à l'autre et après un reset ; pas pour
        // un autre site (actif par défaut).
        sur { moteur.toujoursTraduire(false) }
        sur { moteur.oublier(identite = true) }
        assertTrue("la mémoire des traductions part avec la conversation", moteur.etat.value.traductions.isEmpty())
        assertFalse(moteur.etat.value.toujoursTraduire)
        assertFalse(StockageFichier(dossier.root, "pk_test").toujoursTraduire)
        assertNull(StockageFichier(dossier.root, "pk_test").jeton)
        assertTrue(StockageFichier(dossier.root, "pk_autre").toujoursTraduire)
    }

    @Test
    fun `coupé puis réactivé, les traductions qui manquent sont demandées`() {
        val moteur = charge(manuel())
        assertFalse(moteur.etat.value.toujoursTraduire)
        assertTrue("coupé : rien ne se traduit tout seul", moteur.etat.value.traductions.isEmpty())
        sur { moteur.traduire(moteur.message(2)) }
        attendre("la première traduction") { moteur.affiche(2) == "Traduction 2" }
        sur { moteur.basculerOriginal(moteur.message(2)) }

        sur { moteur.toujoursTraduire(true) }
        assertEquals("déjà traduit : affiché traduit, sans appel", "Traduction 2", moteur.affiche(2))
        attendre("le reste traduit") { moteur.affiche(3) == "Traduction 3" }
        assertEquals(
            listOf("/v1/widget/conversation/messages/2/translation", "/v1/widget/conversation/messages/3/translation"),
            traductions.map { it.path },
        )
        assertEquals("Bonne journée", moteur.affiche(4))
    }

    @Test
    fun `réactivé, une traduction en erreur se redemande`() {
        statutTraduction = 503
        val moteur = charge(manuel())
        sur { moteur.traduire(moteur.message(2)) }
        attendre("l'erreur") { moteur.traduction(2) == EtatTraduction.Echec }
        statutTraduction = 200
        sur { moteur.toujoursTraduire(true) }
        attendre("tout traduit") { moteur.affiche(2) == "Traduction 2" && moteur.affiche(3) == "Traduction 3" }
    }

    @Test
    fun `coupé pendant la traduction, l'original reste affiché`() {
        retardTraduction = 400
        val moteur = charge()
        attendre("la première demande partie") { traductions.size == 1 }
        sur { moteur.toujoursTraduire(false) }
        assertTrue("plus rien en cours : le lien Traduire revient", moteur.etat.value.traductions.isEmpty())
        attendre("la réponse arrivée") { moteur.traduction(3) != null }
        assertEquals(EtatTraduction.Faite("Traduction 3", "en", original = true), moteur.traduction(3))
        assertEquals("Anything else?", moteur.affiche(3))
        Thread.sleep(600)
        assertEquals("l'autre n'est jamais demandée", 1, traductions.size)
        assertNull(moteur.traduction(2))
    }

    @Test
    fun `le bouton n'est montré que s'il sert`() {
        val etat = EtatChat(messages = listOf(Message(1, Expediteur.EQUIPE, null, "Bonjour", emptyList(), 0, langue = "fr")))
        val anglais = Message(2, Expediteur.EQUIPE, null, "Hello", emptyList(), 1, langue = "en")
        val moteur = moteur()
        assertFalse(moteur.proposeTraduction(etat))
        assertFalse("actif, mais rien à traduire", moteur.proposeTraduction(etat.copy(toujoursTraduire = true)))
        assertFalse("des emoji seuls", moteur.proposeTraduction(etat.copy(messages = etat.messages + anglais.copy(texte = "👍"))))
        assertTrue(moteur.proposeTraduction(etat.copy(messages = etat.messages + anglais)))
        assertTrue(moteur.proposeTraduction(etat.copy(toujoursTraduire = false, messages = etat.messages + anglais)))
    }

    // ------------------------------------------------- les noms de langue --

    @Test
    fun `Traduit de l'anglais, dans chaque langue`() {
        assertEquals("Traduit de l’anglais", RegleTraduction.traduitDe("en", Textes("fr")))
        assertEquals("Traduit du portugais", RegleTraduction.traduitDe("pt", Textes("fr")))
        assertEquals("Translated from French", RegleTraduction.traduitDe("fr", Textes("en")))
        assertEquals("Traducido del inglés", RegleTraduction.traduitDe("en", Textes("es")))
        assertEquals("Traduzido do inglês", RegleTraduction.traduitDe("en", Textes("pt")))
        assertEquals("Tradotto dall’inglese", RegleTraduction.traduitDe("en", Textes("it")))
        assertEquals("Tradotto dallo spagnolo", RegleTraduction.traduitDe("es", Textes("it")))
        assertEquals("Tradotto dal francese", RegleTraduction.traduitDe("fr", Textes("it")))
        assertEquals("Übersetzt aus dem Englischen", RegleTraduction.traduitDe("en", Textes("de")))
        assertEquals("Traduit (QQQ)", RegleTraduction.traduitDe("qqq", Textes("fr")))
        for (langue in Textes.LANGUES) {
            val textes = Textes(langue)
            for (cle in listOf("traduire", "traduction", "voir_original", "voir_traduction", "trad_erreur", "reessayer", "toujours")) {
                assertTrue("$langue.$cle", textes.t(cle) != cle)
                // Une langue sans le texte retomberait sur le français.
                if (langue != "fr") assertTrue("$langue.$cle", textes.t(cle) != Textes("fr").t(cle))
            }
        }
    }
}

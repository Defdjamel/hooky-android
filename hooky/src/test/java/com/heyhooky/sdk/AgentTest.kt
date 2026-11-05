package com.heyhooky.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// L'agent IA : décodage tolérant des nouveaux champs (un bloc inconnu est
// ignoré, un message se lit toujours), l'événement handoff, « Parler à un
// humain », l'e-mail demandé au passage, les choix rapides, l'événement
// agent_product_click, la disponibilité et la révélation progressive.
class AgentTest {
    companion object {
        const val BLOCS = """[{"type":"products","items":[{"name":"Lampe Nova","price":"49,90 €","availability":"in_stock","url":"https://lumen.test/nova","image_url":"https://cdn.test/nova.jpg"},{"name":"Sans lien","price":12,"availability":0,"url":"javascript:alert(1)"},{"price":"sans nom"}]},{"type":"carousel","items":[]},{"type":"link","label":"Livraison","url":"https://lumen.test/livraison"},{"type":"quick_replies","options":[{"id":"a","label":"Oui"},{"id":2,"label":"Non"},{"id":"c"}]},"pas un objet",{"type":"link","url":"ftp://x"}]"""

        fun bot(id: Int, texte: String = "Voici deux lampes", blocs: String = BLOCS, le: String = "2026-10-10T12:02:00Z") =
            """{"id":$id,"sender":"team","bot":true,"author_name":"Assistant","body":"$texte","attachments":[],"blocks":$blocs,"reply_to_id":null,"choice":null,"created_at":"$le","locale":"fr"}"""
    }

    // ----------------------------------------------------------- décodage

    @Test
    fun `les blocs se lisent, un bloc inconnu ou mal formé est ignoré`() {
        val m = Decodage.message(JSONObject(bot(9)))!!
        assertTrue(m.bot)
        assertEquals(Expediteur.EQUIPE, m.expediteur)
        assertEquals(
            listOf(
                Bloc.Produits(listOf(
                    FicheProduit("Lampe Nova", "49,90 €", "in_stock", "https://lumen.test/nova", "https://cdn.test/nova.jpg"),
                    FicheProduit("Sans lien", "12", "0", null, null),
                )),
                Bloc.Lien("Livraison", "https://lumen.test/livraison"),
                Bloc.Choix(listOf(ChoixRapide("a", "Oui"), ChoixRapide("2", "Non"))),
            ),
            m.blocs,
        )
        assertEquals(4, m.unites)
        assertEquals(3, m.uniteDesChoix)
    }

    @Test
    fun `un message se lit toujours, quels que soient les nouveaux champs`() {
        val bizarre = Decodage.message(JSONObject("""{"id":3,"sender":"team","bot":"oui","body":"Salut","blocks":{"type":"link"},"reply_to_id":"x","choice":4,"created_at":"2026-10-10T12:00:00Z"}"""))!!
        assertEquals("Salut", bizarre.texte)
        assertFalse(bizarre.bot)
        assertEquals(emptyList<Bloc>(), bizarre.blocs)
        assertNull(bizarre.reponseA)
        assertEquals("4", bizarre.choix)
        // Seul l'agent porte des blocs.
        val equipe = Decodage.message(JSONObject("""{"id":5,"sender":"team","body":"Hop","blocks":[{"type":"link","url":"https://a.test"}],"created_at":"2026-10-10T12:00:00Z"}"""))!!
        assertEquals(emptyList<Bloc>(), equipe.blocs)
        // Le choix du visiteur.
        val choix = Decodage.message(JSONObject("""{"id":6,"sender":"visitor","body":"Oui","reply_to_id":9,"choice":"a","created_at":"2026-10-10T12:03:00Z"}"""))!!
        assertEquals(9L, choix.reponseA)
        assertEquals("a", choix.choix)
    }

    @Test
    fun `l'état de l'agent sur le site et la conversation, et l'événement handoff`() {
        assertTrue(Decodage.site(JSONObject("""{"site":{"name":"Lumen","agent":true}}""")).agent)
        assertFalse(Decodage.site(JSONObject("""{"site":{"name":"Lumen"}}""")).agent)
        val c = Decodage.conversation(JSONObject("""{"token":"j","agent":true,"handed_off":false}"""))
        assertTrue(c.agent)
        assertFalse(c.passeeAUnHumain)
        assertEquals(EvenementConversation.Passage("agent"), Decodage.evenement(JSONObject("""{"type":"handoff","reason":"agent","handed_off_at":"2026-10-10T12:04:00Z"}""")))
    }

    @Test
    fun `les mots, la disponibilité, les textes`() {
        assertEquals(listOf(9, 11, 14), Agent.finsDesMots("Bonjour  à toi"))
        assertEquals(listOf(5), Agent.finsDesMots("  Hop"))
        assertEquals(emptyList<Int>(), Agent.finsDesMots(""))
        assertEquals("Bonjour  à ", Revelation(1, listOf(9, 11, 14), 0, 2).texteMontre("Bonjour  à toi"))
        val fr = Textes("fr")
        val en = Textes("en")
        assertEquals("En stock" to false, Agent.disponibilite("in_stock", fr))
        assertEquals("Out of stock" to true, Agent.disponibilite("OutOfStock", en))
        assertEquals("Rupture de stock" to true, Agent.disponibilite("0", fr))
        assertEquals("En stock" to false, Agent.disponibilite("12", fr))
        assertEquals("Pre-order" to false, Agent.disponibilite("preorder", en))
        assertEquals("Sur commande" to false, Agent.disponibilite("on-backorder", fr))
        assertEquals("Expédié sous 3 jours" to false, Agent.disponibilite("Expédié sous 3 jours", fr))
        for (langue in Textes.LANGUES) {
            val t = Textes(langue)
            for (cle in listOf("assistant_ia", "mention_ia", "humain", "humain_envoi", "humain_echec", "humain_email", "email_merci",
                "email_invalide", "plus_tard", "voir", "voir_produit", "choix", "en_stock", "rupture", "precommande", "sur_commande")) {
                assertFalse("$langue.$cle", t.t(cle) == cle)
            }
        }
        assertEquals("Mit einem Menschen sprechen", Textes("de").t("humain"))
    }

    // -------------------------------------------------- contre un faux serveur

    private val serveur = MockWebServer()
    private lateinit var fil: ExecutorCoroutineDispatcher
    private lateinit var portee: CoroutineScope
    private val requetes = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var messages: List<String> = emptyList()
    @Volatile private var conversation = """{"token":"jeton-1","visitor_name":"Lou","visitor_email":null,"closed":false,"agent":true,"handed_off":false}"""
    @Volatile private var passage = 200
    @Volatile private var modification = 200

    @Before
    fun preparer() {
        fil = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        portee = CoroutineScope(SupervisorJob() + fil)
        serveur.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requetes += request
                val chemin = request.path!!.substringBefore('?')
                return when {
                    chemin == "/v1/widget/site" -> json(200, """{"site":{"name":"Lumen","color":"#5B4CF0","agent":true}}""")
                    chemin == "/v1/widget/conversation" && request.method == "GET" ->
                        json(200, """{"conversation":$conversation,"messages":[${messages.joinToString(",")}],"has_more":false}""")
                    chemin == "/v1/widget/conversation" && request.method == "PATCH" -> {
                        val email = JSONObject(request.body.clone().readUtf8()).optString("visitor_email")
                        json(modification, """{"conversation":{"token":"jeton-1","visitor_email":"$email","agent":false,"handed_off":true}}""")
                    }
                    chemin == "/v1/widget/conversation/handoff" ->
                        json(passage, """{"conversation":{"token":"jeton-1","visitor_email":null,"closed":false,"agent":false,"handed_off":true}}""")
                    chemin == "/v1/widget/conversation/messages" ->
                        json(201, """{"message":{"id":30,"sender":"visitor","body":"Oui","reply_to_id":2,"choice":"a","attachments":[],"created_at":"2026-10-10T12:05:00Z"}}""")
                    chemin == "/v1/widget/events" -> json(201, "{}")
                    else -> json(404, """{"error":"not_found"}""")
                }
            }
        }
        serveur.start()
    }

    @After
    fun ranger() {
        portee.cancel()
        fil.close()
        runCatching { serveur.shutdown() }
    }

    private fun json(statut: Int, corps: String) = MockResponse().setResponseCode(statut).setHeader("Content-Type", "application/json").setBody(corps)

    private fun visiteur(id: Int = 1, le: String = "2026-10-10T12:00:00Z") =
        """{"id":$id,"sender":"visitor","body":"Bonjour","attachments":[],"created_at":"$le"}"""

    // Une conversation reprise (le jeton gardé), relue sur le faux serveur.
    private fun moteur(vararg fil: String, email: String? = null): Moteur {
        messages = fil.toList()
        if (email != null) conversation = conversation.replace("\"visitor_email\":null", "\"visitor_email\":\"$email\"")
        val stockage = StockageMemoire().apply { jeton = "jeton-1" }
        val identite = Identite("hooky-android/test", "com.exemple 2.0 (3)")
        val m = Moteur("pk_test", Api(serveur.url("/").toString(), OkHttpClient(), identite), stockage, portee, Textes("fr"), identite, null, null)
        m.revelationPermise = { false }
        sur { m.demarrer() }
        attendre("le fil") { m.etat.value.conversation != null && m.etat.value.site != null }
        return m
    }

    private fun sur(bloc: suspend () -> Unit) = runBlocking(this.fil) { bloc() }

    private fun attendre(message: String, condition: () -> Boolean) {
        val fin = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > fin) throw AssertionError("Toujours pas : $message")
            Thread.sleep(10)
        }
    }

    @Test
    fun `la mention, puis Parler à un humain une fois que l'agent a répondu`() {
        assertTrue(EtatChat(site = Site("Lumen", "#000000", "right", null, agent = true)).mentionIa)
        val attente = moteur(visiteur())
        assertTrue(attente.etat.value.mentionIa)
        assertFalse(attente.etat.value.humainPropose)

        val repondu = moteur(visiteur(), bot(2))
        assertTrue(repondu.etat.value.humainPropose)
        assertFalse(repondu.etat.value.mentionIa)
    }

    @Test
    fun `Parler à un humain passe la main et demande l'e-mail une fois`() {
        val m = moteur(bot(2))
        sur { m.agent.parlerAUnHumain() }
        attendre("le passage") { m.etat.value.conversation?.passeeAUnHumain == true }
        val requete = requetes.last { it.path == "/v1/widget/conversation/handoff" }
        assertEquals("POST", requete.method)
        assertEquals("Bearer jeton-1", requete.getHeader("Authorization"))
        val e = m.etat.value
        assertFalse(e.humainPropose)
        assertFalse(e.mentionIa)
        assertNull(e.passage)
        assertEquals(EtatEmailPassage.DEMANDE, e.emailPassage)
        assertFalse("plus de choix une fois passé à l'équipe", e.choixVisibles(e.messages.last()))
        // L'événement du direct qui suit ne redemande rien.
        sur {
            m.agent.plusTard()
            m.agent.passeAUnHumain(demanderEmail = true)
        }
        assertNull(m.etat.value.emailPassage)
    }

    @Test
    fun `un passage raté se réessaie`() {
        passage = 500
        val m = moteur(bot(2))
        sur { m.agent.parlerAUnHumain() }
        attendre("l'échec") { m.etat.value.passage == EtatPassage.ECHEC }
        assertTrue(m.etat.value.humainPropose)
    }

    @Test
    fun `l'agent ou l'équipe qui passent la main`() {
        val equipe = moteur(bot(2))
        sur { equipe.agent.passeAUnHumain(demanderEmail = false) }
        assertTrue(equipe.etat.value.conversation!!.passeeAUnHumain)
        assertNull("l'équipe qui répond n'a pas besoin de l'e-mail", equipe.etat.value.emailPassage)

        val connu = moteur(bot(2), email = "lou@exemple.fr")
        sur { connu.agent.passeAUnHumain(demanderEmail = true) }
        assertNull("on a déjà son e-mail", connu.etat.value.emailPassage)
    }

    @Test
    fun `l'e-mail du passage est vérifié puis envoyé`() {
        val m = moteur(bot(2))
        sur { m.agent.passeAUnHumain(demanderEmail = true) }
        sur { m.agent.envoyerEmail("lou@") }
        assertEquals(EtatEmailPassage.INVALIDE, m.etat.value.emailPassage)
        sur { m.agent.emailModifie() }
        assertEquals(EtatEmailPassage.DEMANDE, m.etat.value.emailPassage)
        sur { m.agent.envoyerEmail(" lou@exemple.fr ") }
        attendre("le merci") { m.etat.value.emailPassage == EtatEmailPassage.MERCI }
        assertEquals("lou@exemple.fr", m.etat.value.conversation!!.emailVisiteur)
        assertTrue(m.etat.value.conversation!!.passeeAUnHumain)
        val patch = requetes.last { it.method == "PATCH" }
        assertEquals("lou@exemple.fr", JSONObject(patch.body.readUtf8()).getString("visitor_email"))
    }

    @Test
    fun `un choix rapide part comme un message, sur le dernier message seulement`() {
        val m = moteur(bot(1, le = "2026-10-10T12:01:00Z"), bot(2))
        val (ancien, dernier) = m.etat.value.messages
        assertFalse(m.etat.value.choixVisibles(ancien))
        assertTrue(m.etat.value.choixVisibles(dernier))
        sur { m.agent.choisir(ancien, ChoixRapide("a", "Oui")) }
        Thread.sleep(100)
        assertFalse(requetes.any { it.path == "/v1/widget/conversation/messages" })

        sur { m.agent.choisir(dernier, ChoixRapide("a", "Oui")) }
        attendre("le choix") { m.etat.value.messages.size == 3 }
        val corps = JSONObject(requetes.last { it.path == "/v1/widget/conversation/messages" }.body.readUtf8())
        assertEquals(2, corps.getInt("reply_to_id"))
        assertEquals("a", corps.getString("choice"))
        assertFalse(corps.has("body"))
        assertEquals("a", m.etat.value.messages.last().choix)
        assertFalse("les choix s'effacent", m.etat.value.choixVisibles(dernier))
    }

    @Test
    fun `Voir d'une fiche compte agent_product_click`() {
        val m = moteur(bot(2))
        m.agent.ficheOuverte("https://lumen.test/nova")
        attendre("l'événement") { requetes.any { it.path == "/v1/widget/events" } }
        val corps = JSONObject(requetes.first { it.path == "/v1/widget/events" }.body.readUtf8())
        assertEquals("agent_product_click", corps.getString("name"))
        assertEquals("https://lumen.test/nova", corps.getJSONObject("properties").getString("url"))
        assertEquals("pk_test", corps.getString("site_key"))
    }

    @Test
    fun `une réponse en direct se révèle mot par mot, puis bloc par bloc`() {
        val m = moteur(visiteur())
        m.revelationPermise = { true }
        sur {
            m.premierPlan(true)
            m.ouvert(true)
        }
        val message = Decodage.message(JSONObject(bot(2, texte = "Voici deux lampes pour toi")))!!
        val vues = CopyOnWriteArrayList<Revelation>()
        sur { m.recevoir(EvenementConversation.NouveauMessage(message)) }
        val debut = m.etat.value.revelation!!
        assertEquals(1, debut.motsMontres)
        assertEquals(4, debut.unites)
        assertFalse(debut.choixMontres)
        // Les choix ne se touchent pas avant d'être montrés.
        sur { m.agent.choisir(m.etat.value.messages.last(), ChoixRapide("a", "Oui")) }
        val fin = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < fin) {
            val r = m.etat.value.revelation ?: break
            if (vues.lastOrNull() != r) vues += r
            Thread.sleep(5)
        }
        assertNull("la révélation se termine", m.etat.value.revelation)
        assertTrue("le texte d'abord", vues.any { it.texteFini && it.unitesMontrees == 0 })
        assertTrue(vues.any { it.unitesMontrees == 3 && !it.choixMontres })
        assertTrue(vues.any { it.choixMontres })
        assertFalse(requetes.any { it.path == "/v1/widget/conversation/messages" })
    }

    @Test
    fun `pas de révélation avec Supprimer les animations, ni pour l'historique`() {
        val m = moteur(bot(2))
        sur {
            m.premierPlan(true)
            m.ouvert(true)
        }
        assertNull(m.etat.value.revelation)
        val autre = Decodage.message(JSONObject(bot(3, le = "2026-10-10T12:06:00Z")))!!
        sur { m.recevoir(EvenementConversation.NouveauMessage(autre)) }
        assertNull(m.etat.value.revelation)

        m.revelationPermise = { true }
        val suivant = Decodage.message(JSONObject(bot(4, le = "2026-10-10T12:07:00Z")))!!
        sur { m.recevoir(EvenementConversation.NouveauMessage(suivant)) }
        assertEquals(4L, m.etat.value.revelation?.id)
        // Un message de l'équipe pendant la révélation la termine d'un coup.
        val equipe = Message(5, Expediteur.EQUIPE, "Camille", "Je prends le relais", emptyList(), Decodage.date("2026-10-10T12:08:00Z")!!)
        sur { m.recevoir(EvenementConversation.NouveauMessage(equipe)) }
        assertNull(m.etat.value.revelation)
        // Les blocs vides : un message sans texte ni bloc n'a rien à révéler.
        assertTrue(Decodage.message(JSONObject(bot(6, texte = "", blocs = JSONArray().toString())))!!.unites == 0)
    }
}

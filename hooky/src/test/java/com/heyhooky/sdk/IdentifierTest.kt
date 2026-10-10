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

// identify et setAttributes contre un faux serveur (MockWebServer) : le
// premier message emporte tout, puis un PATCH regroupé et fusionné, réessayé
// au prochain envoi ; un autre userId oublie la conversation ; le nom de
// l'appareil.
class IdentifierTest {
    private val serveur = MockWebServer()
    private lateinit var fil: ExecutorCoroutineDispatcher
    private lateinit var portee: CoroutineScope
    private val requetes = CopyOnWriteArrayList<Pair<RecordedRequest, String>>()
    @Volatile private var statutPatch = 200

    @Before
    fun preparer() {
        fil = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        portee = CoroutineScope(SupervisorJob() + fil)
        serveur.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requetes += request to request.body.readUtf8()
                return when (request.method to request.path!!.substringBefore('?')) {
                    "POST" to "/v1/widget/conversations" -> json(201, """{"conversation": {"token": "jeton-1", "visitor_name": "Lou", "closed": false},
                        "messages": [{"id": 1, "sender": "visitor", "body": "Bonjour", "attachments": [], "created_at": "2026-10-10T12:00:00Z"}], "has_more": false}""")
                    "POST" to "/v1/widget/conversation/messages" -> json(201, """{"message": {"id": 2, "sender": "visitor", "body": "Suite", "attachments": [], "created_at": "2026-10-10T12:01:00Z"}}""")
                    "PATCH" to "/v1/widget/conversation" -> json(statutPatch, """{"conversation": {"token": "jeton-1", "visitor_name": "Lou", "visitor_email": "lou@exemple.fr", "closed": false}}""")
                    else -> json(404, """{"error": "not_found"}""")
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

    private fun moteur(stockage: Stockage = StockageMemoire()): Moteur {
        val identite = Identite("hooky-android/1.0.0", "com.exemple 2.0 (3)")
        return Moteur(
            cleSite = "pk_test",
            api = Api(serveur.url("/").toString(), OkHttpClient(), identite),
            stockage = stockage,
            portee = portee,
            textes = Textes("fr"),
            identite = identite,
            fuseau = "Europe/Paris",
            page = "android-app://com.exemple",
            appareil = "Google Pixel 8 · Android 15",
            delaiProfil = 200,
        )
    }

    private fun <T> sur(bloc: suspend () -> T): T = runBlocking(fil) { bloc() }

    private fun patchs() = requetes.filter { it.first.method == "PATCH" }

    private fun ouverture() = JSONObject(requetes.first { it.first.path == "/v1/widget/conversations" }.second)

    @Test
    fun `le premier message porte l'identifiant, les données libres et l'appareil`() {
        val stockage = StockageMemoire()
        val moteur = moteur(stockage)
        sur {
            moteur.identifier("Lou", "lou@exemple.fr", " 42 ", mapOf("plan" to "Free", "essai" to true))
            moteur.ajouterDonnees(mapOf("plan" to "Pro", "panier" to 49.9, "places" to 3, "essai" to null, "liste" to listOf(1, 2)))
        }
        Thread.sleep(400)
        assertTrue("sans conversation, rien ne part avant le premier message", patchs().isEmpty())

        assertTrue(sur { moteur.envoyer("Bonjour", emptyList()) })
        val corps = ouverture()
        assertEquals("Lou", corps.getString("visitor_name"))
        assertEquals("lou@exemple.fr", corps.getString("visitor_email"))
        assertEquals("42", corps.getString("visitor_user_id"))
        assertEquals("Google Pixel 8 · Android 15", corps.getString("visitor_device"))
        val donnees = corps.getJSONObject("visitor_attributes")
        assertEquals(setOf("plan", "panier", "places"), donnees.keys().asSequence().toSet())
        assertEquals("Pro", donnees.getString("plan"))
        assertEquals(49.9, donnees.getDouble("panier"), 0.0)
        assertEquals(3, donnees.getInt("places"))
        assertEquals("l'identifiant est rangé avec le jeton", "42", stockage.utilisateur)
        Thread.sleep(400)
        assertTrue("tout est parti avec le premier message", patchs().isEmpty())
    }

    @Test
    fun `avec des fichiers, les données libres partent en chaîne JSON`() {
        val moteur = moteur()
        val octets = ByteArray(10) { it.toByte() }
        val photo = FichierChoisi("photo.jpg", "image/jpeg", octets.size.toLong(), null) { octets.inputStream() }
        sur { moteur.identifier(null, null, "42", mapOf("plan" to "Pro")) }
        assertTrue(sur { moteur.envoyer("La voici", listOf(photo)) })
        val corps = requetes.first { it.first.path == "/v1/widget/conversations" }.second
        assertTrue(corps.contains("name=\"visitor_user_id\"\r\nContent-Length: 2\r\n\r\n42\r\n"))
        assertTrue(corps, corps.contains("name=\"visitor_attributes\"\r\nContent-Length: 14\r\n\r\n{\"plan\":\"Pro\"}\r\n"))
        assertTrue(corps.contains("name=\"visitor_device\""))
    }

    @Test
    fun `sur une conversation ouverte, un seul PATCH regroupé et fusionné`() {
        val stockage = StockageMemoire().apply { jeton = "jeton-1" }
        val moteur = moteur(stockage)
        sur {
            moteur.identifier("Lou", "lou@exemple.fr", "42", mapOf("plan" to "Free"))
            moteur.ajouterDonnees(mapOf("plan" to "Pro", "panier" to 49.9))
            moteur.ajouterDonnees(mapOf("ancienne" to null))
        }
        Thread.sleep(600)
        assertEquals("un seul appel pour trois changements dans la seconde", 1, patchs().size)
        val (requete, texte) = patchs().single()
        assertEquals("Bearer jeton-1", requete.getHeader("Authorization"))
        assertEquals("hooky-android/1.0.0", requete.getHeader("X-Hooky-Client"))
        val corps = JSONObject(texte)
        assertEquals("Lou", corps.getString("visitor_name"))
        assertEquals("lou@exemple.fr", corps.getString("visitor_email"))
        assertEquals("42", corps.getString("visitor_user_id"))
        assertEquals("Google Pixel 8 · Android 15", corps.getString("visitor_device"))
        val donnees = corps.getJSONObject("visitor_attributes")
        assertEquals(setOf("plan", "panier", "ancienne"), donnees.keys().asSequence().toSet())
        assertEquals("Pro", donnees.getString("plan"))
        assertTrue(donnees.isNull("ancienne"))
        assertEquals("une conversation anonyme prend l'identifiant", "42", stockage.utilisateur)

        // Le même identify à l'ouverture suivante : rien de neuf, rien ne part.
        sur { moteur.identifier("Lou", "lou@exemple.fr", "42", mapOf("plan" to "Pro")) }
        Thread.sleep(400)
        assertEquals(1, patchs().size)

        // Seul ce qui change part.
        sur { moteur.ajouterDonnees(mapOf("plan" to "Team")) }
        Thread.sleep(400)
        assertEquals(2, patchs().size)
        val second = JSONObject(patchs().last().second)
        assertFalse(second.has("visitor_name"))
        assertEquals("Team", second.getJSONObject("visitor_attributes").getString("plan"))
    }

    @Test
    fun `un PATCH raté est réessayé avec le message suivant, sans erreur affichée`() {
        statutPatch = 503
        val moteur = moteur(StockageMemoire().apply { jeton = "jeton-1" })
        sur { moteur.ajouterDonnees(mapOf("plan" to "Pro")) }
        Thread.sleep(400)
        assertEquals(1, patchs().size)
        assertFalse(moteur.profilAEnvoyer.vide)
        assertNull(moteur.etat.value.erreur)

        statutPatch = 200
        sur { moteur.ajouterDonnees(mapOf("panier" to 12)) }
        assertTrue(sur { moteur.envoyer("Suite", emptyList()) })
        Thread.sleep(400)
        assertEquals("le réessai et le nouveau changement ensemble", 2, patchs().size)
        val donnees = JSONObject(patchs().last().second).getJSONObject("visitor_attributes")
        assertEquals("Pro", donnees.getString("plan"))
        assertEquals(12, donnees.getInt("panier"))
        assertTrue(moteur.profilAEnvoyer.vide)
    }

    @Test
    fun `un serveur sans la route (404) ne fait pas oublier la conversation`() {
        statutPatch = 404
        val stockage = StockageMemoire().apply { jeton = "jeton-1" }
        val moteur = moteur(stockage)
        sur { moteur.ajouterDonnees(mapOf("plan" to "Pro")) }
        Thread.sleep(400)
        assertEquals(1, patchs().size)
        assertEquals("jeton-1", stockage.jeton)
        assertTrue(moteur.aConversation)
    }

    @Test
    fun `un autre userId oublie la conversation, comme reset`() {
        val stockage = StockageMemoire().apply {
            jeton = "jeton-1"
            utilisateur = "42"
            brouillon = "Le brouillon de 42"
        }
        val moteur = moteur(stockage)

        // Le même compte : la conversation reste.
        assertFalse(sur { moteur.identifier("Lou", null, "42") })
        assertTrue(moteur.aConversation)

        // Un autre compte : page blanche, avec sa nouvelle identité.
        assertTrue(sur { moteur.identifier("Sam", "sam@exemple.fr", "43", mapOf("plan" to "Free")) })
        assertFalse(moteur.aConversation)
        assertNull(stockage.jeton)
        assertNull(stockage.utilisateur)
        assertNull(stockage.brouillon)
        assertEquals("", moteur.brouillon)
        assertEquals("Sam", moteur.nomVisiteur)
        assertEquals("43", moteur.utilisateur)
        assertEquals(mapOf("plan" to "Free"), moteur.donnees)
        Thread.sleep(400)
        assertTrue(patchs().isEmpty())

        // Son premier message ouvre une nouvelle conversation, à son nom.
        assertTrue(sur { moteur.envoyer("Bonjour", emptyList()) })
        assertEquals("43", ouverture().getString("visitor_user_id"))
        assertEquals("43", stockage.utilisateur)
    }

    @Test
    fun `reset oublie l'identifiant et les données libres`() {
        val stockage = StockageMemoire().apply { jeton = "jeton-1" }
        val moteur = moteur(stockage)
        sur {
            moteur.identifier(null, null, "42", mapOf("plan" to "Pro"))
            moteur.oublier(identite = true)
        }
        assertNull(moteur.utilisateur)
        assertTrue(moteur.donnees.isEmpty())
        assertTrue(moteur.profilAEnvoyer.vide)
        assertNull(stockage.utilisateur)
        assertFalse(moteur.identifie)
    }

    @Test
    fun `le formulaire est sauté quand l'e-mail est connu`() {
        val moteur = moteur()
        sur { moteur.identifier(null, null, "42") }
        assertFalse("un identifiant seul ne dit ni le prénom ni l'e-mail", moteur.identifie)
        sur { moteur.identifier(null, "lou@exemple.fr", "42") }
        assertTrue(moteur.identifie)
    }

    @Test
    fun `les données libres, chaînes, nombres et booléens, le reste écarté ou en texte`() {
        val propre = Profil.nettoyer(mapOf(
            "a" to "x", "b" to 3, "c" to 3L, "d" to 2.5f, "e" to true, "f" to null,
            "g" to listOf(1), "h" to mapOf("x" to 1), "i" to Double.NaN, "j" to Thread.State.NEW, "k" to 4.0,
        ))
        assertEquals(mapOf("a" to "x", "b" to 3L, "c" to 3L, "d" to 2.5, "e" to true, "f" to null, "j" to "NEW", "k" to 4L), propre)
    }

    @Test
    fun `le nom de l'appareil`() {
        assertEquals("Samsung SM-S918B · Android 15", Appareil.nom("samsung", "SM-S918B", "15"))
        assertEquals("Google Pixel 8 · Android 15", Appareil.nom("Google", "Pixel 8", "15"))
        assertEquals("OnePlus 9 Pro · Android 14", Appareil.nom("OnePlus", "OnePlus 9 Pro", "14"))
        assertEquals("Motorola Moto g(7) · Android 10", Appareil.nom("motorola", "moto g(7)", "10"))
        assertEquals("Xiaomi 2201123G · Android 13", Appareil.nom("Xiaomi", "2201123G", "13"))
        assertEquals("Android 15", Appareil.nom(null, null, "15"))
        assertEquals("Pixel 8 · Android", Appareil.nom("unknown", "Pixel 8", null))
    }
}

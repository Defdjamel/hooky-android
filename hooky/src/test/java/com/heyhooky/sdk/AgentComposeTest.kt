package com.heyhooky.sdk

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Ce que l'agent IA ajoute à l'écran, rendu sur la JVM par Robolectric : son
// étiquette, ses fiches, son lien, ses choix (dernier message seulement), la
// mention, « Parler à un humain », la carte de l'e-mail, et ce qu'entend
// TalkBack pendant une révélation.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h1600dp")
class AgentComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val site = Site("Lumen", "#5B4CF0", "right", null, agent = true)
    private val conversation = Conversation("j", "Lou", null, false, null, null, agent = true)

    private fun lignes(etat: EtatChat, textes: Textes = Textes("fr")) =
        MiseEnLignes.lignes(etat, textes, identifie = true, jour = { "Aujourd’hui" }, heure = { "12:00" })

    @Test
    fun `l'étiquette, les fiches, le lien et les choix de l'agent`() {
        val bot = Decodage.message(JSONObject(AgentTest.bot(2)))!!
        val etat = EtatChat(site = site, conversation = conversation, messages = listOf(bot))
        val choisis = ArrayList<String>()
        val fiches = ArrayList<String>()
        val actions = object : ActionsFil {
            override fun plusAnciens() = Unit
            override fun voirPhotos(pieces: List<PieceJointe>, index: Int) = Unit
            override fun ouvrirFichier(piece: PieceJointe, partager: Boolean) = Unit
            override fun fichierOccupe(piece: PieceJointe) = false
            override fun liensExpires() = Unit
            override var prenom: String = ""
            override var email: String = ""
            override fun versSaisie() = Unit
            override fun choisir(message: Message, option: ChoixRapide) { choisis += option.id }
            override fun ficheOuverte(url: String) { fiches += url }
        }
        compose.setContent {
            HookyTheme(site, Textes("fr")) {
                Column {
                    lignes(etat).forEach { LigneFil(it, 280.dp, actions) }
                    BarreAgent(etat) {}
                }
            }
        }
        compose.onNodeWithText("Assistant IA").assertIsDisplayed()
        compose.onNodeWithText("Voici deux lampes").assertIsDisplayed()
        compose.onNodeWithText("Lampe Nova").assertIsDisplayed()
        compose.onNodeWithText("49,90 €").assertIsDisplayed()
        compose.onNodeWithText("En stock").assertIsDisplayed()
        compose.onNodeWithText("Rupture de stock").assertIsDisplayed()
        compose.onNodeWithText("Livraison").assertIsDisplayed()
        compose.onNodeWithContentDescription("Réponses proposées").assertIsDisplayed()
        compose.onNodeWithText("Oui").performClick()
        assertEquals(listOf("a"), choisis)
        compose.onNodeWithContentDescription("Voir Lampe Nova").performClick()
        assertEquals(listOf("https://lumen.test/nova"), fiches)
        // L'agent a répondu : le bouton remplace la mention.
        compose.onNodeWithText("Parler à un humain").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Tu échanges avec un assistant IA. Un humain peut prendre le relais.").fetchSemanticsNodes().size)
    }

    @Test
    fun `les choix ne sont que sur le dernier message, la mention avant la réponse`() {
        val ancien = Decodage.message(JSONObject(AgentTest.bot(1, le = "2026-10-10T12:01:00Z")))!!
        val visiteur = Message(3, Expediteur.VISITEUR, null, "Merci", emptyList(), Decodage.date("2026-10-10T12:03:00Z")!!)
        val etat = EtatChat(site = site, conversation = conversation, messages = listOf(ancien, visiteur))
        compose.setContent {
            HookyTheme(site, Textes("en")) {
                Column {
                    lignes(etat, Textes("en")).forEach { LigneFil(it, 280.dp, sansActions) }
                    BarreAgent(EtatChat(site = site)) {}
                }
            }
        }
        compose.onNodeWithText("Livraison").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("Oui").fetchSemanticsNodes().size)
        compose.onNodeWithText("You’re chatting with an AI assistant. A human can take over.").assertIsDisplayed()
    }

    @Test
    fun `pendant une révélation, TalkBack entend le message entier`() {
        val bot = Decodage.message(JSONObject(AgentTest.bot(2, texte = "Voici deux lampes pour toi")))!!
        val revelation = Revelation(2, Agent.finsDesMots(bot.texte), bot.unites, motsMontres = 2, uniteDesChoix = bot.uniteDesChoix)
        val etat = EtatChat(site = site, conversation = conversation, messages = listOf(bot), revelation = revelation)
        compose.setContent {
            HookyTheme(site, Textes("fr")) {
                Column { lignes(etat).forEach { LigneFil(it, 280.dp, sansActions) } }
            }
        }
        compose.onNodeWithContentDescription("Voici deux lampes pour toi").assertIsDisplayed()
        // Les blocs attendent la fin du texte.
        assertEquals(0, compose.onAllNodesWithText("Lampe Nova").fetchSemanticsNodes().size)
    }

    @Test
    fun `la carte de l'e-mail au passage`() {
        var plusTard = 0
        compose.setContent {
            HookyTheme(site, Textes("fr")) {
                CarteEmailPassage(EtatEmailPassage.INVALIDE, envoyer = {}, modifie = {}, plusTard = { plusTard++ })
            }
        }
        compose.onNodeWithText("Laisse ton e-mail pour recevoir la réponse de l’équipe :").assertIsDisplayed()
        compose.onNodeWithText("Cette adresse e-mail ne semble pas valide.").assertIsDisplayed()
        compose.onNodeWithText("Plus tard").performClick()
        assertEquals(1, plusTard)
    }

    private val sansActions = object : ActionsFil {
        override fun plusAnciens() = Unit
        override fun voirPhotos(pieces: List<PieceJointe>, index: Int) = Unit
        override fun ouvrirFichier(piece: PieceJointe, partager: Boolean) = Unit
        override fun fichierOccupe(piece: PieceJointe) = false
        override fun liensExpires() = Unit
        override var prenom: String = ""
        override var email: String = ""
        override fun versSaisie() = Unit
    }
}

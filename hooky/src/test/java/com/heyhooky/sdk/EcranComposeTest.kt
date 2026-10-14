package com.heyhooky.sdk

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// L'interface Compose, rendue sur la JVM par Robolectric (aucun APK de test à
// installer sur un appareil) : la saisie et le fil, avec les textes et les
// descriptions qu'entend TalkBack.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EcranComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private val site = Site("Atelier Gecko", "#FF5B3A", "right", "Une question ?")

    @Test
    fun `la flèche d'envoi ne s'active qu'avec un texte, et envoie`() {
        var envois = 0
        compose.setContent {
            HookyTheme(site, Textes("en")) {
                var valeur by remember { mutableStateOf(TextFieldValue("")) }
                Saisie(
                    valeur = valeur,
                    surChangement = { valeur = it },
                    choisis = emptyList(),
                    retirer = {},
                    erreur = null,
                    occupe = false,
                    envoyer = { envois++ },
                    joindre = {},
                    focus = remember { FocusRequester() },
                )
            }
        }
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Your message").performTextInput("Bonjour")
        compose.onNodeWithContentDescription("Send").assertIsEnabled().performClick()
        assertEquals(1, envois)
        compose.onNodeWithText("Powered by hooky").assertIsDisplayed()
        compose.onNodeWithContentDescription("Attach a photo or a PDF").assertIsDisplayed()
    }

    @Test
    fun `une erreur de l'API s'affiche au-dessus de la saisie`() {
        compose.setContent {
            HookyTheme(site, Textes("fr")) {
                Saisie(TextFieldValue(""), {}, emptyList(), {}, Textes("fr").t("lourd_api"), false, {}, {}, remember { FocusRequester() })
            }
        }
        compose.onNodeWithText("Un fichier est trop lourd : 10 Mo au plus.").assertIsDisplayed()
    }

    @Test
    fun `le fil, l'auteur, les messages, le lien, « Lu » et « … écrit »`() {
        val midi = Decodage.date("2026-10-10T12:00:00Z")!!
        val conversation = Conversation("j", "Lou", null, false, null, null)
        val messages = listOf(
            Message(1, Expediteur.VISITEUR, "Lou", "Mon colis est arrivé abîmé", emptyList(), midi),
            Message(2, Expediteur.EQUIPE, "Camille", "Désolée ! Le retour : https://exemple.fr/retours", emptyList(), midi + 60_000),
            Message(3, Expediteur.VISITEUR, "Lou", "Merci !", emptyList(), midi + 120_000),
        )
        val etat = EtatChat(site = site, conversation = conversation, messages = messages, luParEquipe = midi + 180_000, equipeEcrit = "Camille")
        val textes = Textes("fr")
        val lignes = MiseEnLignes.lignes(etat, textes, identifie = false, jour = { "Aujourd’hui" }, heure = { "12:00" })
        compose.setContent {
            HookyTheme(site, textes) {
                Column { lignes.forEach { LigneFil(it, 280.dp, actionsVides) } }
            }
        }
        compose.onNodeWithText("Aujourd’hui").assertIsDisplayed()
        compose.onNodeWithText("Camille").assertIsDisplayed()
        compose.onNodeWithText("Mon colis est arrivé abîmé").assertIsDisplayed()
        compose.onNodeWithText("Désolée ! Le retour : https://exemple.fr/retours").assertIsDisplayed()
        compose.onNodeWithText("12:00 · Lu").assertIsDisplayed()
        compose.onNodeWithText("Camille écrit…").assertIsDisplayed()
    }

    @Test
    fun `avant la conversation, l'accueil du site et le formulaire`() {
        val textes = Textes("fr")
        val lignes = MiseEnLignes.lignes(EtatChat(site = site), textes, identifie = false, jour = { "" }, heure = { "" })
        compose.setContent {
            HookyTheme(site, textes) {
                Column { lignes.forEach { LigneFil(it, 280.dp, actionsVides) } }
            }
        }
        compose.onNodeWithText("Une question ?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ton prénom").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ton e-mail").assertIsDisplayed()
    }

    @Test
    fun `la traduction sous un message de l'équipe, Traduire puis Traduit de l'anglais · Voir l'original`() {
        val midi = Decodage.date("2026-10-10T12:00:00Z")!!
        val conversation = Conversation("j", "Lou", null, false, null, null)
        val anglais = Message(2, Expediteur.EQUIPE, "Sam", "Your order shipped.", emptyList(), midi, langue = "en")
        val francais = Message(3, Expediteur.EQUIPE, "Sam", "Bonne journée", emptyList(), midi + 60_000, langue = "fr")
        val textes = Textes("fr")
        var etat by mutableStateOf(EtatChat(site = site, conversation = conversation, messages = listOf(anglais, francais)))
        val demandes = mutableListOf<Long>()
        val actions = object : ActionsFil by actionsVides {
            override fun traduire(message: Message) {
                demandes += message.id
            }
            override fun basculerOriginal(message: Message) {
                val t = etat.traductions[message.id] as EtatTraduction.Faite
                etat = etat.copy(traductions = mapOf(message.id to t.copy(original = !t.original)))
            }
        }
        compose.setContent {
            HookyTheme(site, textes) {
                val lignes = MiseEnLignes.lignes(etat, textes, identifie = true, jour = { "" }, heure = { "" },
                    traduisible = { RegleTraduction.traduisible(it, "fr") })
                Column { lignes.forEach { LigneFil(it, 280.dp, actions) } }
            }
        }
        // Un seul lien : le message en français n'en a pas.
        compose.onNodeWithText("Traduire").assertIsDisplayed().performClick()
        assertEquals(listOf(2L), demandes)

        etat = etat.copy(traductions = mapOf(2L to EtatTraduction.EnCours))
        compose.onNodeWithText("Traduction…").assertIsDisplayed()

        etat = etat.copy(traductions = mapOf(2L to EtatTraduction.Echec))
        compose.onNodeWithText("Traduction impossible.").assertIsDisplayed()
        compose.onNodeWithText("Réessayer").performClick()
        assertEquals(listOf(2L, 2L), demandes)

        etat = etat.copy(traductions = mapOf(2L to EtatTraduction.Faite("Ta commande est partie.", "en")))
        compose.onNodeWithText("Ta commande est partie.").assertIsDisplayed()
        compose.onNodeWithText("Traduit de l’anglais ·").assertIsDisplayed()
        compose.onNodeWithText("Voir l’original").performClick()
        compose.onNodeWithText("Your order shipped.").assertIsDisplayed()
        compose.onNodeWithText("Voir la traduction").assertIsDisplayed()
    }

    @Test
    fun `le bouton Toujours traduire de l'en-tête, un interrupteur, d'un toucher`() {
        var actif by mutableStateOf(true)
        compose.setContent {
            HookyTheme(site, Textes("fr")) { BoutonTraduction(actif) { actif = it } }
        }
        val bouton = compose.onNodeWithContentDescription("Toujours traduire")
        bouton.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOn()
        bouton.performClick()
        assertEquals(false, actif)
        bouton.assertIsOff()
        bouton.performClick()
        bouton.assertIsOn()
    }

    private val actionsVides = object : ActionsFil {
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

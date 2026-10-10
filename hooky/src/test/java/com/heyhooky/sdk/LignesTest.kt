package com.heyhooky.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// La mise en lignes du fil (comme threadNodes() de widget.js), les fichiers
// choisis, les textes et les couleurs.
class LignesTest {
    private val textes = Textes("fr")
    private val midi = Decodage.date("2026-10-10T12:00:00Z")!!

    private fun message(id: Long, expediteur: Expediteur, minutes: Long, texte: String = "m$id", auteur: String? = null) =
        Message(id, expediteur, auteur, texte, emptyList(), midi + minutes * 60_000)

    private fun lignes(etat: EtatChat, identifie: Boolean = false) =
        MiseEnLignes.lignes(etat, textes, identifie, jour = { "Jour" }, heure = { "12:00" })

    @Test
    fun `avant la conversation, l'accueil du site puis le formulaire`() {
        val site = Site("Boutique", "#FF5B3A", "right", "Une question ?")
        val sans = lignes(EtatChat(site = site))
        assertEquals(listOf(Ligne.Intro("Une question ?"), Ligne.Carte), sans)
        // identify() a déjà dit qui c'est : pas de formulaire.
        assertEquals(listOf(Ligne.Intro("Une question ?")), lignes(EtatChat(site = site), identifie = true))
        // Sans accueil, le texte de la bulle web.
        assertEquals(Ligne.Intro(textes.t("accueil")), lignes(EtatChat(site = site.copy(accueil = null))).first())
    }

    @Test
    fun `les messages se groupent en séries, l'auteur en tête, l'heure à la fin`() {
        val conversation = Conversation("j", null, null, false, null, null)
        val messages = listOf(
            message(1, Expediteur.VISITEUR, 0),
            message(2, Expediteur.VISITEUR, 1),
            message(3, Expediteur.EQUIPE, 2, auteur = "Sam"),
            message(4, Expediteur.EQUIPE, 3, auteur = "Sam"),
            // 10 minutes plus tard : une nouvelle série.
            message(5, Expediteur.EQUIPE, 13, auteur = "Sam"),
        )
        val sortie = lignes(EtatChat(conversation = conversation, messages = messages))
        val types = sortie.map { it::class.simpleName }
        assertEquals(
            listOf("Jour", "Bulle", "Bulle", "Heure", "Auteur", "Bulle", "Bulle", "Heure", "Auteur", "Bulle", "Heure"),
            types,
        )
        val bulles = sortie.filterIsInstance<Ligne.Bulle>()
        assertEquals(listOf(false, true, false, true, false), bulles.map { it.suite })
        assertEquals(listOf(false, true, false, true, true), bulles.map { it.fin })
    }

    @Test
    fun `« Lu » sous le dernier message du visiteur, quand l'équipe l'a lu`() {
        val m = message(1, Expediteur.VISITEUR, 0)
        val conversation = Conversation("j", null, null, false, null, null)
        val pasLu = lignes(EtatChat(conversation = conversation, messages = listOf(m)))
        assertEquals("12:00", pasLu.filterIsInstance<Ligne.Heure>().single().texte)
        val lu = lignes(EtatChat(conversation = conversation, messages = listOf(m), luParEquipe = m.creeLe + 1000))
        assertEquals("12:00 · Lu", lu.filterIsInstance<Ligne.Heure>().single().texte)
    }

    @Test
    fun `le message en route disparaît s'il est déjà arrivé par le temps réel`() {
        val conversation = Conversation("j", null, null, false, null, null)
        val envoi = EnvoiEnCours("Salut", emptyList(), null, midi)
        val enRoute = lignes(EtatChat(conversation = conversation, messages = listOf(message(1, Expediteur.EQUIPE, -5)), envoi = envoi))
        assertTrue(enRoute.last() is Ligne.Envoi)
        val arrive = lignes(EtatChat(conversation = conversation, messages = listOf(message(2, Expediteur.VISITEUR, 0, "Salut")), envoi = envoi))
        assertTrue(arrive.none { it is Ligne.Envoi })
    }

    @Test
    fun `« écrit » et conversation fermée`() {
        val conversation = Conversation("j", null, null, true, null, null)
        val sortie = lignes(EtatChat(conversation = conversation, messages = listOf(message(1, Expediteur.EQUIPE, 0)), equipeEcrit = "Sam"))
        assertEquals(Ligne.Frappe("Sam écrit…"), sortie[sortie.size - 2])
        assertEquals(Ligne.Fermee, sortie.last())
    }

    @Test
    fun `seuls les messages de 1 à 3 emoji s'affichent en grand`() {
        assertEquals(1, MiseEnLignes.nombreEmoji("👍"))
        assertEquals(3, MiseEnLignes.nombreEmoji("🎉 🎉 🎉"))
        assertEquals(1, MiseEnLignes.nombreEmoji("❤️"))
        assertEquals(0, MiseEnLignes.nombreEmoji("👍👍👍👍"))
        assertEquals(0, MiseEnLignes.nombreEmoji("ok 👍"))
        assertEquals(0, MiseEnLignes.nombreEmoji("123"))
    }

    @Test
    fun `les liens sont repérés sans la ponctuation qui suit`() {
        val texte = "Voir https://heyhooky.com/aide. Et http://a.b/c?d=1, merci"
        val liens = MiseEnLignes.liens(texte).map { texte.substring(it) }
        assertEquals(listOf("https://heyhooky.com/aide", "http://a.b/c?d=1"), liens)
    }

    @Test
    fun `les fichiers refusés avant l'envoi, avec la raison`() {
        fun fichier(nom: String, type: String, taille: Long) = FichierChoisi(nom, type, taille, null) { null }
        val (liste, erreur) = ajouterFichiers(
            emptyList(),
            listOf(
                fichier("a.jpg", "image/jpeg", 1000),
                fichier("b.zip", "application/zip", 1000),
                fichier("c.pdf", "application/pdf", 11L * 1024 * 1024),
                fichier("d.heic", "", 1000),
            ),
            textes,
        )
        assertEquals(listOf("a.jpg", "d.heic"), liste.map { it.nom })
        assertEquals(textes.t("refuse", "nom" to "b.zip"), erreur)

        val cinq = List(5) { fichier("p$it.png", "image/png", 10) }
        val (quatre, trop) = ajouterFichiers(emptyList(), cinq, textes)
        assertEquals(4, quatre.size)
        assertEquals(textes.t("quatre"), trop)
    }

    @Test
    fun `les textes suivent la langue de l'appareil, l'anglais sinon`() {
        assertEquals("fr", Textes.pour(java.util.Locale.CANADA_FRENCH).langue)
        assertEquals("en", Textes.pour(java.util.Locale.JAPAN).langue)
        assertEquals("Sam is typing…", Textes("en").t("ecrit", "nom" to "Sam"))
        assertEquals("Lu", Textes("fr").t("lu"))
        // Toutes les langues ont toutes les clés du français.
        for (langue in Textes.LANGUES) {
            for (cle in listOf("appareil", "horsligne", "ferme", "email_invalide", "partager", "aujourdhui", "non_lus")) {
                assertTrue("$langue/$cle", Textes(langue).t(cle) != cle)
            }
        }
    }

    @Test
    fun `le texte sur l'accent se lit (contraste, comme inkOn)`() {
        assertEquals(androidx.compose.ui.graphics.Color.White, Palette.surAccent(Palette.hex("#8B6CFF")))
        assertEquals(Palette.ENCRE, Palette.surAccent(Palette.hex("#C6F432")))
        assertEquals(Palette.LILAS, Palette.hex("pas une couleur"))
    }

    @Test
    fun `les tailles à la façon de la langue`() {
        assertEquals("812 Ko", taille(812 * 1024, Textes("fr")))
        assertEquals("3,4 Mo", taille((3.4 * 1024 * 1024).toLong(), Textes("fr")))
        assertEquals("3.4 MB", taille((3.4 * 1024 * 1024).toLong(), Textes("en")))
    }
}

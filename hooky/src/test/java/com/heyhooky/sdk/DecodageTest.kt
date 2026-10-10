package com.heyhooky.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Le décodage des réponses de l'API, sur des exemples tirés de
// Conversation#visitor_payload, Message#payload et MessageAttachment#payload.
class DecodageTest {

    private val fil = """
        {
          "conversation": {"token": "abc123", "visitor_name": "Lou", "visitor_email": null, "closed": false,
                           "team_read_at": "2026-10-10T14:02:03.123+02:00", "created_at": "2026-10-10T12:00:00.000Z"},
          "messages": [
            {"id": 7, "sender": "visitor", "author_name": "Lou", "body": "Bonjour", "attachments": [],
             "created_at": "2026-10-10T12:00:00.000Z"},
            {"id": 9, "sender": "team", "author_name": "Sam", "body": "",
             "attachments": [
               {"id": 3, "filename": "photo.jpg", "content_type": "image/jpeg", "byte_size": 52311, "kind": "image",
                "url": "https://r2/x.jpg", "thumbnail_url": "https://r2/x-480.jpg", "width": 1600, "height": 1200},
               {"id": 4, "filename": "devis.pdf", "content_type": "application/pdf", "byte_size": 812000, "kind": "file",
                "url": "https://r2/devis.pdf", "thumbnail_url": null, "width": null, "height": null}
             ],
             "created_at": "2026-10-10T12:05:00Z"}
          ],
          "has_more": true
        }
    """.trimIndent()

    @Test
    fun `un fil complet se décode`() {
        val resultat = Decodage.fil(JSONObject(fil), recuLe = 42)
        assertEquals("abc123", resultat.conversation.jeton)
        assertEquals("Lou", resultat.conversation.nomVisiteur)
        assertNull(resultat.conversation.emailVisiteur)
        assertFalse(resultat.conversation.fermee)
        assertTrue(resultat.encore)
        assertEquals(2, resultat.messages.size)

        val (visiteur, equipe) = resultat.messages
        assertEquals(Expediteur.VISITEUR, visiteur.expediteur)
        assertEquals("Bonjour", visiteur.texte)
        assertEquals(42, visiteur.recuLe)
        assertEquals(Expediteur.EQUIPE, equipe.expediteur)
        assertEquals("Sam", equipe.auteur)

        val (photo, pdf) = equipe.pieces
        assertTrue(photo.image)
        assertEquals("https://r2/x-480.jpg", photo.miniature)
        assertEquals(1600, photo.largeur)
        assertFalse(pdf.image)
        assertNull(pdf.miniature)
        assertNull(pdf.largeur)
        assertEquals(812000, pdf.taille)
    }

    @Test
    fun `les dates de Rails passent en UTC, fuseau compris`() {
        assertEquals(1_791_633_600_000, Decodage.date("2026-10-10T12:00:00Z"))
        assertEquals(1_791_633_600_000, Decodage.date("2026-10-10T14:00:00.000+02:00"))
        assertEquals(1_791_633_600_123, Decodage.date("2026-10-10T12:00:00.123456Z"))
        assertEquals(1_791_633_600_000, Decodage.date("2026-10-10T07:00:00-0500"))
        assertNull(Decodage.date("hier"))
        assertNull(Decodage.date(null))
    }

    @Test
    fun `le site et ses valeurs par défaut`() {
        val site = Decodage.site(JSONObject("""{"site": {"name": "Boutique", "color": "#FF5B3A", "position": "left", "greeting": ""}}"""))
        assertEquals("Boutique", site.nom)
        assertEquals("#FF5B3A", site.couleur)
        assertEquals("left", site.position)
        assertNull("un accueil vide vaut pas d'accueil", site.accueil)

        val vide = Decodage.site(JSONObject("""{"site": {}}"""))
        assertEquals("right", vide.position)
    }

    @Test
    fun `les événements du temps réel`() {
        val message = Decodage.evenement(JSONObject("""{"type": "message", "message": {"id": 1, "sender": "team", "body": "Salut", "attachments": [], "created_at": "2026-10-10T12:00:00Z"}}"""))
        assertTrue(message is EvenementConversation.NouveauMessage)

        val ecrit = Decodage.evenement(JSONObject("""{"type": "typing", "sender": "team", "author_name": "Sam"}"""))
        assertEquals(EvenementConversation.Ecrit(Expediteur.EQUIPE, "Sam"), ecrit)

        val lu = Decodage.evenement(JSONObject("""{"type": "read", "sender": "team", "read_at": "2026-10-10T12:00:00Z"}"""))
        assertEquals(EvenementConversation.Lu(Expediteur.EQUIPE, 1_791_633_600_000), lu)

        assertNull(Decodage.evenement(JSONObject("""{"type": "conversation"}""")))
    }

    @Test
    fun `le code d'une erreur`() {
        assertEquals("file_too_large", Decodage.codeErreur("""{"error":"file_too_large"}"""))
        assertNull(Decodage.codeErreur("<html>502</html>"))
        assertNull(Decodage.codeErreur(""))
    }
}

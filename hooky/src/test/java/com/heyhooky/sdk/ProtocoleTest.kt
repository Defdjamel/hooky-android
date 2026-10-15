package com.heyhooky.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Les trames d'Action Cable (actioncable-v1-json) et le délai de reconnexion.
class ProtocoleTest {

    @Test
    fun `l'abonnement à ConversationChannel`() {
        val trame = JSONObject(Protocole.abonnement())
        assertEquals("subscribe", trame.getString("command"))
        val identifiant = JSONObject(trame.getString("identifier"))
        assertEquals("ConversationChannel", identifiant.getString("channel"))
        assertEquals(0, identifiant.getInt("id"))
    }

    @Test
    fun `« en train d'écrire » est une action typing`() {
        val trame = JSONObject(Protocole.frappe())
        assertEquals("message", trame.getString("command"))
        assertEquals("typing", JSONObject(trame.getString("data")).getString("action"))
    }

    @Test
    fun `l'adresse du WebSocket porte le jeton, le client et l'app`() {
        val url = Protocole.adresse("https://api.heyhooky.com", "jeton+/=", "hooky-android/1.0.1", "com.app 2.0 (3)")
        assertEquals("api.heyhooky.com", url.host)
        assertEquals("/cable", url.encodedPath)
        assertEquals("jeton+/=", url.queryParameter("conversation_token"))
        assertEquals("hooky-android/1.0.1", url.queryParameter("client"))
        assertEquals("com.app 2.0 (3)", url.queryParameter("app"))
    }

    @Test
    fun `les trames de service`() {
        assertEquals(Protocole.Trame.Bienvenue, Protocole.lire("""{"type":"welcome"}"""))
        assertEquals(Protocole.Trame.Ping, Protocole.lire("""{"type":"ping","message":1760000000}"""))
        assertEquals(Protocole.Trame.Confirme, Protocole.lire("""{"identifier":"{\"channel\":\"ConversationChannel\",\"id\":0}","type":"confirm_subscription"}"""))
        assertEquals(Protocole.Trame.Refuse, Protocole.lire("""{"identifier":"x","type":"reject_subscription"}"""))
        assertEquals(Protocole.Trame.Deconnexion(false, "unauthorized"), Protocole.lire("""{"type":"disconnect","reason":"unauthorized","reconnect":false}"""))
        assertEquals(Protocole.Trame.Inconnue, Protocole.lire("pas du json"))
    }

    @Test
    fun `un message du canal, l'identifiant comparé par son contenu`() {
        // Rails peut réécrire l'identifiant (ordre des clés, espaces).
        val texte = """{"identifier":"{\"id\": 0, \"channel\": \"ConversationChannel\"}","message":{"type":"typing","sender":"team","author_name":"Sam"}}"""
        val trame = Protocole.lire(texte)
        assertTrue(trame is Protocole.Trame.Evenement)
        assertEquals(EvenementConversation.Ecrit(Expediteur.EQUIPE, "Sam"), (trame as Protocole.Trame.Evenement).evenement)

        val autre = """{"identifier":"{\"channel\":\"InboxChannel\"}","message":{"type":"typing","sender":"team"}}"""
        assertEquals(Protocole.Trame.Inconnue, Protocole.lire(autre))
    }

    @Test
    fun `la reconnexion double jusqu'à 30 s, puis repart de 1 s`() {
        val reconnexion = Reconnexion()
        val delais = List(7) { reconnexion.suivant() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delais)
        reconnexion.reussie()
        assertEquals(1_000L, reconnexion.suivant())
    }

    @Test
    fun `les en-têtes restent en ASCII`() {
        assertEquals("com.app 2.0 ? (3)", Identite.ascii("com.app 2.0 β (3)"))
    }
}

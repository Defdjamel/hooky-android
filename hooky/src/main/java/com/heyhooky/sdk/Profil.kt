package com.heyhooky.sdk

import org.json.JSONObject

// Ce que l'app dit du visiteur (Hooky.identify, Hooky.setAttributes) et que
// le serveur ne sait pas encore : envoyé avec le premier message, puis par
// PATCH /v1/widget/conversation, regroupé (un appel pour plusieurs
// changements dans la seconde). Un champ à null reste inchangé ; une donnée
// libre à null est supprimée côté serveur.
internal data class Profil(
    val nom: String? = null,
    val email: String? = null,
    val utilisateur: String? = null,
    val donnees: Map<String, Any?> = emptyMap(),
) {
    val vide: Boolean get() = nom == null && email == null && utilisateur == null && donnees.isEmpty()

    // Les plus récents l'emportent, les autres restent.
    fun fusion(recent: Profil): Profil = Profil(
        nom = recent.nom ?: nom,
        email = recent.email ?: email,
        utilisateur = recent.utilisateur ?: utilisateur,
        donnees = donnees + recent.donnees,
    )

    // Le corps du PATCH : visitor_device en plus, toujours (une app mise à
    // jour, un système aussi).
    fun json(appareil: String?): JSONObject {
        val corps = JSONObject()
        nom?.let { corps.put("visitor_name", it) }
        email?.let { corps.put("visitor_email", it) }
        utilisateur?.let { corps.put("visitor_user_id", it) }
        if (donnees.isNotEmpty()) corps.put("visitor_attributes", json(donnees))
        appareil?.let { corps.put("visitor_device", it) }
        return corps
    }

    companion object {
        private object Ecarte

        // Chaîne, nombre ou booléen ; null pour supprimer. Les nombres sont
        // ramenés à Long ou Double (12 et 12L sont la même valeur) ; un
        // tableau ou un objet, que le serveur ignorerait, n'est pas envoyé ;
        // le reste (une énumération, une date) part en texte.
        private fun valeur(v: Any?): Any? = when (v) {
            null, is String, is Boolean -> v
            is Byte, is Short, is Int, is Long -> v.toLong()
            is Number -> {
                val d = v.toDouble()
                when {
                    !d.isFinite() -> Ecarte
                    d == Math.rint(d) && kotlin.math.abs(d) < 1e15 -> d.toLong()
                    else -> d
                }
            }
            is Collection<*>, is Map<*, *>, is Array<*>, is JSONObject, is org.json.JSONArray -> Ecarte
            else -> v.toString()
        }

        fun nettoyer(donnees: Map<String, Any?>): Map<String, Any?> {
            val propre = LinkedHashMap<String, Any?>()
            donnees.forEach { (cle, v) ->
                val x = valeur(v)
                if (x !== Ecarte) propre[cle] = x
            }
            return propre
        }

        fun json(donnees: Map<String, Any?>): JSONObject {
            val objet = JSONObject()
            donnees.forEach { (cle, v) -> objet.put(cle, v ?: JSONObject.NULL) }
            return objet
        }
    }
}

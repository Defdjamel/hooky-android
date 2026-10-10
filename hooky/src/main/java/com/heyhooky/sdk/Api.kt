package com.heyhooky.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Une réponse d'erreur de l'API : le statut HTTP (0 si le réseau a manqué)
// et le code d'erreur rendu par Rails ({ "error": "file_too_large" }).
internal class ErreurApi(val statut: Int, val code: String?) : IOException("HTTP $statut ${code ?: ""}".trim())

// Qui appelle : envoyé sur chaque requête et à l'ouverture du WebSocket.
internal data class Identite(val client: String, val app: String) {
    companion object {
        // Un en-tête HTTP ne porte que de l'ASCII visible : un nom de version
        // exotique (« 2.0 β ») ne doit pas faire échouer la requête.
        fun ascii(texte: String): String = texte.map { if (it in ' '..'~') it else '?' }.joinToString("")
    }
}

// Un fichier à envoyer, lu au moment de l'envoi (jamais chargé en entier en
// mémoire). taille vaut -1 si on ne la connaît pas.
internal class FichierAEnvoyer(
    val nom: String,
    val type: String,
    val taille: Long,
    val ouvrir: () -> InputStream?,
)

// Les routes de la bulle (back/config/routes.rb, namespace widget), avec
// OkHttp. Les appels se font hors du fil principal.
internal class Api(
    val base: String,
    clientHttp: OkHttpClient,
    private val identite: Identite,
) {
    private val racine: HttpUrl = base.trimEnd('/').toHttpUrl()

    val client: OkHttpClient = clientHttp.newBuilder()
        .addInterceptor(Interceptor { chaine ->
            chaine.proceed(
                chaine.request().newBuilder()
                    .header("X-Hooky-Client", Identite.ascii(identite.client))
                    .header("X-Hooky-App", Identite.ascii(identite.app))
                    .apply { if (chaine.request().header("Accept") == null) header("Accept", "application/json") }
                    .build()
            )
        })
        .build()

    suspend fun site(cleSite: String): Site {
        val url = adresse("v1/widget/site").newBuilder().addQueryParameter("site_key", cleSite).build()
        return Decodage.site(json(Request.Builder().url(url).get().build()))
    }

    // Le premier message ouvre la conversation et rend son jeton.
    suspend fun ouvrir(
        cleSite: String,
        texte: String,
        nom: String?,
        email: String?,
        langue: String,
        fuseau: String?,
        page: String?,
        fichiers: List<FichierAEnvoyer>,
        progres: ((Float) -> Unit)? = null,
    ): Fil {
        val champs = linkedMapOf<String, String?>(
            "site_key" to cleSite,
            "body" to texte,
            "page_url" to page,
            "visitor_name" to nom?.takeIf { it.isNotBlank() },
            "visitor_email" to email?.takeIf { it.isNotBlank() },
            "locale" to langue,
            "time_zone" to fuseau,
        )
        val requete = Request.Builder().url(adresse("v1/widget/conversations")).post(corps(champs, fichiers, progres)).build()
        return Decodage.fil(json(requete))
    }

    suspend fun envoyer(jeton: String, texte: String, fichiers: List<FichierAEnvoyer>, progres: ((Float) -> Unit)? = null): Message {
        val requete = Request.Builder()
            .url(adresse("v1/widget/conversation/messages"))
            .header("Authorization", "Bearer $jeton")
            .post(corps(linkedMapOf("body" to texte), fichiers, progres))
            .build()
        val reponse = json(requete)
        return reponse.optJSONObject("message")?.let { Decodage.message(it) } ?: throw ErreurApi(500, "invalid_response")
    }

    // La dernière page du fil, ou celle d'avant le message « avant ».
    suspend fun fil(jeton: String, avant: Long? = null): Fil {
        val url = adresse("v1/widget/conversation").newBuilder()
            .apply { if (avant != null) addQueryParameter("before", avant.toString()) }
            .build()
        return Decodage.fil(json(Request.Builder().url(url).header("Authorization", "Bearer $jeton").get().build()))
    }

    suspend fun marquerLu(jeton: String) {
        val requete = Request.Builder()
            .url(adresse("v1/widget/conversation/read"))
            .header("Authorization", "Bearer $jeton")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        withContext(Dispatchers.IO) { executer(requete).close() }
    }

    // Hooky.track : envoyé sans attendre, une erreur reste muette.
    fun suivre(cleSite: String, nom: String, valeur: Double?, proprietes: Map<String, Any?>) {
        try {
            val corps = JSONObject().put("site_key", cleSite).put("name", nom)
            if (valeur != null && valeur.isFinite()) corps.put("value", valeur)
            if (proprietes.isNotEmpty()) {
                val details = JSONObject()
                proprietes.forEach { (cle, v) ->
                    details.put(cle, when (v) {
                        null -> JSONObject.NULL
                        is Number, is Boolean, is String -> v
                        else -> v.toString()
                    })
                }
                corps.put("properties", details)
            }
            val requete = Request.Builder().url(adresse("v1/widget/events")).post(corps.toString().toRequestBody(JSON)).build()
            client.newCall(requete).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = Unit
                override fun onResponse(call: Call, response: Response) = response.close()
            })
        } catch (_: Exception) {
            // Une adresse ou une propriété impossible : tant pis, comme le web.
        }
    }

    private fun adresse(chemin: String): HttpUrl = racine.newBuilder().addPathSegments(chemin).build()

    // En JSON sans fichier ; en multipart avec (files[]), avec la progression.
    private fun corps(champs: Map<String, String?>, fichiers: List<FichierAEnvoyer>, progres: ((Float) -> Unit)?): RequestBody {
        if (fichiers.isEmpty()) {
            val json = JSONObject()
            champs.forEach { (cle, valeur) -> if (valeur != null) json.put(cle, valeur) }
            return json.toString().toRequestBody(JSON)
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        champs.forEach { (cle, valeur) -> if (!valeur.isNullOrEmpty()) multipart.addFormDataPart(cle, valeur) }
        fichiers.forEach { fichier ->
            multipart.addFormDataPart("files[]", fichier.nom, CorpsFichier(fichier))
        }
        val tout = multipart.build()
        return if (progres == null) tout else CorpsCompte(tout, progres)
    }

    // La réponse se lit hors du fil principal (le corps arrive du réseau).
    private suspend fun json(requete: Request): JSONObject = withContext(Dispatchers.IO) {
        val texte = executer(requete).use { it.body?.string().orEmpty() }
        try {
            if (texte.isBlank()) JSONObject() else JSONObject(texte)
        } catch (_: Exception) {
            throw ErreurApi(500, "invalid_response")
        }
    }

    // La réponse si elle est bonne ; sinon ErreurApi, avec le code de Rails.
    private suspend fun executer(requete: Request): Response {
        val reponse = try {
            client.newCall(requete).attendre()
        } catch (e: ErreurApi) {
            throw e
        } catch (e: IOException) {
            throw ErreurApi(0, null)
        }
        if (reponse.isSuccessful) return reponse
        val corps = reponse.use { it.body?.string() }
        throw ErreurApi(reponse.code, Decodage.codeErreur(corps))
    }

    companion object {
        val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
    }
}

// Un appel OkHttp attendu sans bloquer ; annulé avec la coroutine.
internal suspend fun Call.attendre(): Response = suspendCancellableCoroutine { suite ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (suite.isActive) suite.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (suite.isActive) suite.resume(response) else response.close()
        }
    })
    suite.invokeOnCancellation { cancel() }
}

// Le contenu d'un fichier, lu au fil de l'envoi.
private class CorpsFichier(private val fichier: FichierAEnvoyer) : RequestBody() {
    override fun contentType(): MediaType? = fichier.type.toMediaTypeOrNull()
    override fun contentLength(): Long = fichier.taille
    override fun writeTo(sink: BufferedSink) {
        val flux = fichier.ouvrir() ?: throw IOException("unreadable")
        flux.source().use { sink.writeAll(it) }
    }
}

// Compte les octets partis pour la barre de progression (0 à 1).
private class CorpsCompte(private val corps: RequestBody, private val progres: (Float) -> Unit) : RequestBody() {
    override fun contentType(): MediaType? = corps.contentType()
    override fun contentLength(): Long = corps.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var envoyes = 0L
        var dernier = -1
        val compteur = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                envoyes += byteCount
                if (total > 0) {
                    val pourcent = (envoyes * 100 / total).toInt()
                    if (pourcent != dernier) {
                        dernier = pourcent
                        progres(envoyes.toFloat() / total)
                    }
                }
            }
        }.buffer()
        corps.writeTo(compteur)
        compteur.flush()
    }
}

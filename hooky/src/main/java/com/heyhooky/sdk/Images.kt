package com.heyhooky.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// Les miniatures et les photos du fil, sans Coil ni Glide : téléchargées
// avec OkHttp, réduites au décodage à la taille d'affichage, gardées dans un
// cache mémoire (un huitième de la mémoire de l'app au plus).
//
// La clé d'une image ne dépend pas de son lien (signé, il change à chaque
// relecture du fil) mais de la pièce jointe : une photo déjà vue ne se
// retélécharge pas quand ses liens se renouvellent.
@SuppressLint("StaticFieldLeak")
internal object Images {
    private var contexte: Context? = null
    private var client: OkHttpClient? = null
    private val enCours = HashMap<String, CompletableDeferred<Bitmap?>>()

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun initialiser(context: Context, clientHttp: OkHttpClient) {
        contexte = context.applicationContext
        client = clientHttp
    }

    fun enCache(cle: String): Bitmap? = cache.get(cle)

    // Une image d'Internet (url) ou de l'appareil (content://…), réduite pour
    // tenir dans cote × cote pixels. Deux demandes de la même image n'en
    // font qu'un téléchargement. À appeler depuis le fil principal.
    suspend fun charger(cle: String, adresse: String, cote: Int): Bitmap? {
        cache.get(cle)?.let { return it }
        enCours[cle]?.let { return it.await() }
        val attente = CompletableDeferred<Bitmap?>()
        enCours[cle] = attente
        val bitmap = try {
            withContext(Dispatchers.IO) { decoder(lire(adresse) ?: return@withContext null, cote, adresse) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        if (bitmap != null) cache.put(cle, bitmap)
        enCours.remove(cle)
        attente.complete(bitmap)
        return bitmap
    }

    // Les octets d'une image : la plus grosse fait quelques Mo.
    private fun lire(adresse: String): ByteArray? {
        if (adresse.startsWith("content:") || adresse.startsWith("file:")) {
            val resolver = contexte?.contentResolver ?: return null
            return resolver.openInputStream(Uri.parse(adresse))?.use { it.readBytes() }
        }
        val client = client ?: return null
        client.newCall(Request.Builder().url(adresse).build()).execute().use { reponse ->
            if (!reponse.isSuccessful) return null
            return reponse.body?.bytes()
        }
    }

    private fun decoder(octets: ByteArray, cote: Int, adresse: String): Bitmap? {
        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(octets, 0, octets.size, bornes)
        if (bornes.outWidth <= 0 || bornes.outHeight <= 0) return null
        var echantillon = 1
        while (maxOf(bornes.outWidth, bornes.outHeight) / (echantillon * 2) >= cote) echantillon *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = echantillon }
        val bitmap = BitmapFactory.decodeByteArray(octets, 0, octets.size, options) ?: return null
        // Une photo de l'appareil garde son orientation dans l'EXIF (le serveur
        // la redresse à la réception) : l'aperçu la redresse aussi.
        if (!adresse.startsWith("content:") && !adresse.startsWith("file:")) return bitmap
        return redresser(bitmap, octets)
    }

    private fun redresser(bitmap: Bitmap, octets: ByteArray): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return bitmap
        val orientation = try {
            ExifInterface(octets.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: Exception) {
            return bitmap
        }
        val angle = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrice = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrice, true)
    }
}

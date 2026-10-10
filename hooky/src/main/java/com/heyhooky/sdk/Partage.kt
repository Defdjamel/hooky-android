package com.heyhooky.sdk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Les échanges de fichiers avec le reste du téléphone : ce que le visiteur
// choisit (photothèque, appareil photo, fichiers) et les pièces reçues qu'il
// ouvre ou partage. Tout passe par le cache de l'app (dossier hooky/) et
// notre FileProvider.
internal object Partage {

    fun autorite(context: Context): String = context.packageName + ".hooky.fichiers"

    // Un document choisi (content://…) : son nom, son type et sa taille.
    fun choisi(context: Context, uri: Uri): FichierChoisi {
        val resolver = context.contentResolver
        var nom: String? = null
        var taille = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { curseur ->
                if (curseur.moveToFirst()) {
                    val n = curseur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val t = curseur.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !curseur.isNull(n)) nom = curseur.getString(n)
                    if (t >= 0 && !curseur.isNull(t)) taille = curseur.getLong(t)
                }
            }
        } catch (_: Exception) {
            // Un fournisseur capricieux : on garde ce qu'on a.
        }
        val type = resolver.getType(uri) ?: typeDepuisNom(nom ?: "")
        val nomFinal = nom ?: ("photo." + (type.substringAfter('/', "jpg").takeIf { it.length <= 4 } ?: "jpg"))
        return FichierChoisi(nomFinal, type, taille, uri.toString()) { resolver.openInputStream(uri) }
    }

    // Une photo prise par l'app photo, écrite dans notre cache.
    fun nouvellePhoto(context: Context): Pair<File, Uri> {
        val dossier = File(context.cacheDir, "hooky/photos").apply { mkdirs() }
        val nom = "photo-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".jpg"
        val fichier = File(dossier, nom)
        return fichier to FileProvider.getUriForFile(context, autorite(context), fichier)
    }

    fun photoPrise(fichier: File, uri: Uri): FichierChoisi =
        FichierChoisi(fichier.name, "image/jpeg", fichier.length(), uri.toString()) { fichier.inputStream() }

    // Si l'app a déclaré la permission CAMERA, Android l'exige aussi pour
    // passer la main à l'app photo : il faut alors la demander d'abord.
    fun cameraExigeePermission(context: Context): Boolean = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(android.Manifest.permission.CAMERA) == true &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    // Une pièce reçue, téléchargée dans le cache (une fois), puis ouverte
    // dans l'app qui sait la lire, ou partagée. Rend faux si rien n'a marché.
    suspend fun ouvrir(context: Context, piece: PieceJointe, partager: Boolean, textes: Textes): Boolean {
        val fichier = telecharger(context, piece)
        if (fichier == null) return navigateur(context, piece.url)
        val uri = FileProvider.getUriForFile(context, autorite(context), fichier)
        val type = piece.type.ifEmpty { typeDepuisNom(piece.nom) }
        val intention = if (partager) {
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                textes.t("partager"),
            )
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intention)
            true
        } catch (_: ActivityNotFoundException) {
            // Aucune app pour ce type : le navigateur saura au moins le télécharger.
            !partager && navigateur(context, piece.url)
        }
    }

    private fun navigateur(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: Exception) {
        false
    }

    private suspend fun telecharger(context: Context, piece: PieceJointe): File? = withContext(Dispatchers.IO) {
        val nom = piece.nom.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "fichier" }
        val dossier = File(context.cacheDir, "hooky/recus/" + piece.id).apply { mkdirs() }
        val fichier = File(dossier, nom)
        if (fichier.exists() && fichier.length() > 0 && (piece.taille <= 0 || fichier.length() == piece.taille)) return@withContext fichier
        try {
            Hooky.clientHttp.newCall(Request.Builder().url(piece.url).build()).execute().use { reponse ->
                if (!reponse.isSuccessful) return@withContext null
                val provisoire = File(dossier, "$nom.part")
                reponse.body?.byteStream()?.use { entree -> provisoire.outputStream().use { entree.copyTo(it) } } ?: return@withContext null
                if (!provisoire.renameTo(fichier)) return@withContext null
            }
            fichier
        } catch (_: Exception) {
            null
        }
    }
}

package com.heyhooky.sdk

import java.io.InputStream
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

// Un fichier choisi par le visiteur (photothèque, appareil photo, fichiers),
// pas encore envoyé. uri sert à l'aperçu (content://…), ouvrir à l'envoi.
internal class FichierChoisi(
    val nom: String,
    val type: String,
    val taille: Long,
    val uri: String?,
    val ouvrir: () -> InputStream?,
) {
    val id: Long = compteur.incrementAndGet()
    val image: Boolean = estImage(type, nom)

    fun versEnvoi(): FichierAEnvoyer = FichierAEnvoyer(nom, type.ifEmpty { typeDepuisNom(nom) }, taille, ouvrir)

    private companion object {
        val compteur = AtomicLong()
    }
}

// Ce que l'API accepte d'un visiteur (PieceJointe::VISITEUR) : des photos et
// des PDF, 10 Mo chacun, 4 par message. Le serveur lit le vrai type dans le
// contenu ; ici, on prévient seulement avant l'envoi, comme la bulle web.
internal object Limites {
    const val FICHIERS_MAX = 4
    const val TAILLE_MAX = 10L * 1024 * 1024
    val TYPES = Regex("^(image/(jpeg|png|heic|heif|webp|gif)|application/pdf)$")
    val EXTENSIONS = Regex("\\.(jpe?g|png|heic|heif|webp|gif|pdf)$", RegexOption.IGNORE_CASE)
}

internal fun estImage(type: String, nom: String): Boolean =
    type.startsWith("image/") || Regex("\\.(jpe?g|png|heic|heif|webp|gif)$", RegexOption.IGNORE_CASE).containsMatchIn(nom)

internal fun typeDepuisNom(nom: String): String = when (nom.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "pdf" -> "application/pdf"
    else -> "application/octet-stream"
}

// Ajoute des fichiers à ceux déjà choisis en écartant ce que l'API
// refuserait ; rend la nouvelle liste et la première raison d'un refus.
internal fun ajouterFichiers(
    deja: List<FichierChoisi>,
    nouveaux: List<FichierChoisi>,
    textes: Textes,
): Pair<List<FichierChoisi>, String?> {
    val liste = deja.toMutableList()
    var erreur: String? = null
    for (fichier in nouveaux) {
        when {
            liste.size >= Limites.FICHIERS_MAX -> erreur = erreur ?: textes.t("quatre")
            !Limites.TYPES.matches(fichier.type) && !Limites.EXTENSIONS.containsMatchIn(fichier.nom) ->
                erreur = erreur ?: textes.t("refuse", "nom" to fichier.nom)
            fichier.taille > Limites.TAILLE_MAX -> erreur = erreur ?: textes.t("lourd", "nom" to fichier.nom)
            fichier.taille == 0L -> Unit
            else -> liste += fichier
        }
    }
    return liste to erreur
}

// 812 Ko, 3,4 Mo : à la façon de la langue.
internal fun taille(octets: Long, textes: Textes): String {
    val locale = Locale.forLanguageTag(textes.langue)
    if (octets < 1024 * 1024) {
        return NumberFormat.getIntegerInstance(locale).format(maxOf(1, Math.round(octets / 1024.0))) + " " + textes.t("ko")
    }
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    return format.format(octets / 1024.0 / 1024.0) + " " + textes.t("mo")
}

// 42 % (fr), 42% (en) : à la façon de la langue.
internal fun pourcent(part: Float, textes: Textes): String =
    NumberFormat.getPercentInstance(Locale.forLanguageTag(textes.langue)).format(part.toDouble())

internal fun extension(nom: String): String =
    Regex("\\.([a-z0-9]{1,4})$", RegexOption.IGNORE_CASE).find(nom)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""

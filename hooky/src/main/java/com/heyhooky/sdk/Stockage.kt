package com.heyhooky.sdk

import java.io.File
import java.security.MessageDigest
import java.util.Properties

// Ce que la bulle garde sur l'appareil, par clé de site : le jeton de la
// conversation (la seule clé du visiteur), l'identifiant du client donné par
// identify(userId) avec lui, le brouillon, le dernier message de l'équipe
// déjà lu (pour compter les non-lus), et « Toujours traduire » (un réglage de
// l'appareil : effacer() le garde).
internal interface Stockage {
    var jeton: String?
    var utilisateur: String?
    var brouillon: String?
    var luJusqua: Long
    var toujoursTraduire: Boolean
    fun effacer()
}

// Dans un petit fichier privé de l'app, rangé dans noBackupFilesDir : jamais
// sauvegardé dans le cloud ni restauré sur un autre appareil (un jeton copié
// ailleurs donnerait accès à la conversation). Le dossier de l'app n'est
// lisible que par elle ; EncryptedSharedPreferences, abandonné par Google,
// n'apporterait pas plus.
internal class StockageFichier(dossier: File, cleSite: String) : Stockage {
    private val fichier = File(File(dossier, "hooky"), "site-" + empreinte(cleSite) + ".properties")
    private val valeurs = Properties()

    init {
        try {
            if (fichier.exists()) fichier.inputStream().use { valeurs.load(it) }
        } catch (_: Exception) {
            // Un fichier abîmé : on repart de zéro.
        }
    }

    override var jeton: String?
        get() = lire("jeton")
        set(valeur) = ecrire("jeton", valeur)

    override var utilisateur: String?
        get() = lire("utilisateur")
        set(valeur) = ecrire("utilisateur", valeur?.takeIf { it.isNotEmpty() })

    override var brouillon: String?
        get() = lire("brouillon")
        set(valeur) = ecrire("brouillon", valeur?.takeIf { it.isNotEmpty() })

    override var luJusqua: Long
        get() = lire("lu")?.toLongOrNull() ?: 0
        set(valeur) = ecrire("lu", valeur.toString())

    override var toujoursTraduire: Boolean
        get() = lire("traduire") == "1"
        set(valeur) = ecrire("traduire", if (valeur) "1" else null)

    override fun effacer() {
        val traduire = toujoursTraduire
        synchronized(valeurs) { valeurs.clear() }
        fichier.delete()
        toujoursTraduire = traduire
    }

    private fun lire(cle: String): String? = synchronized(valeurs) { valeurs.getProperty(cle) }

    private fun ecrire(cle: String, valeur: String?) {
        synchronized(valeurs) {
            if (valeurs.getProperty(cle) == valeur) return
            if (valeur == null) valeurs.remove(cle) else valeurs.setProperty(cle, valeur)
            try {
                fichier.parentFile?.mkdirs()
                // Écrit à côté puis renommé : jamais de fichier à moitié écrit.
                val provisoire = File(fichier.path + ".tmp")
                provisoire.outputStream().use { valeurs.store(it, null) }
                if (!provisoire.renameTo(fichier)) {
                    fichier.delete()
                    provisoire.renameTo(fichier)
                }
            } catch (_: Exception) {
                // Disque plein : la conversation vivra le temps du processus.
            }
        }
    }

    companion object {
        // La clé du site n'apparaît pas en clair dans le nom du fichier.
        fun empreinte(cle: String): String =
            MessageDigest.getInstance("SHA-256").digest(cle.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}

// Pour les tests, et en secours si le disque est inaccessible.
internal class StockageMemoire : Stockage {
    override var jeton: String? = null
    override var utilisateur: String? = null
    override var brouillon: String? = null
    override var luJusqua: Long = 0
    override var toujoursTraduire: Boolean = false
    override fun effacer() {
        jeton = null
        utilisateur = null
        brouillon = null
        luJusqua = 0
    }
}

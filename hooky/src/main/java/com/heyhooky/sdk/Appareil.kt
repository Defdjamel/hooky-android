package com.heyhooky.sdk

import android.os.Build
import java.util.Locale

// L'appareil du visiteur, pour la fiche de la conversation (visitor_device) :
// « Samsung SM-S918B · Android 15 », « Google Pixel 8 · Android 15 ». Le
// fabricant n'est pas répété quand le modèle le porte déjà (« OnePlus 9 Pro »).
internal object Appareil {
    fun actuel(): String? = try {
        nom(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE)
    } catch (_: Throwable) {
        null
    }

    fun nom(fabricant: String?, modele: String?, version: String?): String {
        val f = majuscule(fabricant?.trim().orEmpty().takeUnless { it.equals("unknown", ignoreCase = true) }.orEmpty())
        val m = majuscule(modele?.trim().orEmpty())
        val appareil = when {
            m.isEmpty() -> f
            f.isEmpty() || m.startsWith(f, ignoreCase = true) -> m
            else -> "$f $m"
        }
        val systeme = "Android" + (version?.trim()?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
        return if (appareil.isEmpty()) systeme else "$appareil · $systeme"
    }

    private fun majuscule(texte: String): String =
        texte.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}

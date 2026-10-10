package com.heyhooky.sdk

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

// Les couleurs de la bulle web (widget.js : --encre, --gris, --fond, --doux),
// et leur pendant sombre, pris dans la palette sombre des apps Hooky
// (app/lib/theme.dart). L'accent est la couleur du site ; le texte posé
// dessus est l'encre ou le blanc, selon le contraste (inkOn).
@Immutable
internal data class Palette(
    val sombre: Boolean,
    // Le trait des bordures, le texte, l'ombre dure des cartes.
    val encre: Color,
    val gris: Color,
    // Le fond du fil, les cartes (bulles de l'équipe, saisie), le gris doux.
    val fond: Color,
    val carte: Color,
    val doux: Color,
    // La bulle du visiteur et son texte.
    val bulleVisiteur: Color,
    val texteVisiteur: Color,
    val ombre: Color,
    val placeholder: Color,
    val erreur: Color,
    val fichierFond: Color,
    val fichierTexte: Color,
    val accent: Color,
    val surAccent: Color,
) {
    companion object {
        val ENCRE = Color(0xFF1B1530)
        val LILAS = Color(0xFF8B6CFF)

        fun claire(accent: Color) = Palette(
            sombre = false,
            encre = ENCRE,
            gris = Color(0xFF554D6E),
            fond = Color(0xFFFAF9FF),
            carte = Color.White,
            doux = Color(0xFFEFECF8),
            bulleVisiteur = ENCRE,
            texteVisiteur = Color.White,
            ombre = ENCRE,
            placeholder = Color(0xFF6C6488),
            erreur = Color(0xFFA3260F),
            fichierFond = Color(0xFFFFE3DC),
            fichierTexte = Color(0xFFA3260F),
            accent = accent,
            surAccent = surAccent(accent),
        )

        // En sombre, l'encre devient la crème : les traits clairs gardent le
        // dessin de la bulle web, les ombres dures passent au noir.
        fun sombre(accent: Color) = Palette(
            sombre = true,
            encre = Color(0xFFF1EDFB),
            gris = Color(0xFFA9A2B6),
            fond = Color(0xFF16131F),
            carte = Color(0xFF221E2E),
            doux = Color(0xFF2E2840),
            bulleVisiteur = Color(0xFFF1EDFB),
            texteVisiteur = ENCRE,
            ombre = Color(0xFF000000),
            placeholder = Color(0xFF8D86A0),
            erreur = Color(0xFFFF8C70),
            fichierFond = Color(0xFF4E2B23),
            fichierTexte = Color(0xFFFFB4A1),
            accent = accent,
            surAccent = surAccent(accent),
        )

        // "#FF5B3A" → Color ; une couleur illisible donne le lilas de Hooky.
        fun hex(valeur: String?): Color {
            val v = valeur?.trim()?.removePrefix("#") ?: return LILAS
            if (v.length != 6) return LILAS
            val n = v.toLongOrNull(16) ?: return LILAS
            return Color(0xFF000000 or n)
        }

        // Le texte lisible sur l'accent : la luminance relative (WCAG), comme
        // inkOn() dans widget.js.
        fun surAccent(couleur: Color): Color = if (luminance(couleur) > 0.4) ENCRE else Color.White

        fun luminance(couleur: Color): Double {
            fun canal(v: Float): Double = if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            return 0.2126 * canal(couleur.red) + 0.7152 * canal(couleur.green) + 0.0722 * canal(couleur.blue)
        }
    }
}

internal val LocalPalette = staticCompositionLocalOf { Palette.claire(Palette.LILAS) }
internal val LocalTextes = staticCompositionLocalOf { Textes("en") }

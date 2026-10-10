package com.heyhooky.sdk

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Le thème des écrans Hooky : la palette (claire ou sombre selon
// l'appareil), l'accent du site, les textes dans la langue de l'appareil.
// Material 3 sert aux menus et aux champs ; les couleurs restent Hooky.
@Composable
internal fun HookyTheme(site: Site?, textes: Textes, content: @Composable () -> Unit) {
    val sombre = isSystemInDarkTheme()
    val accent = remember(site?.couleur) { Palette.hex(site?.couleur) }
    val palette = remember(sombre, accent) { if (sombre) Palette.sombre(accent) else Palette.claire(accent) }
    val schema = if (sombre) {
        darkColorScheme(primary = palette.encre, onPrimary = palette.fond, surface = palette.carte, onSurface = palette.encre, background = palette.fond)
    } else {
        lightColorScheme(primary = palette.encre, onPrimary = Color.White, surface = palette.carte, onSurface = palette.encre, background = palette.fond)
    }
    MaterialTheme(colorScheme = schema) {
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalTextes provides textes,
            LocalTextSelectionColors provides TextSelectionColors(handleColor = palette.encre, backgroundColor = palette.encre.copy(alpha = .25f)),
            content = content,
        )
    }
}

// L'ombre dure de la bulle web (box-shadow: 3px 3px 0 var(--encre)) : un
// double décalé, sans flou. À poser avant le fond et la bordure.
internal fun Modifier.ombreDure(decalage: Dp, rayon: Dp, couleur: Color): Modifier =
    ombreDure(decalage, RoundedCornerShape(rayon), couleur)

// La même, pour une forme quelconque (la bulle et sa pointe).
internal fun Modifier.ombreDure(decalage: Dp, forme: Shape, couleur: Color): Modifier = drawBehind {
    val d = decalage.toPx()
    val contour = forme.createOutline(size, layoutDirection, this)
    translate(d, d) { drawOutline(contour, couleur) }
}

// Une carte de la bulle web d'un seul tenant : l'ombre dure (si décalage),
// le trait, puis le fond à l'intérieur du trait. Dessinés ensemble, sans le
// liseré clair que laissent un clip et une bordure superposés.
internal fun Modifier.carteDure(
    forme: Shape,
    fond: Color,
    trait: Color,
    ombre: Color,
    decalage: Dp = 0.dp,
    epaisseur: Dp = TRAIT,
): Modifier = drawBehind {
    val contour = forme.createOutline(size, layoutDirection, this)
    val d = decalage.toPx()
    if (d > 0f) translate(d, d) { drawOutline(contour, ombre) }
    drawOutline(contour, trait)
    val e = epaisseur.toPx()
    val interieur = forme.createOutline(Size(size.width - 2 * e, size.height - 2 * e), layoutDirection, this)
    translate(e, e) { drawOutline(interieur, fond) }
}

internal val TRAIT: Dp = 2.dp

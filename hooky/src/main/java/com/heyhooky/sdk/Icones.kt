package com.heyhooky.sdk

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Les icônes de la bulle web (widget.js, fonction icone) : des traits de
// 2,2 sur 24, bouts arrondis. Pas de material-icons-extended à embarquer.
internal object Icones {
    val fermer = trait("fermer", "M6 6l12 12M18 6L6 18")
    val envoyer = trait("envoyer", "M5 12h14M13 6l6 6-6 6")
    val trombone = trait("trombone", "M21 11.5l-8.6 8.6a5.5 5.5 0 0 1-7.8-7.8l8.9-8.9a3.7 3.7 0 0 1 5.2 5.2l-8.9 8.9a1.8 1.8 0 0 1-2.6-2.6l8.2-8.2")
    val appareil = trait(
        "appareil",
        "M22 18.5a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-10a2 2 0 0 1 2-2h3.5l2-3h5l2 3H20a2 2 0 0 1 2 2z",
        "M8.2 13a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0-7.6 0",
    )
    val photos = trait(
        "photos",
        "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M3 16.5l5-5 4.5 4.5 3-3 5.5 5.5",
        "M14.5 8.8a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0",
    )
    val doc = trait("doc", "M14 2.5H6.5a2 2 0 0 0-2 2v15a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V8z", "M14 2.5V8h5.5")
    val telecharger = trait("telecharger", "M12 4v11M7 10.5l5 5 5-5M5 20h14")
    val partager = trait("partager", "M12 15V3.5M7 8l5-5 5 5", "M5 13.5V19a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5.5")
    val externe = trait("externe", "M14 4h6v6M20 4l-9 9", "M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4")
    val gauche = trait("gauche", "M15 18l-6-6 6-6")
    val droite = trait("droite", "M9 18l6-6-6-6")
    val traduire = trait(
        "traduire",
        "M4 5h9M8.5 3v2M11 5c-1 4.5-3.6 7.6-7 9.5M6.5 9c1.3 2.2 3 3.8 5 4.8",
        "M13 21l4-9 4 9M14.5 17.6h5",
    )
    // La même, barrée : « Toujours traduire » coupé.
    val traduireCoupe = trait(
        "traduire-coupe",
        "M4 5h9M8.5 3v2M11 5c-1 4.5-3.6 7.6-7 9.5M6.5 9c1.3 2.2 3 3.8 5 4.8",
        "M13 21l4-9 4 9M14.5 17.6h5",
        "M3 3l18 18",
    )

    private fun trait(nom: String, vararg chemins: String): ImageVector =
        ImageVector.Builder(name = nom, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                chemins.forEach {
                    addPath(
                        pathData = addPathNodes(it),
                        fill = null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 2.2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    // Le logo de Hooky (web/assets/hooky-mark.svg) : la bulle, son hameçon et
    // ses trois points ; la bulle de la couleur du texte, les points de
    // celle du bouton.
    fun logo(trait: Color, points: Color): ImageVector =
        ImageVector.Builder(name = "hooky", defaultWidth = 48.dp, defaultHeight = 48.dp, viewportWidth = 48f, viewportHeight = 48f)
            .addPath(addPathNodes("M16 3h16a12 12 0 0 1 12 12v5a12 12 0 0 1-12 12H16A12 12 0 0 1 4 20v-5A12 12 0 0 1 16 3z"), fill = SolidColor(trait))
            .addPath(addPathNodes("M31 29v10a6 6 0 0 1-12 0v-3.2"), stroke = SolidColor(trait), strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round)
            .addPath(addPathNodes("M19 35.3l4.2 3.4"), stroke = SolidColor(trait), strokeLineWidth = 3.2f, strokeLineCap = StrokeCap.Round)
            .addPath(addPathNodes(rond(16f, 17.5f, 2.6f) + rond(24f, 17.5f, 2.6f) + rond(32f, 17.5f, 2.6f)), fill = SolidColor(points))
            .build()

    private fun rond(x: Float, y: Float, r: Float): String = "M${x - r} ${y}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"
}

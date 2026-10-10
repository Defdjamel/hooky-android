package com.heyhooky.sdk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// La visionneuse plein écran, comme celle de la bulle web : la photo en
// grand (deux doigts ou un double toucher pour zoomer), son nom, les
// flèches s'il y en a plusieurs, partager, fermer.
@Composable
internal fun Visionneuse(
    pieces: List<PieceJointe>,
    depart: Int,
    fermer: () -> Unit,
    partager: (PieceJointe) -> Unit,
    surEchec: () -> Unit,
) {
    val textes = LocalTextes.current
    var index by remember { mutableIntStateOf(depart.coerceIn(0, pieces.lastIndex)) }
    val piece = pieces.getOrNull(index) ?: return
    Dialog(
        onDismissRequest = fermer,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.ENCRE)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    piece.nom + if (pieces.size > 1) " · ${index + 1}/${pieces.size}" else "",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                BoutonClair(Icones.partager, textes.t("partager")) { partager(piece) }
                BoutonClair(Icones.fermer, textes.t("fermer_photo"), surClic = fermer)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                PhotoZoomable(piece, surEchec)
                if (pieces.size > 1) {
                    BoutonClair(
                        Icones.gauche,
                        textes.t("avant"),
                        Modifier.align(Alignment.CenterStart),
                    ) { index = (index - 1 + pieces.size) % pieces.size }
                    BoutonClair(
                        Icones.droite,
                        textes.t("apres"),
                        Modifier.align(Alignment.CenterEnd),
                    ) { index = (index + 1) % pieces.size }
                }
            }
        }
    }
}

@Composable
private fun PhotoZoomable(piece: PieceJointe, surEchec: () -> Unit) {
    var echelle by remember(piece.id) { mutableFloatStateOf(1f) }
    var decalage by remember(piece.id) { mutableStateOf(Offset.Zero) }
    ImageDistante(
        cle = "p${piece.id}-grand",
        adresse = piece.url,
        cote = 2048.dp,
        contentScale = ContentScale.Fit,
        description = piece.nom,
        // En attendant la grande, la miniature déjà chargée dans le fil.
        secours = "p${piece.id}",
        surEchec = surEchec,
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(piece.id) {
                detectTapGestures(onDoubleTap = {
                    if (echelle > 1f) {
                        echelle = 1f
                        decalage = Offset.Zero
                    } else {
                        echelle = 2.5f
                    }
                })
            }
            .pointerInput(piece.id) {
                detectTransformGestures { _, deplacement, zoom, _ ->
                    echelle = (echelle * zoom).coerceIn(1f, 5f)
                    decalage = if (echelle > 1f) decalage + deplacement else Offset.Zero
                }
            }
            .graphicsLayer {
                scaleX = echelle
                scaleY = echelle
                translationX = decalage.x
                translationY = decalage.y
            },
    )
}

@Composable
private fun BoutonClair(icone: ImageVector, libelle: String, modifier: Modifier = Modifier, surClic: () -> Unit) {
    val forme = RoundedCornerShape(12.dp)
    Box(
        modifier
            .size(40.dp)
            .clip(forme)
            .background(Palette.ENCRE.copy(alpha = .6f))
            .border(2.dp, Color.White.copy(alpha = .35f), forme)
            .clickable(role = Role.Button, onClickLabel = libelle) { surClic() }
            .semantics { contentDescription = libelle },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icone, null, Modifier.size(22.dp), tint = Color.White)
    }
}

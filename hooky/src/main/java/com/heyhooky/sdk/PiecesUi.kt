package com.heyhooky.sdk

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Une image chargée par Images (miniature du fil, aperçu local, photo en
// grand). En attendant, la case reste unie ; un échec appelle surEchec (un
// lien expiré : le fil sera relu, le lien neuf relancera le chargement).
@Composable
internal fun ImageDistante(
    cle: String,
    adresse: String?,
    cote: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    description: String? = null,
    secours: String? = null,
    surEchec: () -> Unit = {},
) {
    val pixels = with(LocalDensity.current) { cote.roundToPx() }
    val bitmap by produceState(Images.enCache(cle) ?: secours?.let { Images.enCache(it) }, cle, adresse) {
        if (Images.enCache(cle) != null) {
            value = Images.enCache(cle)
            return@produceState
        }
        if (adresse == null) return@produceState
        val charge = Images.charger(cle, adresse, pixels)
        if (charge != null) value = charge else surEchec()
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), contentDescription = description, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(LocalPalette.current.doux))
    }
}

// Les photos d'un message : une seule garde sa forme (dans 236 × 260 au
// plus) ; plusieurs vont deux par ligne, la troisième sur toute la largeur.
@Composable
internal fun Photos(
    pieces: List<PieceJointe>,
    surToucher: (Int) -> Unit,
    surEchec: () -> Unit,
) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    val forme = RoundedCornerShape(18.dp)
    @Composable
    fun Case(i: Int, modifier: Modifier) {
        val piece = pieces[i]
        Box(
            modifier
                .clip(forme)
                .border(TRAIT, palette.trait, forme)
                .clickable(role = Role.Image, onClickLabel = textes.t("agrandir", "nom" to piece.nom)) { surToucher(i) }
                .semantics { contentDescription = textes.t("agrandir", "nom" to piece.nom) },
        ) {
            ImageDistante(
                cle = "p${piece.id}",
                adresse = piece.miniature ?: piece.url,
                cote = 480.dp,
                modifier = Modifier.fillMaxSize(),
                surEchec = surEchec,
            )
        }
    }

    if (pieces.size == 1) {
        val piece = pieces[0]
        val (l, h) = cadre(piece.largeur, piece.hauteur)
        Case(0, Modifier.width(l).height(h))
        return
    }
    Column(Modifier.width(236.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pieces.indices.chunked(2).forEach { rang ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rang.forEach { i ->
                    val seule = rang.size == 1
                    Case(i, Modifier.weight(1f).aspectRatio(if (seule) 2f else 1f))
                }
            }
        }
    }
}

// La taille d'une photo seule, comme cadrer() du web.
internal fun cadre(largeur: Int?, hauteur: Int?): Pair<Dp, Dp> {
    if (largeur == null || hauteur == null || largeur <= 0 || hauteur <= 0) return 200.dp to 200.dp
    val echelle = min(min(236f / largeur, 260f / hauteur), 1f)
    val l = max(96, (largeur * echelle).roundToInt())
    return l.dp to (l.toFloat() * hauteur / largeur).dp
}

// Un fichier reçu : le type (PDF…), le nom, la taille ; toucher l'ouvre,
// le bouton à droite le partage.
@Composable
internal fun CarteFichier(
    nom: String,
    taille: Long,
    occupe: Boolean,
    surOuvrir: (() -> Unit)?,
    surPartager: (() -> Unit)?,
) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    val forme = RoundedCornerShape(16.dp)
    val ext = extension(nom)
    Row(
        Modifier
            .width(236.dp)
            .carteDure(forme, palette.carte, palette.trait, palette.ombre, 2.dp)
            .clip(forme)
            .then(
                if (surOuvrir != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = textes.t("ouvrir_fichier", "nom" to nom)) { surOuvrir() }
                } else {
                    Modifier
                }
            )
            .padding(start = 9.dp, top = 9.dp, bottom = 9.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .size(width = 38.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.fichierFond),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icones.doc, null, Modifier.size(18.dp), tint = palette.fichierTexte)
            if (ext.isNotEmpty()) Text(ext, color = palette.fichierTexte, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(nom, color = palette.encre, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (taille > 0) taille(taille, textes) + if (ext.isNotEmpty()) " · $ext" else "" else ext,
                color = palette.gris,
                fontSize = 12.sp,
            )
        }
        when {
            occupe -> CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), color = palette.gris, strokeWidth = 2.dp)
            surPartager != null -> Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = textes.t("partager")) { surPartager() }
                    .semantics { contentDescription = textes.t("partager") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icones.partager, null, Modifier.size(20.dp), tint = palette.gris)
            }
            else -> Icon(Icones.telecharger, null, Modifier.padding(8.dp).size(20.dp), tint = palette.gris)
        }
    }
}

// La barre de progression d'un envoi.
@Composable
internal fun Progression(part: Float, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(palette.doux),
    ) {
        Box(
            Modifier
                .fillMaxWidth(part.coerceIn(0f, 1f))
                .height(4.dp)
                .background(palette.encre),
        )
    }
}

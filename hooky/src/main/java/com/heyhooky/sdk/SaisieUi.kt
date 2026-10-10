package com.heyhooky.sdk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class Choix { PHOTOS, APPAREIL, FICHIERS }

// La saisie, en bas : l'erreur, les fichiers choisis, le trombone (photo,
// appareil photo, fichier), le texte qui grandit, la flèche d'envoi. Elle
// suit le clavier (insets ime) et ne se fait jamais masquer.
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Saisie(
    valeur: TextFieldValue,
    surChangement: (TextFieldValue) -> Unit,
    choisis: List<FichierChoisi>,
    retirer: (FichierChoisi) -> Unit,
    erreur: String?,
    occupe: Boolean,
    envoyer: () -> Unit,
    joindre: (Choix) -> Unit,
    focus: FocusRequester,
) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    var actif by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val peutEnvoyer = !occupe && (valeur.text.isNotBlank() || choisis.isNotEmpty())
    val forme = RoundedCornerShape(22.dp)
    val trait = with(androidx.compose.ui.platform.LocalDensity.current) { TRAIT.toPx() }

    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.carte)
            .drawBehind { drawLine(palette.trait, Offset(0f, trait / 2), Offset(size.width, trait / 2), trait) }
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(start = 10.dp, end = 10.dp, top = 10.dp),
    ) {
        if (erreur != null) {
            Text(
                erreur,
                color = palette.erreur,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .carteDure(forme, palette.carte, palette.trait, palette.ombre, if (actif) 3.dp else 0.dp)
                .clip(forme),
        ) {
            if (choisis.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    choisis.forEach { Apercu(it) { retirer(it) } }
                }
            }
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    Outil(Icones.trombone, textes.t("joindre")) { menu = true }
                    DropdownMenu(
                        expanded = menu,
                        onDismissRequest = { menu = false },
                        shape = RoundedCornerShape(18.dp),
                        containerColor = palette.carte,
                        border = BorderStroke(TRAIT, palette.trait),
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp,
                    ) {
                        ElementMenu(Icones.photos, textes.t("photo")) {
                            menu = false
                            joindre(Choix.PHOTOS)
                        }
                        ElementMenu(Icones.appareil, textes.t("appareil")) {
                            menu = false
                            joindre(Choix.APPAREIL)
                        }
                        ElementMenu(Icones.doc, textes.t("fichier")) {
                            menu = false
                            joindre(Choix.FICHIERS)
                        }
                    }
                }
                BasicTextField(
                    value = valeur,
                    onValueChange = { if (it.text.length <= 5000) surChangement(it) },
                    maxLines = 6,
                    textStyle = TextStyle(color = palette.encre, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(palette.encre),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .focusRequester(focus)
                        .onFocusChanged { actif = it.isFocused }
                        .semantics { contentDescription = textes.t("message") }
                        .padding(horizontal = 6.dp, vertical = 9.dp),
                    decorationBox = { champ ->
                        Box {
                            if (valeur.text.isEmpty()) Text(textes.t("saisie"), color = palette.placeholder, fontSize = 16.sp, maxLines = 1)
                            champ()
                        }
                    },
                )
                Box(
                    Modifier
                        .padding(start = 2.dp)
                        .size(40.dp)
                        // Rien à envoyer : la flèche s'efface sur le gris doux.
                        .carteDure(RoundedCornerShape(14.dp), if (peutEnvoyer) palette.accent else palette.doux, if (peutEnvoyer) palette.trait else palette.doux, palette.ombre)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = peutEnvoyer, role = Role.Button, onClickLabel = textes.t("envoyer")) { envoyer() }
                        .semantics { contentDescription = textes.t("envoyer") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icones.envoyer, null, Modifier.size(22.dp), tint = if (peutEnvoyer) palette.surAccent else palette.placeholder)
                }
            }
        }
        // « Propulsé par hooky », caché quand le clavier est ouvert.
        if (WindowInsets.isImeVisible) {
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                textes.t("propulse"),
                color = palette.placeholder,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun Outil(icone: ImageVector, libelle: String, surClic: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = libelle) { surClic() }
            .semantics { contentDescription = libelle },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icone, null, Modifier.size(22.dp), tint = palette.gris)
    }
}

@Composable
private fun ElementMenu(icone: ImageVector, libelle: String, surClic: () -> Unit) {
    val palette = LocalPalette.current
    DropdownMenuItem(
        text = { Text(libelle, color = palette.encre, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) },
        leadingIcon = { Icon(icone, null, Modifier.size(20.dp), tint = palette.encre) },
        onClick = surClic,
    )
}

// Un fichier choisi, avant l'envoi : la photo, ou une carte pour un PDF ;
// la petite croix le retire.
@Composable
private fun Apercu(fichier: FichierChoisi, retirer: () -> Unit) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    val forme = RoundedCornerShape(14.dp)
    Box {
        if (fichier.image && fichier.uri != null) {
            ImageDistante(
                cle = "local${fichier.id}",
                adresse = fichier.uri,
                cote = 192.dp,
                description = fichier.nom,
                modifier = Modifier
                    .size(64.dp)
                    .clip(forme)
                    .border(TRAIT, palette.trait, forme),
            )
        } else {
            Row(
                Modifier
                    .width(160.dp)
                    .height(64.dp)
                    .clip(forme)
                    .background(palette.carte)
                    .border(TRAIT, palette.trait, forme)
                    .padding(start = 8.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 28.dp, height = 34.dp).clip(RoundedCornerShape(6.dp)).background(palette.fichierFond),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(extension(fichier.nom).ifEmpty { "PDF" }, color = palette.fichierTexte, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(fichier.nom, color = palette.encre, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (fichier.taille > 0) Text(taille(fichier.taille, textes), color = palette.gris, fontSize = 12.sp)
                }
            }
        }
        val libelle = textes.t("retirer", "nom" to fichier.nom)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 7.dp, y = (-7).dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(palette.carte)
                .padding(2.dp)
                .clip(CircleShape)
                .background(palette.encre)
                .clickable(role = Role.Button, onClickLabel = libelle) { retirer() }
                .semantics { contentDescription = libelle },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icones.fermer, null, Modifier.fillMaxSize().padding(4.dp), tint = if (palette.sombre) palette.fond else Color.White)
        }
    }
}

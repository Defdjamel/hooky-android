package com.heyhooky.sdk

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Le dessin de chaque ligne du fil (voir MiseEnLignes) : les bulles, le
// jour, l'heure, le message en route, « … écrit », l'accueil.

private val RAYON = 20.dp
private val POINTE = 6.dp

@Composable
internal fun LigneFil(
    ligne: Ligne,
    largeurMax: Dp,
    actions: ActionsFil,
) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    when (ligne) {
        Ligne.Plus -> Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                textes.t("precedents"),
                color = palette.encre,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(palette.carte)
                    .border(TRAIT, palette.trait, CircleShape)
                    .clickable(role = Role.Button) { actions.plusAnciens() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        is Ligne.Jour -> Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
            Text(
                ligne.texte,
                color = palette.gris,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(palette.doux)
                    .padding(horizontal = 11.dp, vertical = 3.dp),
            )
        }

        is Ligne.Auteur -> Text(
            ligne.nom,
            color = palette.gris,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 3.dp),
        )

        is Ligne.Bulle -> MessageFil(ligne, largeurMax, actions)

        is Ligne.Heure -> Text(
            ligne.texte,
            color = palette.gris,
            fontSize = 11.5.sp,
            textAlign = if (ligne.visiteur) TextAlign.End else TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp),
        )

        is Ligne.Envoi -> EnvoiFil(ligne.envoi, largeurMax)

        is Ligne.Frappe -> Row(
            Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PointsFrappe()
            Spacer(Modifier.width(8.dp))
            Text(ligne.nom, color = palette.gris, fontSize = 13.sp)
        }

        is Ligne.Intro -> Box(Modifier.padding(top = 4.dp)) {
            BulleTexte(ligne.texte, visiteur = false, fin = true, largeurMax = largeurMax)
        }

        Ligne.Carte -> CarteAccueil(actions)

        Ligne.Fermee -> Text(
            textes.t("ferme"),
            color = palette.gris,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, start = 24.dp, end = 24.dp),
        )
    }
}

// Ce que le fil demande à l'écran : remonter le fil, voir une photo, ouvrir
// ou partager un fichier, relire les liens expirés, le formulaire d'accueil.
internal interface ActionsFil {
    fun plusAnciens()
    fun voirPhotos(pieces: List<PieceJointe>, index: Int)
    fun ouvrirFichier(piece: PieceJointe, partager: Boolean)
    fun fichierOccupe(piece: PieceJointe): Boolean
    fun liensExpires()
    var prenom: String
    var email: String
    fun versSaisie()
}

@Composable
private fun MessageFil(ligne: Ligne.Bulle, largeurMax: Dp, actions: ActionsFil) {
    val message = ligne.message
    val visiteur = message.expediteur == Expediteur.VISITEUR
    val photos = message.pieces.filter { it.image }
    val fichiers = message.pieces.filterNot { it.image }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (ligne.suite) 3.dp else 10.dp),
        horizontalAlignment = if (visiteur) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (photos.isNotEmpty()) Photos(photos, surToucher = { actions.voirPhotos(photos, it) }, surEchec = actions::liensExpires)
        fichiers.forEach { piece ->
            CarteFichier(
                nom = piece.nom,
                taille = piece.taille,
                occupe = actions.fichierOccupe(piece),
                surOuvrir = { actions.ouvrirFichier(piece, partager = false) },
                surPartager = { actions.ouvrirFichier(piece, partager = true) },
            )
        }
        if (message.texte.isNotEmpty()) {
            if (ligne.emoji > 0) {
                Text(message.texte, fontSize = if (ligne.emoji == 1) 48.sp else 38.sp, lineHeight = 52.sp, modifier = Modifier.padding(horizontal = 2.dp))
            } else {
                BulleTexte(message.texte, visiteur, ligne.fin, largeurMax)
            }
        }
    }
}

// Une bulle de texte : l'encre pleine pour le visiteur, la carte bordée
// (ombre dure) pour l'équipe ; la pointe sur le dernier d'une série.
@Composable
internal fun BulleTexte(texte: String, visiteur: Boolean, fin: Boolean, largeurMax: Dp) {
    val palette = LocalPalette.current
    val forme = if (visiteur) {
        RoundedCornerShape(RAYON, RAYON, if (fin) POINTE else RAYON, RAYON)
    } else {
        RoundedCornerShape(RAYON, RAYON, RAYON, if (fin) POINTE else RAYON)
    }
    val couleurTexte = if (visiteur) palette.texteVisiteur else palette.encre
    val fond = Modifier.widthIn(max = largeurMax).let {
        if (visiteur) {
            it.clip(forme).background(palette.bulleVisiteur).padding(horizontal = 14.dp, vertical = 9.dp)
        } else {
            it.carteDure(forme, palette.carte, palette.trait, palette.ombre, 2.dp)
                .clip(forme)
                .padding(horizontal = 13.dp, vertical = 8.dp)
        }
    }
    SelectionContainer(fond) {
        Text(texteAvecLiens(texte), color = couleurTexte, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

// Les adresses web deviennent des liens (soulignés, de la couleur du texte).
private fun texteAvecLiens(texte: String) = buildAnnotatedString {
    var position = 0
    for (lien in MiseEnLignes.liens(texte)) {
        append(texte.substring(position, lien.first))
        pushLink(LinkAnnotation.Url(texte.substring(lien), TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))))
        append(texte.substring(lien))
        pop()
        position = lien.last + 1
    }
    append(texte.substring(position))
}

// Le message en route : estompé, les aperçus locaux, la progression.
@Composable
private fun EnvoiFil(envoi: EnvoiEnCours, largeurMax: Dp) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    val images = envoi.fichiers.filter { it.image && it.uri != null }
    val autres = envoi.fichiers.filterNot { it.image && it.uri != null }
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).alpha(.62f),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (images.isNotEmpty()) {
            Column(Modifier.width(if (images.size == 1) 200.dp else 236.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                images.chunked(2).forEach { rang ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rang.forEach { fichier ->
                            ImageDistante(
                                cle = "local${fichier.id}",
                                adresse = fichier.uri,
                                cote = 480.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(if (images.size == 1) 200.dp else 116.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .border(TRAIT, palette.trait, RoundedCornerShape(18.dp)),
                            )
                        }
                    }
                }
            }
        }
        autres.forEach { CarteFichier(it.nom, it.taille, occupe = false, surOuvrir = null, surPartager = null) }
        if (envoi.texte.isNotEmpty()) BulleTexte(envoi.texte, visiteur = true, fin = true, largeurMax = largeurMax)
        val progres = envoi.progres
        if (progres != null) Progression(progres, Modifier.width(160.dp))
        Text(
            textes.t("envoi") + if (progres != null && progres > 0f) " " + pourcent(progres, textes) else "",
            color = palette.gris,
            fontSize = 11.5.sp,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

// Les trois points de « … écrit », qui s'allument tour à tour.
@Composable
private fun PointsFrappe() {
    val palette = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "frappe")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Restart), label = "phase")
    Row(
        Modifier
            .clip(CircleShape)
            .background(palette.carte)
            .border(TRAIT, palette.trait, CircleShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(3) { i ->
            val t = ((phase - i * .15f) % 1f + 1f) % 1f
            val alpha = if (t < .3f) .3f + t / .3f * .7f else if (t < .6f) 1f - (t - .3f) / .3f * .7f else .3f
            Box(Modifier.size(6.dp).alpha(alpha).clip(CircleShape).background(palette.encre))
        }
    }
}

// L'accueil d'une nouvelle conversation : le prénom et l'e-mail, facultatifs.
@Composable
private fun CarteAccueil(actions: ActionsFil) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    val focus = LocalFocusManager.current
    val forme = RoundedCornerShape(18.dp)
    var prenom by remember { mutableStateOf(actions.prenom) }
    var email by remember { mutableStateOf(actions.email) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(forme)
            .background(palette.carte)
            .border(TRAIT, palette.trait, forme)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChampAccueil(
            libelle = textes.t("prenom").trim(),
            aide = textes.t("facultatif"),
            valeur = prenom,
            surChangement = {
                prenom = it.take(100)
                actions.prenom = prenom
            },
            clavier = KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            surSuivant = { focus.moveFocus(FocusDirection.Down) },
        )
        ChampAccueil(
            libelle = textes.t("email").trim(),
            aide = textes.t("email_aide"),
            valeur = email,
            surChangement = {
                email = it.take(200)
                actions.email = email
            },
            clavier = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            surSuivant = { actions.versSaisie() },
        )
    }
}

@Composable
private fun ChampAccueil(
    libelle: String,
    aide: String,
    valeur: String,
    surChangement: (String) -> Unit,
    clavier: KeyboardOptions,
    surSuivant: () -> Unit,
) {
    val palette = LocalPalette.current
    var actif by remember { mutableStateOf(false) }
    val forme = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            buildAnnotatedString {
                append(libelle)
                append(" ")
                pushStyle(SpanStyle(fontWeight = FontWeight.Normal, color = palette.gris))
                append(aide)
                pop()
            },
            color = palette.encre,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        androidx.compose.foundation.text.BasicTextField(
            value = valeur,
            onValueChange = surChangement,
            singleLine = true,
            keyboardOptions = clavier,
            keyboardActions = KeyboardActions(onNext = { surSuivant() }),
            textStyle = androidx.compose.ui.text.TextStyle(color = palette.encre, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.encre),
            modifier = Modifier
                .fillMaxWidth()
                .carteDure(forme, palette.carte, palette.trait, palette.ombre, if (actif) 3.dp else 0.dp)
                .clip(forme)
                .onFocusChanged { actif = it.isFocused }
                .semantics { contentDescription = libelle }
                .padding(horizontal = 11.dp, vertical = 9.dp),
        )
    }
}

package com.heyhooky.sdk

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Ce que l'agent IA ajoute à la conversation, comme la bulle web : la mention
// IA et « Parler à un humain » au-dessus de la saisie, la carte qui demande
// l'e-mail au passage, et sous ses messages les fiches produit (empilées,
// jamais de carrousel), le bouton lien et les choix rapides, à la couleur du
// site.

// « IA » encadré, comme .mention-ia de widget.js ; « Assistant IA » près du
// nom de l'agent.
@Composable
internal fun EtiquetteIa(texte: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Text(
        texte,
        color = palette.gris,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = modifier
            .border(1.5.dp, palette.gris, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp),
    )
}

// Au-dessus de la saisie : la mention tant que l'agent n'a pas répondu, puis
// « Parler à un humain » (« Transmission… », l'erreur à réessayer).
@Composable
internal fun BarreAgent(etat: EtatChat, parler: () -> Unit) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    when {
        etat.humainPropose -> Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            val envoi = etat.passage == EtatPassage.ENVOI
            val echec = etat.passage == EtatPassage.ECHEC
            val forme = CircleShape
            Text(
                textes.t(if (envoi) "humain_envoi" else if (echec) "humain_echec" else "humain"),
                color = if (echec) palette.erreur else palette.encre,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .alpha(if (envoi) .6f else 1f)
                    .carteDure(forme, palette.carte, palette.trait, palette.ombre, 2.dp)
                    .clip(forme)
                    .clickable(enabled = !envoi, role = Role.Button) { parler() }
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        etat.mentionIa -> Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 8.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            EtiquetteIa("IA", Modifier.clearAndSetSemantics {})
            Text(textes.t("mention_ia"), color = palette.gris, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

// Au bout du fil, au passage à un humain, si l'on n'a pas l'e-mail.
@Composable
internal fun CarteEmailPassage(etat: EtatEmailPassage, envoyer: (String) -> Unit, modifie: () -> Unit, plusTard: () -> Unit) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    val forme = RoundedCornerShape(18.dp)
    var email by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(forme)
            .background(palette.carte)
            .border(TRAIT, palette.trait, forme)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (etat == EtatEmailPassage.MERCI) {
            Text(
                textes.t("email_merci"),
                color = palette.encre,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            return@Column
        }
        val libelle = textes.t("humain_email")
        Text(libelle, color = palette.encre, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        val champ = RoundedCornerShape(12.dp)
        androidx.compose.foundation.text.BasicTextField(
            value = email,
            onValueChange = {
                email = it.take(200)
                modifie()
            },
            enabled = etat != EtatEmailPassage.ENVOI,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSend = { envoyer(email) }),
            textStyle = TextStyle(color = palette.encre, fontSize = 16.sp),
            cursorBrush = SolidColor(palette.encre),
            modifier = Modifier
                .fillMaxWidth()
                .clip(champ)
                .background(palette.doux)
                .semantics { contentDescription = textes.t("email").trim() }
                .padding(horizontal = 11.dp, vertical = 9.dp),
        )
        if (etat == EtatEmailPassage.INVALIDE) {
            Text(
                textes.t("email_invalide"),
                color = palette.erreur,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            Text(
                textes.t("plus_tard"),
                color = palette.gris,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { plusTard() }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            )
            val envoi = etat == EtatEmailPassage.ENVOI
            BoutonAccent(textes.t("envoyer"), Modifier.alpha(if (envoi) .5f else 1f), actif = !envoi) { envoyer(email) }
        }
    }
}

// Un bouton plein, à la couleur du site, bordé d'encre.
@Composable
private fun BoutonAccent(
    texte: String,
    modifier: Modifier = Modifier,
    actif: Boolean = true,
    forme: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    taille: Int = 14,
    horizontal: Dp = 14.dp,
    vertical: Dp = 5.dp,
    description: String? = null,
    role: Role = Role.Button,
    icone: Boolean = false,
    action: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .clip(forme)
            .background(palette.accent)
            .border(TRAIT, palette.trait, forme)
            .clickable(enabled = actif, role = role) { action() }
            .let { m -> if (description != null) m.semantics { contentDescription = description } else m }
            .padding(horizontal = horizontal, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(texte, color = palette.surAccent, fontSize = taille.sp, fontWeight = FontWeight.Bold, lineHeight = (taille + 4).sp)
        if (icone) Icon(Icones.externe, null, Modifier.size(16.dp), tint = palette.surAccent)
    }
}

// Les blocs sous un message de l'agent, dans l'ordre reçu ; pendant une
// révélation, seules les unités déjà montrées (chaque fiche, le lien, les
// choix) sont là, et chacune apparaît en fondu (comme .revele-apparait).
@Composable
internal fun BlocsAgent(
    message: Message,
    choixVisibles: Boolean,
    unitesMontrees: Int?,
    choisir: (ChoixRapide) -> Unit,
    ficheOuverte: (String) -> Unit,
) {
    val ouvrir = LocalUriHandler.current
    fun ouvrirLien(url: String) {
        try {
            ouvrir.openUri(url)
        } catch (_: Exception) {
            // Aucun navigateur : rien à faire.
        }
    }
    var unite = 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        message.blocs.forEach { bloc ->
            when (bloc) {
                is Bloc.Produits -> bloc.fiches.forEach { fiche ->
                    Apparition(unite++, unitesMontrees) {
                        Fiche(fiche) { url ->
                            ficheOuverte(url)
                            ouvrirLien(url)
                        }
                    }
                }
                is Bloc.Lien -> Apparition(unite++, unitesMontrees) {
                    BoutonAccent(
                        bloc.libelle ?: bloc.url,
                        Modifier.widthIn(max = 300.dp),
                        vertical = 6.dp,
                        role = Role.Button,
                        icone = true,
                    ) { ouvrirLien(bloc.url) }
                }
                is Bloc.Choix -> Apparition(unite++, unitesMontrees) {
                    if (choixVisibles) ChoixRapides(bloc.options, choisir)
                }
            }
        }
    }
}

// Une unité : là tout de suite hors révélation, sinon quand son tour vient.
@Composable
private fun Apparition(index: Int, montrees: Int?, contenu: @Composable () -> Unit) {
    if (montrees == null) {
        contenu()
        return
    }
    val etat = remember { MutableTransitionState(false) }
    etat.targetState = index < montrees
    AnimatedVisibility(
        visibleState = etat,
        enter = fadeIn(tween(Agent.BLOC_DUREE.toInt())) + slideInVertically(tween(Agent.BLOC_DUREE.toInt())) { it / 8 },
    ) { contenu() }
}

// Une fiche : la photo (retirée si elle ne vient pas), le nom, le prix, la
// disponibilité, et « Voir » qui ouvre la page du produit.
@Composable
private fun Fiche(fiche: FicheProduit, ouvrir: (String) -> Unit) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    val forme = RoundedCornerShape(16.dp)
    var sansImage by remember(fiche.image) { mutableStateOf(fiche.image == null) }
    Row(
        Modifier
            .width(260.dp)
            .carteDure(forme, palette.carte, palette.trait, palette.ombre, 2.dp)
            .padding(start = if (sansImage) 12.dp else 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!sansImage) {
            ImageDistante(
                cle = "produit:" + fiche.image,
                adresse = fiche.image,
                cote = 64.dp,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                surEchec = { sansImage = true },
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(fiche.nom, color = palette.encre, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            fiche.prix?.let { Text(it, color = palette.encre, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            fiche.disponibilite?.let {
                val (texte, rupture) = Agent.disponibilite(it, textes)
                Text(
                    texte,
                    color = if (rupture) palette.erreur else palette.gris,
                    fontSize = 12.sp,
                    fontWeight = if (rupture) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            fiche.url?.let { url ->
                BoutonAccent(
                    textes.t("voir"),
                    Modifier.padding(top = 6.dp),
                    taille = 13,
                    vertical = 3.dp,
                    description = textes.t("voir_produit", "nom" to fiche.nom),
                ) { ouvrir(url) }
            }
        }
    }
}

// Les choix rapides, à la ligne quand la place manque.
@Composable
private fun ChoixRapides(options: List<ChoixRapide>, choisir: (ChoixRapide) -> Unit) {
    val textes = LocalTextes.current
    FlowRow(
        Modifier.padding(top = 2.dp).semantics {
            isTraversalGroup = true
            contentDescription = textes.t("choix")
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEach { option ->
            BoutonAccent(option.libelle, forme = CircleShape, horizontal = 13.dp, vertical = 5.dp) { choisir(option) }
        }
    }
}

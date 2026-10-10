package com.heyhooky.sdk

import android.Manifest
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

// L'écran de conversation : l'en-tête aux couleurs du site, le fil, la
// saisie. Tout l'état vient du Moteur ; ici, seulement le dessin et les
// échanges avec le téléphone (sélecteurs de photos et de fichiers).
@Composable
internal fun EcranConversation(moteur: Moteur, fermer: () -> Unit) {
    val etat by moteur.etat.collectAsState()
    val choisis by moteur.choisis.collectAsState()
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    val context = LocalContext.current
    val portee = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var saisie by remember { mutableStateOf(TextFieldValue(moteur.brouillon, TextRange(moteur.brouillon.length))) }
    var visionneuse by remember { mutableStateOf<Pair<List<PieceJointe>, Int>?>(null) }
    val occupes = remember { mutableStateMapOf<Long, Boolean>() }

    fun ajouter(nouveaux: List<FichierChoisi>) {
        if (nouveaux.isEmpty()) return
        val (liste, erreur) = ajouterFichiers(moteur.choisis.value, nouveaux, textes)
        moteur.choisis.value = liste
        moteur.erreur(erreur)
    }

    // Les sélecteurs du téléphone : photothèque (le sélecteur de photos
    // d'Android, sans permission), appareil photo, documents (photos et PDF).
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Limites.FICHIERS_MAX)) { uris ->
        ajouter(uris.map { Partage.choisi(context, it) })
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        ajouter(uris.map { Partage.choisi(context, it) })
    }
    var photoEnCours by remember { mutableStateOf<Pair<File, Uri>?>(null) }
    val appareil = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { prise ->
        val (fichier, uri) = photoEnCours ?: return@rememberLauncherForActivityResult
        photoEnCours = null
        if (prise && fichier.length() > 0) ajouter(listOf(Partage.photoPrise(fichier, uri))) else fichier.delete()
    }
    fun prendrePhoto() {
        try {
            val nouvelle = Partage.nouvellePhoto(context)
            photoEnCours = nouvelle
            appareil.launch(nouvelle.second)
        } catch (_: Exception) {
            Toast.makeText(context, textes.t("illisible"), Toast.LENGTH_SHORT).show()
        }
    }
    val permissionCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accordee ->
        if (accordee) prendrePhoto()
    }

    fun joindre(choix: Choix) {
        try {
            when (choix) {
                Choix.PHOTOS -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                Choix.FICHIERS -> documents.launch(arrayOf("application/pdf", "image/*"))
                Choix.APPAREIL -> if (Partage.cameraExigeePermission(context)) permissionCamera.launch(Manifest.permission.CAMERA) else prendrePhoto()
            }
        } catch (_: Exception) {
            Toast.makeText(context, textes.t("illisible"), Toast.LENGTH_SHORT).show()
        }
    }

    // Le texte et les fichiers partent ensemble ; raté, ils reviennent dans
    // la saisie avec l'erreur.
    fun envoyer() {
        val texte = saisie.text.trim()
        val fichiers = choisis
        if (etat.envoi != null || (texte.isEmpty() && fichiers.isEmpty())) return
        val nouvelle = !moteur.aConversation
        val email = moteur.emailSaisi.trim()
        if (nouvelle && !moteur.identifie && email.isNotEmpty() && !EMAIL.matches(email)) {
            moteur.erreur(textes.t("email_invalide"))
            return
        }
        saisie = TextFieldValue("")
        moteur.saisie("")
        moteur.choisis.value = emptyList()
        portee.launch {
            val parti = moteur.envoyer(
                texte,
                fichiers,
                nom = if (nouvelle && !moteur.identifie) moteur.prenomSaisi else null,
                email = if (nouvelle && !moteur.identifie) email else null,
            )
            if (!parti) {
                val actuel = saisie.text
                val remis = texte + if (actuel.isNotEmpty()) "\n" + actuel else ""
                saisie = TextFieldValue(remis, TextRange(remis.length))
                moteur.saisie(remis)
                moteur.choisis.value = (fichiers + moteur.choisis.value).take(Limites.FICHIERS_MAX)
            }
        }
    }

    val actions = remember(moteur) {
        object : ActionsFil {
            override fun plusAnciens() = moteur.plusAnciens()
            override fun voirPhotos(pieces: List<PieceJointe>, index: Int) {
                visionneuse = pieces to index
            }
            override fun ouvrirFichier(piece: PieceJointe, partager: Boolean) {
                if (occupes[piece.id] == true) return
                occupes[piece.id] = true
                portee.launch {
                    val ok = Partage.ouvrir(context, piece, partager, textes)
                    occupes.remove(piece.id)
                    if (!ok) Toast.makeText(context, textes.t("illisible"), Toast.LENGTH_SHORT).show()
                }
            }
            override fun fichierOccupe(piece: PieceJointe): Boolean = occupes[piece.id] == true
            override fun liensExpires() = moteur.rafraichirLiens()
            override var prenom: String
                get() = moteur.prenomSaisi
                set(valeur) {
                    moteur.prenomSaisi = valeur
                }
            override var email: String
                get() = moteur.emailSaisi
                set(valeur) {
                    moteur.emailSaisi = valeur
                    moteur.effacerErreur()
                }
            override fun versSaisie() {
                focus.requestFocus()
            }
            override fun traduire(message: Message) = moteur.traduire(message)
            override fun basculerOriginal(message: Message) = moteur.basculerOriginal(message)
        }
    }

    val format24 = DateFormat.is24HourFormat(context)
    val locale = remember(textes) { Locale.forLanguageTag(textes.langue) }
    val lignes = remember(etat, moteur.identifie, moteur.aConversation, format24) {
        MiseEnLignes.lignes(
            etat = etat,
            textes = textes,
            identifie = moteur.identifie,
            jour = { jour(it, textes, locale) },
            heure = { SimpleDateFormat(if (format24) "HH:mm" else "h:mm a", locale).format(Date(it)) },
            aConversation = moteur.aConversation,
            traduisible = moteur::traduisible,
        )
    }

    Column(Modifier.fillMaxSize().background(palette.fond)) {
        val enConversation = moteur.aConversation && etat.messages.isNotEmpty()
        Entete(
            titre = if (enConversation) etat.site?.nom.orEmpty() else textes.t("salut"),
            sousTitre = if (enConversation) textes.t("ici") else etat.site?.nom.orEmpty(),
            fermer = fermer,
            toujoursTraduire = if (moteur.proposeTraduction(etat)) etat.toujoursTraduire else null,
            basculerToujours = moteur::toujoursTraduire,
        )

        val liste = rememberLazyListState()
        // Un nouveau message en bas : on y descend si l'on y était déjà (ou
        // si c'est le nôtre).
        val dernier = lignes.lastOrNull()
        LaunchedEffect(dernier?.cle, etat.envoi?.debut) {
            if (liste.firstVisibleItemIndex <= 3 || etat.envoi != null) liste.animateScrollToItem(0)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val largeurMax = (maxWidth - 28.dp) * .82f
            LazyColumn(
                state = liste,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            ) {
                items(lignes.asReversed(), key = { it.cle }) { ligne ->
                    LigneFil(ligne, largeurMax, actions)
                }
            }
        }

        Saisie(
            valeur = saisie,
            surChangement = {
                saisie = it
                moteur.saisie(it.text)
                moteur.effacerErreur()
            },
            choisis = choisis,
            retirer = { fichier -> moteur.choisis.value = moteur.choisis.value.filterNot { it === fichier } },
            erreur = etat.erreur,
            occupe = etat.envoi != null,
            envoyer = ::envoyer,
            joindre = ::joindre,
            focus = focus,
        )
    }

    visionneuse?.let { (pieces, index) ->
        Visionneuse(
            pieces = pieces,
            depart = index,
            fermer = { visionneuse = null },
            partager = { actions.ouvrirFichier(it, partager = true) },
            surEchec = moteur::rafraichirLiens,
        )
    }
}

// L'en-tête aux couleurs du site : « Salut ! On t'écoute. » avant la
// conversation, puis le nom du site ; le menu de traduction (toujoursTraduire
// non nul : proposé dès qu'un message de l'équipe est dans une autre
// langue) ; le bouton pour fermer.
@Composable
private fun Entete(
    titre: String,
    sousTitre: String,
    fermer: () -> Unit,
    toujoursTraduire: Boolean? = null,
    basculerToujours: (Boolean) -> Unit = {},
) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    Column(Modifier.fillMaxWidth().background(palette.accent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    titre,
                    color = palette.surAccent,
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (sousTitre.isNotEmpty()) {
                    Text(sousTitre, color = palette.surAccent.copy(alpha = .92f), fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            val forme = RoundedCornerShape(12.dp)
            if (toujoursTraduire != null) MenuTraduction(toujoursTraduire, basculerToujours)
            Box(
                Modifier
                    .size(40.dp)
                    .carteDure(forme, palette.carte, palette.trait, palette.ombre, 2.dp)
                    .clip(forme)
                    .clickable(role = Role.Button, onClickLabel = textes.t("fermer")) { fermer() }
                    .semantics { contentDescription = textes.t("fermer") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icones.fermer, null, Modifier.size(22.dp), tint = palette.encre)
            }
        }
        Box(Modifier.fillMaxWidth().size(height = TRAIT, width = 0.dp).background(palette.ombre))
    }
}

// Le bouton de traduction de l'en-tête et son menu : « Toujours traduire »,
// un interrupteur (annoncé comme tel par TalkBack).
@Composable
private fun MenuTraduction(actif: Boolean, basculer: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    var ouvert by remember { mutableStateOf(false) }
    val forme = RoundedCornerShape(12.dp)
    Box {
        Box(
            Modifier
                .size(40.dp)
                .carteDure(forme, palette.carte, palette.trait, palette.ombre, 2.dp)
                .clip(forme)
                .clickable(role = Role.Button, onClickLabel = textes.t("options_trad")) { ouvert = true }
                .semantics { contentDescription = textes.t("options_trad") },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icones.traduire, null, Modifier.size(22.dp), tint = palette.encre)
        }
        DropdownMenu(
            expanded = ouvert,
            onDismissRequest = { ouvert = false },
            shape = RoundedCornerShape(18.dp),
            containerColor = palette.carte,
            border = BorderStroke(TRAIT, palette.trait),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            DropdownMenuItem(
                text = { Text(textes.t("toujours"), color = palette.encre, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) },
                leadingIcon = { Icon(Icones.traduire, null, Modifier.size(20.dp), tint = palette.encre) },
                trailingIcon = {
                    Switch(
                        checked = actif,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = palette.carte,
                            checkedTrackColor = palette.encre,
                            checkedBorderColor = palette.encre,
                            uncheckedThumbColor = palette.gris,
                            uncheckedTrackColor = palette.doux,
                            uncheckedBorderColor = palette.trait,
                        ),
                    )
                },
                onClick = {
                    ouvert = false
                    basculer(!actif)
                },
                modifier = Modifier.semantics(mergeDescendants = true) {
                    role = Role.Switch
                    toggleableState = ToggleableState(actif)
                },
            )
        }
    }
}

// « Aujourd'hui », « Hier », sinon « samedi 3 octobre » (l'année si c'est
// loin), dans la langue de la bulle.
private fun jour(ms: Long, textes: Textes, locale: Locale): String {
    val debut = MiseEnLignes.debutDuJour(ms)
    val aujourdhui = MiseEnLignes.debutDuJour(System.currentTimeMillis())
    val ecart = Math.round((aujourdhui - debut) / 86_400_000.0)
    if (ecart == 0L) return textes.t("aujourdhui")
    if (ecart == 1L) return textes.t("hier")
    val annee = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR) != Calendar.getInstance().get(Calendar.YEAR)
    val motif = DateFormat.getBestDateTimePattern(locale, if (annee || ecart > 300) "EEEEdMMMMyyyy" else "EEEEdMMMM")
    return SimpleDateFormat(motif, locale).format(Date(ms)).replaceFirstChar { it.titlecase(locale) }
}

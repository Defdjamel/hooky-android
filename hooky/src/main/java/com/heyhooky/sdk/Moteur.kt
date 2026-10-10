package com.heyhooky.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.io.IOException

// Le message en route : affiché estompé dans le fil, avec sa progression.
internal data class EnvoiEnCours(
    val texte: String,
    val fichiers: List<FichierChoisi>,
    val progres: Float?,
    val debut: Long,
)

// Tout ce que l'écran de conversation affiche.
internal data class EtatChat(
    val site: Site? = null,
    val conversation: Conversation? = null,
    val messages: List<Message> = emptyList(),
    val encore: Boolean = false,
    val envoi: EnvoiEnCours? = null,
    val equipeEcrit: String? = null,
    val luParEquipe: Long? = null,
    val erreur: String? = null,
    val chargeAnciens: Boolean = false,
    // La traduction des messages de l'équipe (voir Traduction.kt) : où en est
    // chacun, par id, et « Toujours traduire » (gardé par site).
    val traductions: Map<Long, EtatTraduction> = emptyMap(),
    val toujoursTraduire: Boolean = false,
) {
    // Le message en route est-il déjà arrivé par Action Cable avant la
    // réponse de l'API ? Alors on ne l'affiche pas deux fois.
    val envoiVisible: EnvoiEnCours?
        get() {
            val envoi = envoi ?: return null
            val arrive = messages.any {
                it.expediteur == Expediteur.VISITEUR && it.creeLe >= envoi.debut - 60_000 &&
                    it.texte == envoi.texte && it.pieces.size == envoi.fichiers.size
            }
            return if (arrive) null else envoi
        }
}

// La mécanique de la bulle, sans interface : le site, la conversation, le
// temps réel, les envois, les non-lus. Tout se passe sur la portée donnée
// (le fil principal dans l'app) ; Hooky en garde une seule instance.
internal class Moteur(
    val cleSite: String,
    private val api: Api,
    private val stockage: Stockage,
    private val portee: CoroutineScope,
    val textes: Textes,
    private val identite: Identite,
    private val fuseau: String?,
    private val page: String?,
    reconnexion: Reconnexion = Reconnexion(),
    private val horloge: () -> Long = System::currentTimeMillis,
    // « Samsung SM-S918B · Android 15 ».
    private val appareil: String? = null,
    // Plusieurs changements du profil en moins d'une seconde : un seul PATCH.
    private val delaiProfil: Long = 1_000,
    // Hooky.configure(translation = false) : ni lien ni menu de traduction.
    val traductionActive: Boolean = true,
) {
    private val _etat = MutableStateFlow(EtatChat(toujoursTraduire = stockage.toujoursTraduire))
    val etat: StateFlow<EtatChat> = _etat.asStateFlow()

    private val _nonLus = MutableStateFlow(0)
    val nonLus: StateFlow<Int> = _nonLus.asStateFlow()

    // identify(name, email) : envoyés à l'ouverture de la conversation.
    var nomVisiteur: String? = null
    var emailVisiteur: String? = null
    val identifie: Boolean get() = !nomVisiteur.isNullOrBlank() || !emailVisiteur.isNullOrBlank()

    // identify(userId) et les données libres (setAttributes) : tout part avec
    // le premier message ; ensuite, ce qui change part par PATCH.
    var utilisateur: String? = null
        private set
    private val _donnees = LinkedHashMap<String, Any>()
    val donnees: Map<String, Any> get() = _donnees

    // Ce que le serveur ne sait pas encore, et ce qu'il sait déjà (envoyé
    // pendant cette vie de l'app : un identify répété à chaque lancement ne
    // part qu'une fois).
    var profilAEnvoyer = Profil()
        private set
    private var profilConnu = Profil()
    private var envoiProfil: Job? = null
    private var profilPlanifie = false

    // Le brouillon survit à la fermeture de l'écran, et de l'app.
    var brouillon: String = stockage.brouillon.orEmpty()
        private set

    // Les fichiers choisis et le formulaire d'accueil, pas encore envoyés :
    // gardés le temps du processus, comme le panneau de la bulle web.
    val choisis = MutableStateFlow<List<FichierChoisi>>(emptyList())
    var prenomSaisi: String = ""
    var emailSaisi: String = ""

    private var jeton: String? = stockage.jeton
    private var premierPlan = false
    private var ouvert = false
    private var luEnvoye: Long? = null
    private var dernierRafraichi = 0L
    private var chargementSite: Job? = null
    private var typingFin: Job? = null
    // Les traductions à demander, une à la fois.
    private val fileTraductions = ArrayDeque<Long>()
    private var traducteur: Job? = null

    val cable = Cable(
        client = api.client,
        portee = portee,
        adresse = ::adresseCable,
        surEvenement = ::recevoir,
        surAbonne = ::rattraper,
        surRefus = { oublier() },
        reconnexion = reconnexion,
        horloge = horloge,
    )

    val aConversation: Boolean get() = jeton != null

    val adresseApi: String get() = api.base

    // Hooky.track : sans attendre, sans erreur.
    fun suivre(nom: String, valeur: Double?, proprietes: Map<String, Any?>) {
        api.suivre(cleSite, nom, valeur, proprietes)
    }

    // Au lancement : l'apparence du site, puis la conversation enregistrée.
    fun demarrer() {
        chargerSite()
        if (jeton != null) portee.launch { relireFil() }
    }

    private fun chargerSite() {
        if (_etat.value.site != null || chargementSite?.isActive == true) return
        chargementSite = portee.launch {
            try {
                val site = api.site(cleSite)
                _etat.update { it.copy(site = site) }
            } catch (_: Exception) {
                // Pas de réseau, ou site introuvable : la bulle reste cachée,
                // on réessaiera au prochain retour au premier plan.
            }
        }
    }

    // L'app passe au premier plan (ou en arrière-plan) : le temps réel suit.
    fun premierPlan(actif: Boolean) {
        if (premierPlan == actif) return
        premierPlan = actif
        if (actif) {
            chargerSite()
            brancher()
            marquerLu()
            verifierLiens()
            viderProfil()
        } else {
            cable.fermer()
            stockage.brouillon = brouillon
        }
    }

    // L'écran de conversation est affiché (ou non).
    fun ouvert(actif: Boolean) {
        ouvert = actif
        if (actif) {
            chargerSite()
            marquerLu()
            verifierLiens()
        } else {
            stockage.brouillon = brouillon
        }
    }

    fun saisie(texte: String) {
        brouillon = texte
        if (texte.isNotBlank() && jeton != null) cable.frappe()
    }

    fun effacerErreur() {
        if (_etat.value.erreur != null && _etat.value.envoi == null) _etat.update { it.copy(erreur = null) }
    }

    fun erreur(texte: String?) {
        _etat.update { it.copy(erreur = texte) }
    }

    // identify : le prénom et l'e-mail remplacent les précédents (null les
    // efface ici, pas sur le serveur) ; userId null ne change rien. Rend
    // vrai si la conversation a été oubliée (un autre compte).
    fun identifier(nom: String?, email: String?, utilisateur: String? = null, donnees: Map<String, Any?> = emptyMap()): Boolean {
        val n = nom?.trim()?.takeIf { it.isNotEmpty() }
        val e = email?.trim()?.takeIf { it.isNotEmpty() }
        val u = utilisateur?.trim()?.takeIf { it.isNotEmpty() }
        // Un autre compte sur cet appareil : il ne voit pas le fil du précédent.
        val avant = stockage.utilisateur
        val autreCompte = u != null && jeton != null && avant != null && avant != u
        if (autreCompte) oublier(identite = true)
        nomVisiteur = n
        emailVisiteur = e
        if (u != null) {
            this.utilisateur = u
            if (jeton != null) stockage.utilisateur = u
        }
        retenir(Profil(n, e, u, Profil.nettoyer(donnees)))
        return autreCompte
    }

    // setAttributes : fusionnées avec les précédentes, null supprime.
    fun ajouterDonnees(donnees: Map<String, Any?>) {
        retenir(Profil(donnees = Profil.nettoyer(donnees)))
    }

    // Retenu pour le premier message, et pour le PATCH s'il y a déjà une
    // conversation (sauf ce que le serveur sait déjà).
    private fun retenir(changement: Profil) {
        changement.donnees.forEach { (cle, v) -> if (v == null) _donnees.remove(cle) else _donnees[cle] = v }
        val connu = profilConnu
        val neuf = Profil(
            nom = changement.nom?.takeIf { it != connu.nom },
            email = changement.email?.takeIf { it != connu.email },
            utilisateur = changement.utilisateur?.takeIf { it != connu.utilisateur },
            donnees = changement.donnees.filter { (cle, v) -> !connu.donnees.containsKey(cle) || connu.donnees[cle] != v },
        )
        if (neuf.vide) return
        profilAEnvoyer = profilAEnvoyer.fusion(neuf)
        planifierProfil()
    }

    // Un seul PATCH pour les changements d'une même seconde.
    private fun planifierProfil(delai: Long = delaiProfil) {
        if (jeton == null || profilAEnvoyer.vide || profilPlanifie) return
        profilPlanifie = true
        envoiProfil = portee.launch {
            if (delai > 0) delay(delai)
            envoyerProfil()
        }
    }

    // Ce qui attend part tout de suite (après un envoi, au retour en ligne).
    private fun viderProfil() = planifierProfil(0)

    // Jamais d'erreur affichée : hors ligne, on réessaiera au prochain envoi.
    suspend fun envoyerProfil() {
        profilPlanifie = false
        val jeton = jeton ?: return
        if (profilAEnvoyer.vide) return
        val envoi = profilAEnvoyer
        profilAEnvoyer = Profil()
        try {
            val conversation = api.modifier(jeton, envoi.json(appareil))
            if (jeton != this.jeton) return
            profilConnu = profilConnu.fusion(envoi)
            _etat.update { e ->
                e.copy(conversation = e.conversation?.copy(nomVisiteur = conversation.nomVisiteur, emailVisiteur = conversation.emailVisiteur))
            }
        } catch (e: ErreurApi) {
            when {
                e.statut == 401 -> if (jeton == this.jeton) oublier()
                // Refusé tel quel (ou un serveur sans cette route : 404, sans
                // oublier la conversation pour autant) : le renvoyer n'y changerait rien.
                e.statut in 400..499 && e.statut != 408 && e.statut != 429 -> Unit
                else -> if (jeton == this.jeton) profilAEnvoyer = envoi.fusion(profilAEnvoyer)
            }
        } catch (e: Exception) {
            if (jeton == this.jeton) profilAEnvoyer = envoi.fusion(profilAEnvoyer)
            if (e is CancellationException) throw e
        }
    }

    // Le texte et les fichiers partent ensemble. Rend vrai si c'est parti ;
    // sinon l'erreur est dans l'état et l'écran remet le brouillon.
    suspend fun envoyer(texte: String, fichiers: List<FichierChoisi>, nom: String? = null, email: String? = null): Boolean {
        val propre = texte.trim()
        if (_etat.value.envoi != null || (propre.isEmpty() && fichiers.isEmpty())) return false
        val premier = jeton == null
        val envois = fichiers.map { it.versEnvoi() }
        _etat.update { it.copy(erreur = null, envoi = EnvoiEnCours(propre, fichiers, if (fichiers.isEmpty()) null else 0f, horloge())) }
        val progres: (Float) -> Unit = { part ->
            portee.launch { _etat.update { e -> e.envoi?.let { e.copy(envoi = it.copy(progres = part)) } ?: e } }
        }
        // Le premier message emporte tout ce que l'app a dit du visiteur ; ce
        // qui change pendant l'envoi partira ensuite par PATCH.
        var retenu: Profil? = null
        return try {
            if (premier) {
                val nomEnvoye = (nom ?: nomVisiteur)?.trim()?.take(100)
                val emailEnvoye = (email ?: emailVisiteur)?.trim()
                val compte = utilisateur
                val extra = LinkedHashMap<String, Any?>(donnees)
                retenu = profilAEnvoyer
                profilAEnvoyer = Profil()
                val fil = api.ouvrir(
                    cleSite = cleSite,
                    texte = propre,
                    nom = nomEnvoye,
                    email = emailEnvoye,
                    langue = textes.langue,
                    fuseau = fuseau,
                    page = page,
                    fichiers = envois,
                    progres = progres,
                    utilisateur = compte,
                    donnees = extra,
                    appareil = appareil,
                )
                jeton = fil.conversation.jeton
                stockage.jeton = jeton
                stockage.utilisateur = compte
                stockage.luJusqua = fil.messages.filter { it.expediteur == Expediteur.EQUIPE }.maxOfOrNull { it.id } ?: 0
                profilConnu = Profil(nomEnvoye?.takeIf { it.isNotEmpty() }, emailEnvoye?.takeIf { it.isNotEmpty() }, compte, extra)
                retenu = null
                charger(fil, remplacer = true)
                brancher()
            } else {
                val message = api.envoyer(jeton!!, propre, envois, progres)
                fusionner(listOf(message))
            }
            brouillon = ""
            stockage.brouillon = null
            _etat.update { it.copy(envoi = null) }
            viderProfil()
            true
        } catch (e: Exception) {
            retenu?.let { profilAEnvoyer = it.fusion(profilAEnvoyer) }
            if (e is ErreurApi && e.statut == 401) oublier()
            _etat.update { it.copy(envoi = null, erreur = texteErreur(e)) }
            false
        }
    }

    fun texteErreur(e: Throwable): String {
        val code = (e as? ErreurApi)?.code
        val statut = (e as? ErreurApi)?.statut
        return when {
            code == "file_too_large" -> textes.t("lourd_api")
            code == "file_type_not_allowed" -> textes.t("refuse_api")
            code == "too_many_files" -> textes.t("quatre")
            code == "empty_message" -> textes.t("vide")
            code == "storage_unavailable" -> textes.t("stockage")
            statut == 429 -> textes.t("trop")
            statut == 0 || (e is IOException && e !is ErreurApi) -> textes.t("horsligne")
            else -> textes.t("echec")
        }
    }

    // ------------------------------------------------------- traduction --

    // Ce message peut-il se traduire ici ?
    fun traduisible(message: Message): Boolean =
        traductionActive && RegleTraduction.traduisible(message, textes.langue)

    // Le menu « Toujours traduire » n'est proposé que s'il sert : un message
    // de l'équipe dans une autre langue (ou le réglage déjà actif).
    fun proposeTraduction(etat: EtatChat = _etat.value): Boolean =
        traductionActive && (etat.toujoursTraduire || etat.messages.any(::traduisible))

    // « Traduire », ou « Réessayer » après une erreur. Déjà en mémoire : aucun appel.
    fun traduire(message: Message) {
        if (!traduisible(message)) return
        when (val actuel = _etat.value.traductions[message.id]) {
            EtatTraduction.EnCours -> return
            is EtatTraduction.Faite -> return poserTraductions(mapOf(message.id to actuel.copy(original = false)))
            else -> Unit
        }
        poserTraductions(mapOf(message.id to EtatTraduction.EnCours))
        enfiler(listOf(message.id))
    }

    // « Voir l’original » / « Voir la traduction ».
    fun basculerOriginal(message: Message) {
        val actuel = _etat.value.traductions[message.id] as? EtatTraduction.Faite ?: return
        poserTraductions(mapOf(message.id to actuel.copy(original = !actuel.original)))
    }

    // Activé : les messages déjà traduits passent en traduction (les autres
    // se traduisent) ; coupé : on relit les originaux.
    fun toujoursTraduire(actif: Boolean) {
        stockage.toujoursTraduire = actif
        _etat.update { e ->
            e.copy(
                toujoursTraduire = actif,
                traductions = e.traductions.mapValues { (_, t) -> if (t is EtatTraduction.Faite) t.copy(original = !actif) else t },
            )
        }
        traduireLesNouveaux()
    }

    // « Toujours traduire » : chaque message qui peut l'être, une seule fois
    // (une erreur laisse « Réessayer »), du plus récent au plus ancien.
    private fun traduireLesNouveaux() {
        val etat = _etat.value
        if (!etat.toujoursTraduire || !traductionActive || jeton == null) return
        val ids = etat.messages.asReversed().filter { traduisible(it) && it.id !in etat.traductions }.map { it.id }
        if (ids.isEmpty()) return
        poserTraductions(ids.associateWith { EtatTraduction.EnCours })
        enfiler(ids)
    }

    private fun poserTraductions(changements: Map<Long, EtatTraduction?>) {
        _etat.update { e ->
            val tout = e.traductions.toMutableMap()
            changements.forEach { (id, t) -> if (t == null) tout.remove(id) else tout[id] = t }
            e.copy(traductions = tout)
        }
    }

    // Une traduction à la fois : un long fil traduit d'un coup ne doit pas
    // buter sur la limite du serveur (30 par minute).
    private fun enfiler(ids: List<Long>) {
        ids.forEach { if (it !in fileTraductions) fileTraductions.addLast(it) }
        if (traducteur?.isActive == true) return
        traducteur = portee.launch {
            while (fileTraductions.isNotEmpty()) demanderTraduction(fileTraductions.removeFirst())
        }
    }

    private suspend fun demanderTraduction(id: Long) {
        val jeton = jeton
        val message = _etat.value.messages.firstOrNull { it.id == id }
        if (jeton == null || message == null || !traduisible(message)) return poserTraductions(mapOf(id to null))
        try {
            val traduction = api.traduire(jeton, id, textes.langue)
            if (jeton != this.jeton) return
            val source = RegleTraduction.codeCourt(traduction.source ?: message.langue)
            if (source == RegleTraduction.codeCourt(textes.langue)) {
                // Le message était déjà dans la langue de la bulle : plus de
                // lien (le serveur corrige aussi sa langue et le rediffuse).
                _etat.update { e ->
                    e.copy(
                        messages = e.messages.map { if (it.id == id) it.copy(langue = traduction.source) else it },
                        traductions = e.traductions - id,
                    )
                }
            } else {
                poserTraductions(mapOf(id to EtatTraduction.Faite(traduction.texte, source)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (jeton == this.jeton) poserTraductions(mapOf(id to EtatTraduction.Echec))
        }
    }

    // Remonter le fil : la page d'avant le plus ancien message affiché.
    fun plusAnciens() {
        val premier = _etat.value.messages.firstOrNull() ?: return
        val jeton = jeton ?: return
        if (_etat.value.chargeAnciens) return
        _etat.update { it.copy(chargeAnciens = true) }
        portee.launch {
            try {
                val fil = api.fil(jeton, avant = premier.id)
                fusionner(fil.messages)
                _etat.update { it.copy(encore = fil.encore) }
            } catch (_: Exception) {
                // On pourra réessayer.
            } finally {
                _etat.update { it.copy(chargeAnciens = false) }
            }
        }
    }

    // Le visiteur a vu le fil : l'équipe voit « Lu », les non-lus tombent.
    fun marquerLu() {
        val jeton = jeton ?: return
        if (!ouvert || !premierPlan) return
        val dernier = _etat.value.messages.lastOrNull { it.expediteur == Expediteur.EQUIPE }?.id ?: return
        if (dernier > stockage.luJusqua) stockage.luJusqua = dernier
        compterNonLus()
        if (luEnvoye == dernier) return
        luEnvoye = dernier
        portee.launch {
            try {
                api.marquerLu(jeton)
            } catch (_: Exception) {
                luEnvoye = null
            }
        }
    }

    // Des liens de pièces jointes expirés (une heure) : on relit le fil, page
    // après page jusqu'au plus ancien message affiché. Une fois par 20 s au
    // plus, pour qu'une image vraiment cassée ne fasse pas tourner la bulle.
    fun rafraichirLiens() {
        val jeton = jeton ?: return
        val messages = _etat.value.messages
        if (messages.isEmpty() || horloge() - dernierRafraichi < 20_000) return
        dernierRafraichi = horloge()
        val plusAncien = messages.first().id
        portee.launch {
            try {
                var avant: Long? = null
                while (true) {
                    val fil = api.fil(jeton, avant)
                    fusionner(fil.messages)
                    if (avant == null) _etat.update { it.copy(luParEquipe = fil.conversation.luParEquipe) }
                    val premier = fil.messages.firstOrNull()
                    if (fil.encore && premier != null && premier.id > plusAncien) avant = premier.id else break
                }
            } catch (_: Exception) {
                // Tant pis : la prochaine tentative dans 20 s.
            }
        }
    }

    private fun verifierLiens() {
        val perimes = _etat.value.messages.any { it.pieces.isNotEmpty() && horloge() - it.recuLe > LIENS_FRAIS }
        if (perimes && (ouvert || premierPlan)) rafraichirLiens()
    }

    // reset(), ou un jeton qui ne vaut plus rien : on repart d'une page
    // blanche (le site reste chargé). identite : ce que l'app a dit du
    // visiteur part aussi (reset, un autre compte).
    fun oublier(identite: Boolean = false) {
        cable.fermer()
        jeton = null
        luEnvoye = null
        envoiProfil?.cancel()
        profilPlanifie = false
        profilAEnvoyer = Profil()
        profilConnu = Profil()
        if (identite) {
            nomVisiteur = null
            emailVisiteur = null
            utilisateur = null
            _donnees.clear()
        }
        brouillon = ""
        choisis.value = emptyList()
        prenomSaisi = ""
        emailSaisi = ""
        stockage.effacer()
        traducteur?.cancel()
        fileTraductions.clear()
        // « Toujours traduire » est un réglage de l'appareil : il reste.
        _etat.update { EtatChat(site = it.site, toujoursTraduire = it.toujoursTraduire) }
        _nonLus.value = 0
    }

    private fun adresseCable(): HttpUrl? {
        val jeton = jeton ?: return null
        return Protocole.adresse(api.base, jeton, identite.client, identite.app)
    }

    private fun brancher() {
        if (premierPlan && jeton != null && _etat.value.conversation != null) cable.ouvrir()
    }

    private suspend fun relireFil() {
        val jeton = jeton ?: return
        try {
            charger(api.fil(jeton), remplacer = false)
            brancher()
            // De retour en ligne : ce qui n'était pas parti part.
            viderProfil()
        } catch (e: ErreurApi) {
            if (e.statut == 401) oublier()
        } catch (_: Exception) {
            // Hors ligne : on réessaiera au retour au premier plan.
        }
    }

    // À la confirmation de l'abonnement (après une coupure) : on relit le fil
    // pour ne rien manquer.
    private fun rattraper() {
        portee.launch { relireFil() }
    }

    private fun charger(fil: Fil, remplacer: Boolean) {
        _etat.update {
            it.copy(
                conversation = fil.conversation,
                luParEquipe = fil.conversation.luParEquipe,
                encore = if (remplacer || it.messages.isEmpty()) fil.encore else it.encore,
            )
        }
        fusionner(fil.messages, remplacer)
    }

    // Un message déjà connu est remplacé : relu, il apporte des liens neufs.
    private fun fusionner(arrives: List<Message>, remplacer: Boolean = false) {
        _etat.update { etat ->
            val index = LinkedHashMap<Long, Message>()
            if (!remplacer) etat.messages.forEach { index[it.id] = it }
            arrives.forEach { index[it.id] = it }
            etat.copy(messages = index.values.sortedWith(compareBy({ it.creeLe }, { it.id })))
        }
        compterNonLus()
        marquerLu()
        traduireLesNouveaux()
    }

    private fun compterNonLus() {
        val lu = stockage.luJusqua
        _nonLus.value = _etat.value.messages.count { it.expediteur == Expediteur.EQUIPE && it.id > lu }
    }

    private fun recevoir(evenement: EvenementConversation) {
        when (evenement) {
            is EvenementConversation.NouveauMessage -> {
                if (evenement.message.expediteur == Expediteur.EQUIPE) _etat.update { it.copy(equipeEcrit = null) }
                fusionner(listOf(evenement.message.copy(recuLe = horloge())))
                // Un message du visiteur rouvre une conversation fermée.
                if (evenement.message.expediteur == Expediteur.VISITEUR) {
                    _etat.update { e -> e.copy(conversation = e.conversation?.copy(fermee = false)) }
                }
            }
            is EvenementConversation.Ecrit -> if (evenement.expediteur == Expediteur.EQUIPE) {
                _etat.update { it.copy(equipeEcrit = evenement.auteur ?: it.site?.nom ?: "") }
                typingFin?.cancel()
                typingFin = portee.launch {
                    delay(TYPING_AFFICHE)
                    _etat.update { it.copy(equipeEcrit = null) }
                }
            }
            is EvenementConversation.Lu -> if (evenement.expediteur == Expediteur.EQUIPE) {
                _etat.update { it.copy(luParEquipe = evenement.le) }
            }
        }
    }

    companion object {
        const val TYPING_AFFICHE = 4_000L
        // Les liens signés valent une heure : passé 50 minutes, on relit le fil.
        const val LIENS_FRAIS = 50 * 60 * 1000L
    }
}

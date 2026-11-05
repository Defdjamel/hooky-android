package com.heyhooky.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// L'agent IA du site, comme la partie « agent IA » de widget/widget.js :
// - il répond tant que la conversation le dit (agent et pas handed_off), ou,
//   avant le premier message, tant que le site le dit (site.agent) ; la
//   mention IA est là (AI Act : le visiteur sait qu'il parle à une IA) ;
// - une fois qu'il a répondu, « Parler à un humain » remplace la mention
//   (POST /v1/widget/conversation/handoff) ; au passage (son bouton, ou
//   l'agent qui transmet, l'événement « handoff »), l'e-mail est demandé si
//   on ne l'a pas, pour que le visiteur ait la réponse s'il part ;
// - ses messages portent des blocs : fiches produit, bouton lien, choix
//   rapides (sur le dernier message du fil seulement) ; un choix touché part
//   comme un message ({ reply_to_id, choice }) ; « Voir » d'une fiche compte
//   l'événement agent_product_click (Hooky.track) ;
// - une réponse arrivée en direct apparaît au fur et à mesure : le texte mot
//   par mot, puis chaque fiche, le lien, les choix. Pas avec « Supprimer les
//   animations » ni TalkBack : tout est là d'un coup.

// « Parler à un humain » : la demande en route, ou ratée.
internal enum class EtatPassage { ENVOI, ECHEC }

// La carte qui demande l'e-mail au passage à un humain.
internal enum class EtatEmailPassage { DEMANDE, INVALIDE, ENVOI, MERCI }

// Une réponse de l'agent en cours de révélation : les mots montrés (la fin
// de chacun, en caractères), puis les unités (chaque fiche, le lien, les
// choix) montrées une à une.
internal data class Revelation(
    val id: Long,
    val finsDesMots: List<Int>,
    val unites: Int,
    val motsMontres: Int,
    val unitesMontrees: Int = 0,
    // L'unité des choix rapides : ils ne se touchent qu'une fois montrés.
    val uniteDesChoix: Int? = null,
) {
    val texteFini: Boolean get() = motsMontres >= finsDesMots.size
    val choixMontres: Boolean get() = uniteDesChoix?.let { unitesMontrees > it } ?: true

    // Le texte montré jusqu'ici.
    fun texteMontre(texte: String): String = when {
        texteFini -> texte
        motsMontres <= 0 -> ""
        else -> texte.take(finsDesMots[motsMontres - 1])
    }
}

// L'agent répond-il ? La conversation le dit, sinon, avant le premier
// message, le site.
internal val EtatChat.agentActif: Boolean
    get() = conversation?.let { it.agent && !it.passeeAUnHumain } ?: (site?.agent == true)

// « Parler à un humain » : l'agent répond et a déjà répondu.
internal val EtatChat.humainPropose: Boolean
    get() = conversation != null && agentActif && messages.any { it.bot }

// La mention reste tant que l'agent répond ; le bouton la remplace une fois
// qu'il a répondu (ses messages portent alors l'étiquette).
internal val EtatChat.mentionIa: Boolean
    get() = agentActif && !humainPropose

// Les choix rapides : sur le dernier message du fil seulement, tant que la
// conversation n'est pas passée à un humain et qu'aucun envoi n'est en route.
internal fun EtatChat.choixVisibles(message: Message): Boolean =
    message.id == messages.lastOrNull()?.id && envoi == null && conversation?.passeeAUnHumain != true

// Les unités révélées une à une : chaque fiche, le lien, les choix.
internal val Message.unites: Int
    get() = blocs.sumOf { if (it is Bloc.Produits) it.fiches.size else 1 }

internal val Message.uniteDesChoix: Int?
    get() {
        var n = 0
        for (bloc in blocs) {
            if (bloc is Bloc.Choix) return n
            n += if (bloc is Bloc.Produits) bloc.fiches.size else 1
        }
        return null
    }

internal class Agent(private val moteur: Moteur) {
    // Les réponses de l'agent arrivées en direct, à révéler.
    private val aReveler = HashSet<Long>()
    private var revelateur: Job? = null

    private val etat get() = moteur._etat

    // ------------------------------------------------- le passage à un humain

    fun parlerAUnHumain() {
        val jeton = moteur.jeton ?: return
        if (etat.value.passage == EtatPassage.ENVOI) return
        etat.update { it.copy(passage = EtatPassage.ENVOI) }
        moteur.portee.launch {
            try {
                val conversation = moteur.api.passerAUnHumain(jeton)
                if (jeton != moteur.jeton) return@launch
                passeAUnHumain(demanderEmail = true, aJour = conversation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (jeton != moteur.jeton) return@launch
                if (e is ErreurApi && e.statut == 401) moteur.oublier() else etat.update { it.copy(passage = EtatPassage.ECHEC) }
            }
        }
    }

    // La conversation est passée à l'équipe. Quand le visiteur l'a vu se
    // faire (son bouton, ou l'agent qui transmet) et qu'on n'a pas son
    // e-mail, la carte le lui demande.
    fun passeAUnHumain(demanderEmail: Boolean, aJour: Conversation? = null) {
        etat.update { e ->
            val actuelle = e.conversation ?: return@update e
            val deja = actuelle.passeeAUnHumain
            val conversation = (aJour?.takeIf { it.jeton.isNotEmpty() } ?: actuelle).copy(agent = false, passeeAUnHumain = true)
            val demande = !deja && demanderEmail && !emailConnu(conversation) && e.emailPassage == null
            e.copy(conversation = conversation, passage = null, emailPassage = if (demande) EtatEmailPassage.DEMANDE else e.emailPassage)
        }
    }

    private fun emailConnu(conversation: Conversation): Boolean =
        !conversation.emailVisiteur.isNullOrBlank() || !moteur.emailVisiteur.isNullOrBlank() || !moteur.profilConnu.email.isNullOrBlank()

    fun envoyerEmail(saisi: String) {
        val jeton = moteur.jeton ?: return
        if (etat.value.emailPassage == EtatEmailPassage.ENVOI) return
        val email = saisi.trim()
        if (!EMAIL_VALIDE.matches(email)) {
            etat.update { it.copy(emailPassage = EtatEmailPassage.INVALIDE) }
            return
        }
        etat.update { it.copy(emailPassage = EtatEmailPassage.ENVOI) }
        moteur.portee.launch {
            try {
                val conversation = moteur.api.modifier(jeton, Profil(email = email).json(moteur.appareil))
                if (jeton != moteur.jeton) return@launch
                moteur.profilConnu = moteur.profilConnu.fusion(Profil(email = email))
                etat.update { e ->
                    e.copy(
                        conversation = e.conversation?.copy(emailVisiteur = conversation.emailVisiteur ?: email),
                        emailPassage = EtatEmailPassage.MERCI,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (jeton != moteur.jeton) return@launch
                when {
                    e is ErreurApi && e.statut == 401 -> moteur.oublier()
                    e is ErreurApi && e.statut == 422 -> etat.update { it.copy(emailPassage = EtatEmailPassage.INVALIDE) }
                    else -> etat.update { it.copy(emailPassage = EtatEmailPassage.DEMANDE, erreur = moteur.texteErreur(e)) }
                }
            }
        }
    }

    fun emailModifie() {
        if (etat.value.emailPassage == EtatEmailPassage.INVALIDE) etat.update { it.copy(emailPassage = EtatEmailPassage.DEMANDE) }
    }

    fun plusTard() {
        etat.update { it.copy(emailPassage = null) }
    }

    // ------------------------------------------- les choix rapides, les fiches

    // Un choix touché part comme un message du visiteur (le serveur met le
    // libellé) ; les choix s'effacent pendant l'envoi.
    fun choisir(message: Message, option: ChoixRapide) {
        val e = etat.value
        if (moteur.jeton == null || !e.choixVisibles(message)) return
        // Pas encore affichés : pas encore cliquables.
        val r = e.revelation
        if (r != null && r.id == message.id && !r.choixMontres) return
        moteur.portee.launch { moteur.envoyer(option.libelle, emptyList(), choix = message.id to option.id) }
    }

    fun ficheOuverte(url: String) {
        moteur.suivre("agent_product_click", null, mapOf("url" to url))
    }

    // ------------------------------------------ la révélation progressive

    // À appeler avant fusionner : seul un message de l'agent encore inconnu,
    // reçu conversation à l'écran, sera révélé.
    fun noterAReveler(messages: List<Message>) {
        if (!moteur.ouvert || !moteur.premierPlan || !moteur.revelationPermise()) return
        val connus = etat.value.messages.map { it.id }.toHashSet()
        messages.filter { it.bot && it.id !in connus }.forEach { aReveler += it.id }
    }

    // Après fusionner : la dernière réponse notée et arrivée commence.
    fun revelerLesNotes() {
        if (aReveler.isEmpty()) return
        val arrives = etat.value.messages.filter { it.id in aReveler }
        aReveler.removeAll(arrives.map { it.id }.toSet())
        val message = arrives.lastOrNull() ?: return
        // Un message à traduire s'affiche d'un coup (comme la bulle web).
        if (moteur.traduisible(message)) return
        reveler(message)
    }

    fun reveler(message: Message) {
        terminerRevelation()
        val fins = finsDesMots(message.texte)
        val unites = message.unites
        if (fins.isEmpty() && unites == 0) return
        val id = message.id
        // Le premier mot est là tout de suite : pas de bulle vide.
        etat.update { it.copy(revelation = Revelation(id, fins, unites, if (fins.isEmpty()) 0 else 1, uniteDesChoix = message.uniteDesChoix)) }
        // Un long texte accélère : jamais plus de TEXTE_MAX.
        val pas = if (fins.isEmpty()) 0L else minOf(MOT, TEXTE_MAX / fins.size).coerceAtLeast(1L)
        revelateur = moteur.portee.launch {
            val debut = System.currentTimeMillis()
            while (true) {
                val r = etat.value.revelation?.takeIf { it.id == id } ?: return@launch
                if (r.texteFini) break
                delay(pas)
                val vus = minOf(r.finsDesMots.size, ((System.currentTimeMillis() - debut) / pas).toInt() + 1)
                etat.update { e -> e.revelation?.takeIf { it.id == id }?.let { e.copy(revelation = it.copy(motsMontres = vus)) } ?: e }
            }
            var premier = true
            while (true) {
                val r = etat.value.revelation?.takeIf { it.id == id } ?: return@launch
                if (r.unitesMontrees >= r.unites) break
                delay(if (premier && fins.isEmpty()) 0 else BLOC_ECART)
                premier = false
                etat.update { e -> e.revelation?.takeIf { it.id == id }?.let { e.copy(revelation = it.copy(unitesMontrees = it.unitesMontrees + 1)) } ?: e }
            }
            delay(BLOC_DUREE + if (unites > 0) 50 else 0)
            etat.update { e -> if (e.revelation?.id == id) e.copy(revelation = null) else e }
        }
    }

    // Fin immédiate (un autre message, un envoi, la conversation fermée…) :
    // tout est montré.
    fun terminerRevelation() {
        revelateur?.cancel()
        revelateur = null
        if (etat.value.revelation != null) etat.update { it.copy(revelation = null) }
    }

    fun oublier() {
        terminerRevelation()
        aReveler.clear()
    }

    companion object {
        // Les durées de widget.js (MOT_MS, TEXTE_MAX_MS, BLOC_ECART, BLOC_DUREE).
        const val MOT = 30L
        const val TEXTE_MAX = 2_500L
        const val BLOC_ECART = 250L
        const val BLOC_DUREE = 200L

        val EMAIL_VALIDE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

        // La fin de chaque mot (espaces compris), comme /\s*\S+\s*|\s+/g.
        fun finsDesMots(texte: String): List<Int> {
            val fins = ArrayList<Int>()
            var dansMot = false
            var vuMot = false
            texte.forEachIndexed { i, c ->
                if (c.isWhitespace()) {
                    dansMot = false
                } else {
                    if (!dansMot && vuMot && i > 0) fins += i
                    dansMot = true
                    vuMot = true
                }
            }
            if (texte.isNotEmpty()) fins += texte.length
            return fins
        }

        // La disponibilité telle que les catalogues l'écrivent (in stock,
        // out_of_stock, instock, preorder, backorder, un nombre…) dans la
        // langue de la bulle ; un autre texte reste tel quel. Rend le texte et
        // s'il s'agit d'une rupture.
        fun disponibilite(valeur: String, textes: Textes): Pair<String, Boolean> {
            val texte = valeur.trim()
            val cle = texte.lowercase().replace(Regex("[\\s_-]+"), "")
            if (cle.isNotEmpty() && cle.all { it in '0'..'9' }) {
                val zero = cle.all { it == '0' }
                return textes.t(if (zero) "rupture" else "en_stock") to zero
            }
            val connues = mapOf(
                "instock" to "en_stock", "available" to "en_stock", "outofstock" to "rupture", "soldout" to "rupture",
                "preorder" to "precommande", "backorder" to "sur_commande", "onbackorder" to "sur_commande",
            )
            return connues[cle]?.let { textes.t(it) to (it == "rupture") } ?: (texte to false)
        }
    }
}

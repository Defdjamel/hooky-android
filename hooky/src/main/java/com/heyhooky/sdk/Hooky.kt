package com.heyhooky.sdk

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.TimeZone
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit

/**
 * The Hooky chat bubble for Android.
 *
 * ```
 * Hooky.configure(context, "YOUR_SITE_KEY")   // once, at launch
 * HookyBubble()                                // Compose; or <com.heyhooky.sdk.HookyBubbleView/> in XML
 * Hooky.open(context)                          // or open the conversation yourself
 * ```
 *
 * Everything runs on the main thread and never throws: until [configure] has
 * been called, the other calls do nothing.
 */
public object Hooky {
    /** The Hooky API used when [configure] is given no other address. */
    public const val DEFAULT_API_URL: String = "https://api.heyhooky.com"

    /** The SDK version, sent to the API as `X-Hooky-Client: hooky-android/<version>`. */
    @JvmField
    public val VERSION: String = BuildConfig.VERSION_SDK

    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nonLus = MutableStateFlow(0)
    private var suiviNonLus: Job? = null
    private var suiviSite: Job? = null
    private val siteCourant = MutableStateFlow<Site?>(null)
    private var cycleDeVie: Application.ActivityLifecycleCallbacks? = null
    private var activiteOuverte: WeakReference<Activity>? = null
    // Ce que l'app a dit du visiteur, gardé ici aussi : identify avant
    // configure, et une nouvelle configuration, ne le perdent pas.
    private var nom: String? = null
    private var email: String? = null
    private var utilisateur: String? = null
    private val donnees = LinkedHashMap<String, Any?>()

    internal var moteur: Moteur? = null
        private set(valeur) {
            field = valeur
            moteurEtat.value = valeur
        }

    // Le même, lisible par Compose : HookyBubble apparaît dès configure.
    private val moteurEtat = mutableStateOf<Moteur?>(null)
    internal val moteurObserve: Moteur? get() = moteurEtat.value

    /**
     * Number of team messages the visitor has not seen yet, updated live
     * (it drops to 0 when the conversation is opened).
     */
    @JvmStatic
    public val unreadCount: StateFlow<Int> = nonLus.asStateFlow()

    /** True once [configure] has been called. */
    @JvmStatic
    public val isConfigured: Boolean get() = moteur != null

    /**
     * Call once at launch (Application.onCreate or your first Activity).
     * Loads the site's look, restores the saved conversation and, while the
     * app is in the foreground, keeps the realtime connection open.
     *
     * @param siteKey the public key of your site, from the Hooky dashboard.
     * @param apiUrl the Hooky API, only to change for local tests.
     * @param translation translates the team's messages written in another
     *   language than the bubble's as they arrive ("Always translate", a toggle
     *   button in the conversation's header the visitor can turn off), with a
     *   "Translate" link under them when it is off. On by default; `false`
     *   shows neither the button nor the link and translates nothing.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, siteKey: String, apiUrl: String = DEFAULT_API_URL, translation: Boolean = true) {
        val application = context.applicationContext as Application
        val actuel = moteur
        if (actuel != null) {
            if (actuel.cleSite == siteKey && actuel.adresseApi == apiUrl.trimEnd('/') && actuel.traductionActive == translation) return
            actuel.premierPlan(false)
            suiviNonLus?.cancel()
            suiviSite?.cancel()
        }

        val identite = Identite(client = nomClient(), app = descriptionApp(application))
        val api = Api(apiUrl.trimEnd('/'), clientHttp, identite)
        val stockage: Stockage = try {
            StockageFichier(application.noBackupFilesDir, siteKey)
        } catch (_: Exception) {
            StockageMemoire()
        }
        val nouveau = Moteur(
            cleSite = siteKey,
            api = api,
            stockage = stockage,
            portee = portee,
            textes = Textes.pour(),
            identite = identite,
            fuseau = TimeZone.getDefault().id,
            page = "android-app://" + application.packageName,
            appareil = Appareil.actuel(),
            traductionActive = translation,
        )
        // Avant le démarrage : un autre compte que celui de la conversation
        // enregistrée repart d'une page blanche.
        nouveau.identifier(nom, email, utilisateur, donnees)
        moteur = nouveau
        Images.initialiser(application, clientHttp)
        suiviNonLus = portee.launch { nouveau.nonLus.collect { nonLus.value = it } }
        suiviSite = portee.launch { nouveau.etat.collect { siteCourant.value = it.site } }
        surveillerPremierPlan(application)
        nouveau.demarrer()
        if (auPremierPlan()) nouveau.premierPlan(true)
    }

    /**
     * Optional: who the visitor is (your signed-in user). Never throws.
     *
     * Sent with the first message, or right away (grouped, within a second)
     * when a conversation already exists; retried with the next message if
     * the device is offline. With an email, the bubble skips its name and
     * email form.
     *
     * ```
     * Hooky.identify("Lou", "lou@example.com", "42", mapOf("plan" to "Pro", "cart" to 49.9))
     * ```
     *
     * @param name the first name; replaces the conversation's (`null` clears it on this device).
     * @param email the email; replaces the conversation's (`null` clears it on this device).
     * @param userId your own id for this user, shown to your team (not verified); `null` leaves it
     *   unchanged. A different `userId` than the current conversation's starts a new conversation,
     *   like [reset]: another account on the same device never sees the previous one's thread.
     * @param attributes free data shown to your team, merged with the previous ones: strings,
     *   numbers, booleans; `null` removes a key. Up to 50 keys.
     */
    @JvmStatic
    @JvmOverloads
    public fun identify(name: String?, email: String? = null, userId: String? = null, attributes: Map<String, Any?> = emptyMap()) {
        nom = name?.trim()?.takeIf { it.isNotEmpty() }
        this.email = email?.trim()?.takeIf { it.isNotEmpty() }
        val compte = userId?.trim()?.takeIf { it.isNotEmpty() }
        // Un autre compte : les données libres du précédent ne le suivent pas.
        if (compte != null && utilisateur != null && compte != utilisateur) donnees.clear()
        if (compte != null) utilisateur = compte
        retenir(attributes)
        val moteur = moteur ?: return
        if (moteur.identifier(nom, this.email, compte, attributes)) {
            // La conversation enregistrée était celle d'un autre compte.
            close()
            donnees.clear()
            retenir(attributes)
        }
    }

    /**
     * Adds free data about the visitor, shown to your team next to the
     * conversation: `setAttributes(mapOf("plan" to "Pro", "cart" to 49.9))`.
     * Merged with the previous data (`null` removes a key). Sent with the first
     * message, or right away (grouped, within a second) when a conversation
     * already exists. Never throws.
     */
    @JvmStatic
    public fun setAttributes(attributes: Map<String, Any?>) {
        retenir(attributes)
        moteur?.ajouterDonnees(attributes)
    }

    private fun retenir(attributes: Map<String, Any?>) {
        attributes.forEach { (cle, valeur) -> if (valeur == null) donnees.remove(cle) else donnees[cle] = valeur }
    }

    /** Opens the conversation full screen (an Activity, from any Context). */
    @JvmStatic
    public fun open(context: Context) {
        if (moteur == null) return
        val intention = Intent(context, HookyActivity::class.java)
        if (context !is Activity) intention.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intention)
    }

    /** Closes the conversation if it is open. */
    @JvmStatic
    public fun close() {
        activiteOuverte?.get()?.finish()
    }

    /**
     * Records an event for your Hooky stats, like `Hooky.track()` on the web:
     * `track("signup")`, `track("order", 49.0)`, `track("order", 49.0, mapOf("plan" to "pro"))`.
     * Never throws; failures are silent.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(name: String, value: Double? = null, properties: Map<String, Any?> = emptyMap()) {
        val moteur = moteur ?: return
        if (name.isBlank()) return
        moteur.suivre(name, value, properties)
    }

    /** Forgets the conversation, the identity and the attributes (call it when your user signs out). */
    @JvmStatic
    public fun reset() {
        nom = null
        email = null
        utilisateur = null
        donnees.clear()
        close()
        moteur?.oublier(identite = true)
    }

    /**
     * For Java and View-based apps: [listener] is called on the main thread
     * with the current count, then on every change. Close the returned
     * handle to stop listening.
     */
    @JvmStatic
    public fun addUnreadCountListener(listener: UnreadCountListener): Closeable {
        val job = portee.launch { nonLus.collect { listener.onUnreadCountChanged(it) } }
        return Closeable { job.cancel() }
    }

    /** Receives the number of unread team messages. */
    public fun interface UnreadCountListener {
        public fun onUnreadCountChanged(count: Int)
    }

    // Pour les surcouches du SDK (le plugin Flutter) : se présenter au
    // serveur sous leur propre nom, et lire l'apparence du site pour dessiner
    // leur bulle. Réservé, d'où l'opt-in.

    /**
     * The `X-Hooky-Client` value (`hooky-flutter/1.0.0`…), set before
     * [configure]; `null` for the default `hooky-android/<version>`.
     */
    @HookyWrapperApi
    @JvmStatic
    public var clientName: String? = null

    @OptIn(HookyWrapperApi::class)
    private fun nomClient(): String = clientName?.trim()?.takeIf { it.isNotEmpty() } ?: "hooky-android/$VERSION"

    /**
     * [listener] is called on the main thread with the site's name, color
     * (`#RRGGBB`) and bubble side (`left` or `right`) once its appearance is
     * loaded, then at every change. Close the handle to stop listening.
     */
    @HookyWrapperApi
    @JvmStatic
    public fun addSiteListener(listener: SiteListener): Closeable {
        val job = portee.launch {
            siteCourant.collect { site -> site?.let { listener.onSite(it.nom, it.couleur, it.position) } }
        }
        return Closeable { job.cancel() }
    }

    /** Receives the site's appearance. */
    @HookyWrapperApi
    public fun interface SiteListener {
        public fun onSite(name: String, color: String, position: String)
    }

    internal fun activiteOuverte(activite: Activity?) {
        activiteOuverte = activite?.let { WeakReference(it) }
    }

    internal val clientHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Quatre photos de 10 Mo sur un réseau lent.
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    // « com.exemple.app 2.3.1 (231) » : pour l'équipe, comme le navigateur
    // pour la bulle web.
    private fun descriptionApp(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${context.packageName} ${info.versionName ?: "?"} (${PackageInfoCompat.getLongVersionCode(info)})"
    } catch (_: Exception) {
        context.packageName
    }

    // Le temps réel ne vit qu'au premier plan : on compte les activités
    // démarrées de l'app (sans dépendre de lifecycle-process).
    private fun surveillerPremierPlan(application: Application) {
        if (cycleDeVie != null) return
        val rappels = object : Application.ActivityLifecycleCallbacks {
            // Les activités démarrées depuis configure. Une activité déjà
            // démarrée avant (configure appelé après son onStart, comme dans
            // une app Flutter) n'y est pas : quand elle s'arrête parce que la
            // conversation s'ouvre par-dessus, l'app reste au premier plan ;
            // elle ne passe en arrière-plan que s'il n'en reste aucune.
            private val demarrees = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

            override fun onActivityStarted(activity: Activity) {
                demarrees.add(activity)
                if (demarrees.size == 1) moteur?.premierPlan(true)
            }

            override fun onActivityStopped(activity: Activity) {
                demarrees.remove(activity)
                if (demarrees.isEmpty() && !activity.isChangingConfigurations) moteur?.premierPlan(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(rappels)
        cycleDeVie = rappels
    }

    private fun auPremierPlan(): Boolean {
        val etat = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(etat)
        return etat.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }
}

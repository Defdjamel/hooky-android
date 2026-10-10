package com.heyhooky.sdk

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * The full-screen conversation opened by [Hooky.open]. You don't need to
 * start it yourself; it is declared in the SDK's manifest.
 */
public class HookyActivity : ComponentActivity() {
    private var moteur: Moteur? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // L'app a été relancée par le système directement ici, sans
        // configure : il n'y a rien à montrer.
        val moteur = Hooky.moteur ?: run {
            finish()
            return
        }
        this.moteur = moteur
        enableEdgeToEdge()
        setContent {
            val etat by moteur.etat.collectAsState()
            HookyTheme(etat.site, moteur.textes) {
                val palette = LocalPalette.current
                // Les icônes de la barre d'état se lisent sur l'en-tête (la
                // couleur du site), celles du bas sur la saisie.
                LaunchedEffect(palette) {
                    val transparent = Color.TRANSPARENT
                    enableEdgeToEdge(
                        statusBarStyle = if (palette.surAccent == Palette.ENCRE) SystemBarStyle.light(transparent, transparent) else SystemBarStyle.dark(transparent),
                        navigationBarStyle = if (palette.sombre) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    )
                }
                EcranConversation(moteur, fermer = ::finish)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Hooky.activiteOuverte(this)
        moteur?.ouvert(true)
    }

    override fun onStop() {
        moteur?.ouvert(false)
        Hooky.activiteOuverte(null)
        super.onStop()
    }
}

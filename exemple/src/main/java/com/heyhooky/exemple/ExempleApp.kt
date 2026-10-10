package com.heyhooky.exemple

import android.app.Application
import com.heyhooky.sdk.Hooky

// Une ligne au lancement : la clé publique du site (et, pour les tests
// locaux, l'adresse de l'API).
class ExempleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hooky.configure(this, BuildConfig.HOOKY_SITE_KEY, apiUrl = BuildConfig.HOOKY_API)
    }
}

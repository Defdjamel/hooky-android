package com.heyhooky.exemple

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.heyhooky.sdk.Hooky
import java.io.Closeable

// L'exemple pour une app en Views : HookyBubbleView posée dans le layout
// XML, Hooky.open() sur un bouton, et le nombre de non-lus par écouteur.
class VuesActivity : ComponentActivity() {
    private var ecoute: Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activite_vues)
        findViewById<Button>(R.id.ouvrir).setOnClickListener { Hooky.open(this) }
        val compteur = findViewById<TextView>(R.id.non_lus)
        ecoute = Hooky.addUnreadCountListener { n -> compteur.text = "Unread: $n" }
    }

    override fun onDestroy() {
        ecoute?.close()
        super.onDestroy()
    }
}

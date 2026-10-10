package com.heyhooky.exemple

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heyhooky.sdk.Hooky
import com.heyhooky.sdk.HookyBubble

// L'exemple en Compose : une vitrine, la bulle en bas à droite, et des
// boutons pour chaque appel du SDK.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val sombre = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (sombre) darkColorScheme(primary = Color(0xFFC9BDFF)) else lightColorScheme(primary = Color(0xFF43378A))) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        Vitrine()
                        HookyBubble(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .padding(20.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Vitrine() {
        val nonLus by Hooky.unreadCount.collectAsState()
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hooky Shop", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (nonLus > 0) "$nonLus unread repl${if (nonLus == 1) "y" else "ies"} from the team" else "No unread reply",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Produit("Ceramic mug", "€18", Color(0xFFFFD9C7), Modifier.weight(1f))
                Produit("Linen tote", "€32", Color(0xFFECE7FF), Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = { Hooky.open(this@MainActivity) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open")
            }
            OutlinedButton(onClick = {
                Hooky.identify("Lou", "lou@example.com")
                Toast.makeText(this@MainActivity, "Signed in as Lou", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in as Lou (identify)")
            }
            // Un compte de l'app : son identifiant et des données libres, vues
            // par l'équipe sur la fiche de la conversation.
            Button(onClick = {
                Hooky.identify(
                    name = "Lou",
                    email = "lou@example.com",
                    userId = "demo-42",
                    attributes = mapOf("plan" to "Pro", "cart" to 49.9, "beta" to true),
                )
                Toast.makeText(this@MainActivity, "Signed in as user demo-42", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Sign in (identify with userId)")
            }
            OutlinedButton(onClick = {
                Hooky.setAttributes(mapOf("cart" to (10..200).random(), "last_screen" to "Checkout"))
                Toast.makeText(this@MainActivity, "setAttributes(cart, last_screen)", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Update the cart (setAttributes)")
            }
            OutlinedButton(onClick = {
                Hooky.track("purchase", 49.0, mapOf("product" to "Linen tote"))
                Toast.makeText(this@MainActivity, "track(\"purchase\", 49.0)", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Buy for €49 (track)")
            }
            OutlinedButton(onClick = {
                Hooky.reset()
                Toast.makeText(this@MainActivity, "Conversation forgotten", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out (reset)")
            }
            OutlinedButton(onClick = {
                startActivity(Intent(this@MainActivity, VuesActivity::class.java))
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Same with Views (XML)")
            }
            Spacer(Modifier.height(96.dp))
        }
    }

    @Composable
    private fun Produit(nom: String, prix: String, couleur: Color, modifier: Modifier) {
        Column(modifier) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(couleur),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .7f)))
            }
            Text(nom, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(prix, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

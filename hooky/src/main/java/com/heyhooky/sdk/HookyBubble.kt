package com.heyhooky.sdk

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The ready-made chat button: your site's color, the Hooky icon, a badge
 * with the number of unread replies. Tapping it opens the conversation
 * (or calls [onClick] if you pass one).
 *
 * It only appears once [Hooky.configure] has loaded your site. Place it
 * yourself, usually in a corner:
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     MyScreen()
 *     HookyBubble(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp))
 * }
 * ```
 */
@Composable
public fun HookyBubble(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val moteur = Hooky.moteurObserve ?: return
    val etat by moteur.etat.collectAsState()
    val site = etat.site ?: return
    val nonLus by Hooky.unreadCount.collectAsState()
    val context = LocalContext.current
    HookyTheme(site, moteur.textes) {
        BoutonBulle(nonLus, modifier) { onClick?.invoke() ?: Hooky.open(context) }
    }
}

@Composable
private fun BoutonBulle(nonLus: Int, modifier: Modifier, surClic: () -> Unit) {
    val palette = LocalPalette.current
    val textes = LocalTextes.current
    val site = Hooky.moteurObserve?.etat?.value?.site
    val pression = remember { MutableInteractionSource() }
    val appuye by pression.collectIsPressedAsState()
    val forme = RoundedCornerShape(22.dp)
    val description = textes.t("ouvrir", "site" to site?.nom.orEmpty()) +
        if (nonLus > 0) ", " + textes.t("non_lus", "n" to nonLus) else ""
    // L'ombre dure se tasse quand on appuie, comme le lanceur du web.
    val decalage = if (appuye) 2.dp else 0.dp
    Box(modifier.padding(end = 4.dp, bottom = 4.dp)) {
        Box(
            Modifier
                .offset(decalage, decalage)
                .size(64.dp)
                .ombreDure(if (appuye) 2.dp else 4.dp, 22.dp, palette.ombre)
                .clip(forme)
                .background(palette.accent)
                .border(TRAIT, palette.encre, forme)
                .clickable(interactionSource = pression, indication = null, role = Role.Button) { surClic() }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icones.logo(palette.surAccent, palette.accent),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(34.dp),
            )
        }
        if (nonLus > 0) {
            Text(
                if (nonLus > 99) "99+" else nonLus.toString(),
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp + decalage, y = (-6).dp + decalage)
                    .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5383B))
                    .border(TRAIT, palette.encre, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * [HookyBubble] for apps built with Views: add `<com.heyhooky.sdk.HookyBubbleView>`
 * to your layout (in a corner of a FrameLayout or ConstraintLayout,
 * `wrap_content`). The hosting Activity must be an AppCompatActivity or a
 * ComponentActivity.
 */
public class HookyBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
    @Composable
    override fun Content() {
        HookyBubble()
    }
}

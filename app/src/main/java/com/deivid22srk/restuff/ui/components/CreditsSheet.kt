package com.deivid22srk.restuff.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.deivid22srk.restuff.R
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.config.PortLink

/**
 * Botão de assinatura "Portado por ..." (pill de vidro com coração no accent).
 * Abre o diálogo de créditos com todos os links configurados no
 * [PortBranding.config.links] — YouTube, GitHub, Telegram e projeto base.
 */
@Composable
fun CreditsButton(
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = PortBranding.config
    val haptics = LocalHapticFeedback.current
    val entrance = rememberEntrance(920, reduceMotion)

    Box(
        modifier = modifier
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 24f
            }
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, config.accent.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = config.contentDescCredits,
                tint = config.accent,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = config.portedByLabel,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Diálogo de créditos: header com a marca do port + uma linha por link
 * (ícone tintado, nome, detalhe e ícone "abrir"). Cada linha abre a URL no
 * navegador; sem navegador disponível, mostra um Toast explicativo.
 */
@Composable
fun CreditsDialog(onDismiss: () -> Unit) {
    val config = PortBranding.config
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF12121C),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 22.dp, bottom = 12.dp)
            ) {
                // ---- Header ----------------------------------------------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(R.drawable.ic_logo_mark),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp)
                    )
                    Column {
                        Text(
                            text = config.creditsTitle,
                            color = Color.White,
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = config.creditsSubtitle,
                            color = Color.White.copy(alpha = 0.50f),
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(Modifier.size(18.dp))

                // ---- Links ------------------------------------------------
                config.links.forEach { link ->
                    LinkRow(link) { openInBrowser(context, link.url) }
                    Spacer(Modifier.size(6.dp))
                }

                Spacer(Modifier.size(10.dp))

                Text(
                    text = config.creditsFooter,
                    color = Color.White.copy(alpha = 0.38f),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )

                Spacer(Modifier.size(6.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = config.labelClose,
                        color = config.accent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkRow(link: PortLink, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(link.tint.copy(alpha = 0.16f), RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = link.iconVector(),
                contentDescription = null,
                tint = link.tint,
                modifier = Modifier.size(19.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = link.label,
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = link.description,
                color = Color.White.copy(alpha = 0.50f),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Mapeia o iconKey do config para o vetor correspondente (icons-extended). */
private fun PortLink.iconVector(): ImageVector = when (iconKey) {
    "youtube" -> Icons.Filled.SmartDisplay
    "github" -> Icons.Filled.Code
    "telegram" -> Icons.AutoMirrored.Filled.Send
    else -> Icons.AutoMirrored.Filled.OpenInNew
}

/** Abre a URL no navegador; fallback amigável quando não há navegador. */
internal fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, "Nenhum navegador encontrado", Toast.LENGTH_SHORT).show()
    }
}

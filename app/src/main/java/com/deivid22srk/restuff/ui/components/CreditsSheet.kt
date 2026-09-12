package com.deivid22srk.restuff.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.deivid22srk.restuff.R
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.config.PortLink
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType

/**
 * Assinatura "Portado por ..." no design "Mel & Carvão": convite tipográfico
 * discreto (texto secundário + ponto âmbar), sem pílula de vidro. Abre o
 * diálogo de créditos com os links do [PortBranding.config.links].
 */
@Composable
fun CreditsButton(
    reduceMotion: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = PortBranding.config
    val haptics = LocalHapticFeedback.current
    val entrance = rememberEntrance(460, reduceMotion)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer { alpha = entrance.value }
            .heightIn(min = 48.dp)
            .portClickable(haptic = true) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(4.dp)
                    .background(config.accent, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = config.portedByLabel,
                color = PortPalette.textTertiary,
                style = PortType.mono
            )
        }
    }
}

/**
 * Diálogo de créditos — painel carvão plano: marca + título, LINHA DO MEL,
 * links como linhas hairline (ícone tintado + textos), rodapé quieto.
 * Cada linha abre a URL no navegador; sem navegador, Toast explicativo.
 */
@Composable
fun CreditsDialog(onDismiss: () -> Unit) {
    val config = PortBranding.config
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(PortPalette.radiusLg),
            color = PortPalette.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 22.dp, bottom = 10.dp)
            ) {
                // ---- Header: marca + identidade ---------------------------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_logo_mark),
                        contentDescription = null,
                        modifier = Modifier.size(34.dp)
                    )
                    Column {
                        Text(
                            text = config.creditsTitle,
                            color = PortPalette.textPrimary,
                            style = PortType.titleScreen
                        )
                        Text(
                            text = config.creditsSubtitle,
                            color = PortPalette.textSecondary,
                            style = PortType.rowSub
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                HoneyLine(color = config.accent)
                Spacer(Modifier.height(6.dp))

                // ---- Links: linhas com hairline entre si -------------------
                config.links.forEachIndexed { index, link ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(PortPalette.hairline)
                        )
                    }
                    LinkRow(link) { openInBrowser(context, link.url) }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = config.creditsFooter,
                    color = PortPalette.textTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(
                        text = config.labelClose,
                        color = config.accent,
                        style = PortType.rowLabel
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
            .portClickable { onClick() }
            .padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = link.iconVector(),
            contentDescription = null,
            tint = link.tint,
            modifier = Modifier.size(18.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = link.label,
                color = PortPalette.textPrimary,
                style = PortType.rowLabel
            )
            Text(
                text = link.description,
                color = PortPalette.textSecondary,
                style = PortType.rowSub
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = PortPalette.textTertiary,
            modifier = Modifier.size(14.dp)
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

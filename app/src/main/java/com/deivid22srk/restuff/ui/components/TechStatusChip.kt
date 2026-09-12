package com.deivid22srk.restuff.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.deivid22srk.restuff.config.PortBranding
import com.deivid22srk.restuff.data.GpuDriverManager
import com.deivid22srk.restuff.ui.theme.PortPalette
import com.deivid22srk.restuff.ui.theme.PortType

/**
 * Assinatura técnica da barra inferior — mono minúsculo precedido por um
 * ponto de 4dp no accent: "RESTUFF · VULKAN · ARM64-V8A". Sem chip, sem
 * caixa: metadado, não interface. Recalculado a cada entrada na tela (o
 * driver Turnip pode ter sido ativado nas Configurações).
 */
@Composable
fun TechStatusChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config = PortBranding.config

    var info by remember { mutableStateOf(computeInfo(context)) }
    LaunchedEffect(Unit) {
        info = computeInfo(context)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(4.dp)
                .background(config.accent, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = info,
            color = PortPalette.textTertiary,
            style = PortType.mono
        )
    }
}

private fun computeInfo(context: android.content.Context): String {
    val abi = Build.SUPPORTED_ABIS.firstOrNull()?.uppercase() ?: ""
    // Driver Vulkan customizado (AdrenoTools/Turnip) ativo e carregado
    // com sucesso no último boot? (vulkan_instance.cpp escreve o desfecho
    // em files/drivers/last_boot.txt.)
    val hasCustomDriver = try {
        GpuDriverManager.activeId(context) != null &&
            GpuDriverManager.lastBootOutcome(context)?.status != "custom_failed"
    } catch (_: Exception) {
        false
    }
    val renderer = if (hasCustomDriver) "VULKAN · TURNIP" else "VULKAN"
    return listOf("RESTUFF", renderer, abi).joinToString("  ·  ")
}

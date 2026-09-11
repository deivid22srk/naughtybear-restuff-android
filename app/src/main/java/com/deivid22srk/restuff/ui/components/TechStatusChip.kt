package com.deivid22srk.restuff.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deivid22srk.restuff.data.GpuDriverManager

/**
 * Chip técnico do canto inferior esquerdo: a PILHA REAL do port — motor
 * RESTUFF (librestuff.so, rexglue-SDK), renderer Vulkan fixo do
 * recompilador, driver customizado Turnip quando importado via
 * AdrenoTools (tela de Configurações) e o ABI do aparelho.
 *
 * Nota: o jogo não usa OpenGL ES — a versão de GLES do aparelho é
 * irrelevante aqui e por isso não é exibida. O selo TURNIP reflete o
 * driver CONFIGURADO (active.txt); se o último boot falhou em carregá-lo
 * (custom_failed), o selo é omitido — o desfecho real fica no Diagnóstico.
 */
@Composable
fun TechStatusChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val info = remember {
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.uppercase() ?: ""
        // Driver Vulkan customizado (padrão AdrenoTools/Turnip) configurado
        // e carregado com sucesso no último boot? (null = nunca bootou —
        // mostra o selo se há driver ativo; vulkan_instance.cpp escreve o
        // desfecho em files/drivers/last_boot.txt.)
        val hasCustomDriver = try {
            GpuDriverManager.activeId(context) != null &&
                GpuDriverManager.lastBootOutcome(context)?.status != "custom_failed"
        } catch (_: Exception) {
            false
        }
        val renderer = if (hasCustomDriver) "VULKAN · TURNIP" else "VULKAN"
        listOf("RESTUFF", renderer, abi).joinToString("  ·  ")
    }

    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = info,
            color = Color.White.copy(alpha = 0.50f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp
        )
    }
}

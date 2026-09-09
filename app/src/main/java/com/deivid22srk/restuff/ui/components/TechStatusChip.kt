package com.deivid22srk.restuff.ui.components

import android.app.ActivityManager
import android.content.Context
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
import com.deivid22srk.restuff.config.PortBranding

/**
 * Chip técnico do canto inferior esquerdo: motor (Compose), versão de GLES
 * e ABI do aparelho — o "canto de credibilidade" típico de menus AAA.
 * Informação real do dispositivo, calculada uma única vez.
 */
@Composable
fun TechStatusChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val info = remember {
        val gl = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.deviceConfigurationInfo?.glEsVersion ?: "GLES"
        } catch (_: Exception) {
            "GLES"
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull()?.uppercase() ?: ""
        listOf("COMPOSE", "GLES $gl", abi).joinToString("  ·  ")
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

package com.deivid22srk.restuff

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.deivid22srk.restuff.ui.DataSelectionRoute
import com.deivid22srk.restuff.ui.settings.SettingsRoute
import com.deivid22srk.restuff.ui.theme.PortScreenTheme
import com.deivid22srk.restuff.viewmodel.DataSelectionViewModel
import com.deivid22srk.restuff.settings.PortSettingsViewModel

/**
 * Activity única do template. Todo o conteúdo é Jetpack Compose edge-to-edge
 * em MODO IMERSIVO (fullscreen de verdade): barras de sistema ocultas e
 * re-ocultadas sempre que o foco volta (diálogos, swipe temporário etc.).
 * O usuário as revela deslizando da borda — comportamento padrão de ports.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Mantém a tela ligada durante o jogo (comportamento esperado de um port).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            PortScreenTheme {
                // ViewModels no escopo da Activity: compartilhados entre as
                // telas de Seleção e Configurações via navegação.
                val selectionViewModel: DataSelectionViewModel = viewModel()
                val settingsViewModel: PortSettingsViewModel = viewModel()

                // ==========================================================
                // PONTO DE INTEGRAÇÃO DO MOTOR DO PORT
                //
                // Com os dados prontos (ISO extraído ou pasta validada), o
                // toque em "Iniciar Jogo" chega aqui e lança a GameActivity:
                // SDL3 (org.libsdl.app.SDLActivity) cria a janela/superfície
                // e roda o SDL_main do motor nativo (librestuff.so).
                // ==========================================================
                selectionViewModel.onLaunchGame = { _, _ ->
                    startActivity(
                        android.content.Intent(this, com.deivid22srk.restuff.game.GameActivity::class.java)
                    )
                }

                AppNavigation(selectionViewModel, settingsViewModel)
            }
        }

        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private object Routes {
    const val SELECTION = "selection"
    const val SETTINGS = "settings"
}

@Composable
private fun AppNavigation(
    selectionViewModel: DataSelectionViewModel,
    settingsViewModel: PortSettingsViewModel,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.SELECTION,
        enterTransition = { fadeIn(tween(320)) },
        exitTransition = { fadeOut(tween(220)) },
        popEnterTransition = { fadeIn(tween(320)) },
        popExitTransition = { fadeOut(tween(220)) }
    ) {
        composable(Routes.SELECTION) {
            DataSelectionRoute(
                viewModel = selectionViewModel,
                settingsViewModel = settingsViewModel,
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsRoute(
                settingsViewModel = settingsViewModel,
                selectionViewModel = selectionViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

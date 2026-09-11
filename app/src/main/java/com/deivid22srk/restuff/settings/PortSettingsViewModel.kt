package com.deivid22srk.restuff.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel da tela de Configurações. Expõe [PortSettings] imutável e um
 * único ponto de mutação ([update]) que já persiste em disco — a UI apenas
 * emite transformações puras:
 *
 *     onFpsLimitChange = { opt -> vm.update { it.copy(fpsLimit = opt) } }
 *
 * O consumo é feito pelo [com.deivid22srk.restuff.game.GameActivity]:
 * os campos do motor viram cvars do restuff.toml/argv no boot do jogo, e
 * os de controles alimentam o VirtualGamepadView (ver PortSettings.kt).
 */
class PortSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PortSettingsRepository(application)

    private val _settings = MutableStateFlow(repository.load())
    val settings: StateFlow<PortSettings> = _settings.asStateFlow()

    fun update(transform: (PortSettings) -> PortSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        repository.save(next)
    }
}

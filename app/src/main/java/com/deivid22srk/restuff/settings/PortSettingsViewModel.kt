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
 *     onAspectChange = { opt -> vm.update { it.copy(aspectRatio = opt) } }
 *
 * Para plugar no motor do port: observe [settings] e repasse cada campo ao
 * engine (veja o README, seção "Conectando as configurações").
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

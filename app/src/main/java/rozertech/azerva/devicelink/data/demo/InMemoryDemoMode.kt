package rozertech.azerva.devicelink.data.demo

import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rozertech.azerva.devicelink.domain.repository.DemoModeRepository


/**
 * In-memory implementation of [DemoModeRepository].
 *
 * Manages the application's demonstration mode without persistent storage.
 * Demo mode is disabled by default and must be explicitly enabled.
 *
 * Exposes the current state through a StateFlow, allowing observers
 * to react automatically to changes.
 *
 * The state is shared across the application and is reset when
 * the application process is terminated.
 */
@Singleton
class InMemoryDemoMode @Inject constructor() : DemoModeRepository {

    private val state = MutableStateFlow(false)

    /**
     * Observes whether demonstration mode is currently enabled.
     *
     * Emits false by default until demonstration mode is enabled.
     */
    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    /**
     * Enables or disables demonstration mode.
     *
     * Updates the observable state without persisting the value.
     *
     * @param value True to enable demonstration mode, false to disable it.
     */
    override fun setEnabled(value: Boolean) {
        state.value = value
    }
}

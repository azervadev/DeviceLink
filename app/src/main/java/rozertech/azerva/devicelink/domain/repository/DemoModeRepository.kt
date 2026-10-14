package rozertech.azerva.devicelink.domain.repository

import kotlinx.coroutines.flow.StateFlow


/**
 * Defines the contract for managing the application's demonstration mode.
 *
 * Provides observable access to the current demo mode state
 * and allows enabling or disabling it.
 */
interface DemoModeRepository {

    /**
     * Observes whether demonstration mode is currently enabled.
     */
    val enabled: StateFlow<Boolean>

    /**
     * Updates the demonstration mode state.
     *
     * @param value True to enable demonstration mode, false to disable it.
     */
    fun setEnabled(value: Boolean)
}

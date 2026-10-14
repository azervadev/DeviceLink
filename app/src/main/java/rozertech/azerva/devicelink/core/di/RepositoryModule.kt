package rozertech.azerva.devicelink.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import rozertech.azerva.devicelink.data.demo.InMemoryDemoMode
import rozertech.azerva.devicelink.data.local.RoomEventRepository
import rozertech.azerva.devicelink.data.repository_imp.BleRepositoryImp
import rozertech.azerva.devicelink.data.repository_imp.NfcRepositoryImp
import rozertech.azerva.devicelink.data.repository_imp.SensorRepositoryImp
import rozertech.azerva.devicelink.domain.repository.BleRepository
import rozertech.azerva.devicelink.domain.repository.DemoModeRepository
import rozertech.azerva.devicelink.domain.repository.EventRepository
import rozertech.azerva.devicelink.domain.repository.NfcRepository
import rozertech.azerva.devicelink.domain.repository.SensorRepository


/**
 * Hilt dependency injection module for repository implementations.
 *
 * Binds domain repository interfaces to their corresponding
 * data layer implementations.
 *
 * Installed in [SingletonComponent] to make repository bindings
 * available throughout the application.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * Binds the BLE repository implementation to its domain contract.
     *
     * @param impl Android implementation of the BLE repository.
     * @return The [BleRepository] implementation.
     */
    @Binds
    abstract fun bindBle(impl: BleRepositoryImp): BleRepository

    /**
     * Binds the NFC repository implementation to its domain contract.
     *
     * @param impl Android implementation of the NFC repository.
     * @return The [NfcRepository] implementation.
     */
    @Binds
    abstract fun bindNfc(impl: NfcRepositoryImp): NfcRepository

    /**
     * Binds the sensor repository implementation to its domain contract.
     *
     * @param impl Android implementation of the sensor repository.
     * @return The [SensorRepository] implementation.
     */
    @Binds
    abstract fun bindSensors(impl: SensorRepositoryImp): SensorRepository

    /**
     * Binds the Room event repository to its domain contract.
     *
     * @param impl Room-based implementation of the event repository.
     * @return The [EventRepository] implementation.
     */
    @Binds
    abstract fun bindEvents(impl: RoomEventRepository): EventRepository

    /**
     * Binds the in-memory demo mode implementation to its domain contract.
     *
     * @param impl In-memory implementation of the demo mode repository.
     * @return The [DemoModeRepository] implementation.
     */
    @Binds
    abstract fun bindDemo(impl: InMemoryDemoMode): DemoModeRepository
}

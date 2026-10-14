package rozertech.azerva.devicelink.core.di

import android.app.Application
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import rozertech.azerva.devicelink.data.local.EventDao
import rozertech.azerva.devicelink.data.local.EventsDatabase
import javax.inject.Singleton


/**
 * Hilt dependency injection module for application-level dependencies.
 *
 * Provides the application context, Room database instance,
 * and data access objects used for local event persistence.
 *
 * Installed in [SingletonComponent] to make dependencies available
 * throughout the application's lifecycle.
 */
@Module
@InstallIn(SingletonComponent::class)
class AppModule {

    /**
     * Provides the application context.
     *
     * @param application Application instance managed by Android.
     * @return The application-level context.
     */
    @Provides
    fun provideContext(application: Application): Context =
        application.applicationContext

    /**
     * Provides a singleton instance of the Room event database.
     *
     * Creates or opens the local database named "device_link_events.db".
     *
     * @param context Application context used to initialize Room.
     * @return The singleton [EventsDatabase] instance.
     */
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context
    ): EventsDatabase =
        Room.databaseBuilder(
            context,
            EventsDatabase::class.java,
            "device_link_events.db"
        ).build()

    /**
     * Provides the data access object for managing stored events.
     *
     * @param database Room database containing event records.
     * @return The [EventDao] instance associated with the database.
     */
    @Provides
    fun dao(database: EventsDatabase): EventDao =
        database.eventDao()
}

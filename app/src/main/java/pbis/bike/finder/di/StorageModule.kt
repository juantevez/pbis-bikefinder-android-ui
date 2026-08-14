package pbis.bike.finder.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import pbis.bike.finder.data.local.DeviceLocationProvider
import pbis.bike.finder.data.local.SystemLocationProvider
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.local.TokenStore
import pbis.bike.finder.data.repository.PhotoRepository
import pbis.bike.finder.data.repository.PhotoUploader
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: TokenStore): TokenStorage

    @Binds
    @Singleton
    abstract fun bindPhotoUploader(impl: PhotoRepository): PhotoUploader

    @Binds
    @Singleton
    abstract fun bindDeviceLocationProvider(impl: SystemLocationProvider): DeviceLocationProvider
}

package pbis.bike.finder.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.local.TokenStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindTokenStorage(impl: TokenStore): TokenStorage
}

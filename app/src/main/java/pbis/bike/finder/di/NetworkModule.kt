package pbis.bike.finder.di

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import pbis.bike.finder.BuildConfig
import pbis.bike.finder.data.local.ApiEnvironment
import pbis.bike.finder.data.remote.AuthInterceptor
import pbis.bike.finder.data.remote.TokenAuthenticator
import pbis.bike.finder.data.remote.api.AuthApi
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.api.DashboardApi
import pbis.bike.finder.data.remote.api.GeoApi
import pbis.bike.finder.data.remote.api.NominatimApi
import pbis.bike.finder.data.remote.api.NotificationApi
import pbis.bike.finder.data.remote.api.PaymentApi
import pbis.bike.finder.data.remote.api.PublicTipApi
import pbis.bike.finder.data.remote.api.TheftReportApi
import pbis.bike.finder.data.remote.dto.BikeFinderJson
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** El cliente que **no** renueva tokens: lo usa el propio refresh. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = BikeFinderJson

    // TokenStore y ApiEnvironment se construyen solos: tienen @Inject constructor
    // con @ApplicationContext. Tenerlos además acá como @Provides duplicaba el
    // camino en el grafo sin agregar nada.

    /**
     * Reescribe el host de cada request al que diga [ApiEnvironment].
     *
     * Retrofit exige una baseUrl fija al construirse, pero acá la base es
     * configurable en runtime (el backend de desarrollo cambia de IP con el
     * DHCP). Este interceptor reemplaza esquema, host y puerto de la URL ya
     * armada, dejando el path intacto — así la baseUrl de Retrofit es sólo un
     * placeholder y no hay que reconstruir el cliente cuando cambia el entorno.
     */
    @Provides
    @Singleton
    fun provideBaseUrlInterceptor(environment: ApiEnvironment): Interceptor = Interceptor { chain ->
        val base = runBlocking { environment.apiBase() }.toHttpUrl()
        val url = chain.request().url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
        chain.proceed(chain.request().newBuilder().url(url).build())
    }

    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        // BODY sólo en debug: el cuerpo de /auth/login lleva la contraseña en
        // claro y el de /auth/refresh, el refresh token. Loguear eso en un
        // release es publicar credenciales en logcat, que cualquier app con
        // permiso de lectura de logs puede leer en dispositivos viejos.
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
        else HttpLoggingInterceptor.Level.NONE
    }

    /**
     * Cliente sin [TokenAuthenticator]: si el refresh diera 401 y el
     * authenticator intentara refrescar, sería recursión infinita.
     */
    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshClient(
        baseUrlInterceptor: Interceptor,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrlInterceptor)
        .addInterceptor(logging)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        baseUrlInterceptor: Interceptor,
        authInterceptor: AuthInterceptor,
        tokenAuthenticator: TokenAuthenticator,
        logging: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(baseUrlInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(logging)
        .authenticator(tokenAuthenticator)
        .connectTimeout(15, TimeUnit.SECONDS)
        // El gateway corta a los 10s y devuelve 503 cuando se le vence la espera.
        // 30s deja margen para que sea el gateway el que decida, no el cliente:
        // un timeout local no trae el campo `retry` que dice si se puede
        // reintentar sin duplicar la operación.
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun retrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        // Placeholder: el host real lo pone provideBaseUrlInterceptor.
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    /**
     * `AuthApi` **con** Bearer y renovación: el que usa toda la app.
     *
     * `/auth/me` y `/auth/logout` necesitan token. Las rutas públicas de esta
     * misma interfaz (login, registro, reset) lo evitan con `X-Skip-Auth`, así
     * que una sola instancia autenticada sirve para todo el servicio.
     */
    @Provides
    @Singleton
    fun provideAuthApi(client: OkHttpClient, json: Json): AuthApi =
        retrofit(client, json).create(AuthApi::class.java)

    /**
     * `AuthApi` **sin** authenticator, exclusivo para renovar la sesión.
     *
     * Va sobre [provideRefreshClient] porque un 401 en `/auth/refresh` no puede
     * disparar otro refresh: sería recursión sin fondo.
     *
     * Está calificado y separado del anterior porque tenerlos unificados fue un
     * bug real: con un único binding sobre el cliente sin interceptor, `/auth/me`
     * salía **sin** `Authorization` y devolvía 401 en silencio. El nombre del
     * usuario simplemente no aparecía, y ningún test lo vio porque los de
     * integración arman su propio cliente autenticado.
     */
    @Provides
    @Singleton
    @RefreshClient
    fun provideRefreshAuthApi(@RefreshClient client: OkHttpClient, json: Json): AuthApi =
        retrofit(client, json).create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideBicycleApi(client: OkHttpClient, json: Json): BicycleApi =
        retrofit(client, json).create(BicycleApi::class.java)

    @Provides
    @Singleton
    fun provideTheftReportApi(client: OkHttpClient, json: Json): TheftReportApi =
        retrofit(client, json).create(TheftReportApi::class.java)

    @Provides
    @Singleton
    fun providePublicTipApi(client: OkHttpClient, json: Json): PublicTipApi =
        retrofit(client, json).create(PublicTipApi::class.java)

    @Provides
    @Singleton
    fun provideGeoApi(client: OkHttpClient, json: Json): GeoApi =
        retrofit(client, json).create(GeoApi::class.java)

    @Provides
    @Singleton
    fun providePaymentApi(client: OkHttpClient, json: Json): PaymentApi =
        retrofit(client, json).create(PaymentApi::class.java)

    @Provides
    @Singleton
    fun provideNotificationApi(client: OkHttpClient, json: Json): NotificationApi =
        retrofit(client, json).create(NotificationApi::class.java)

    @Provides
    @Singleton
    fun provideDashboardApi(client: OkHttpClient, json: Json): DashboardApi =
        retrofit(client, json).create(DashboardApi::class.java)

    /**
     * El ImageLoader de Coil, sobre el OkHttp autenticado de la app.
     *
     * Las fotos de las bicis no son recursos públicos: salen por
     * `/api/files/download`, que pide el Bearer como cualquier otro endpoint. Al
     * compartir el cliente, cada imagen viaja con el token, se le reescribe el
     * host igual que al resto —así una foto sigue funcionando cuando el DHCP
     * cambia la IP del backend— y un 401 dispara el mismo refresh que las demás
     * llamadas, en vez de romper la galería hasta que el usuario vuelva a entrar.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        client: OkHttpClient,
    ): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .build()

    /**
     * Nominatim, el único servicio que no es nuestro.
     *
     * Tiene cliente propio y no reusa el de la app por tres razones, todas
     * necesarias: no debe pasar por el interceptor que reescribe el host hacia
     * el gateway; **no puede llevar el `Authorization`**, porque mandarle
     * nuestro token a un tercero es filtrar una credencial; y la política de uso
     * de OSM exige un `User-Agent` que identifique a la aplicación —el genérico
     * de OkHttp es motivo de bloqueo por IP—.
     */
    @Provides
    @Singleton
    fun provideNominatimApi(json: Json): NominatimApi {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(
                            "User-Agent",
                            "BikeFinder-Android/${BuildConfig.VERSION_NAME} " +
                                "(https://github.com/juantevez)",
                        )
                        .build(),
                )
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://nominatim.openstreetmap.org/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(NominatimApi::class.java)
    }
}

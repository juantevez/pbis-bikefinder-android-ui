package pbis.bike.finder.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import pbis.bike.finder.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

private val Context.environmentStore by preferencesDataStore(name = "api_environment")

private val API_BASE_KEY = stringPreferencesKey("api_base")
private val AUTH_BASE_KEY = stringPreferencesKey("auth_base")

/**
 * A qué backend apunta la app.
 *
 * El front web deriva la base de la API del host desde el que se navega
 * (`config.js`), lo cual acá no existe: una app instalada no tiene "el host
 * desde el que se navega". Queda un default por buildType más un override
 * persistido — el equivalente del `localStorage.setItem('apiBase', …)` que el
 * front expone para la consola.
 *
 * El override no es una comodidad de debug: en desarrollo el backend corre en la
 * máquina del dev y el DHCP le cambia la IP, así que hardcodear no sobrevive a un
 * reinicio del router. Con un teléfono físico, además, `10.0.2.2` no resuelve
 * nada — ese alias sólo existe dentro del emulador.
 *
 * `authSsoBase` apunta a auth-service **directo** (:8084), no al gateway. No es
 * una inconsistencia: el `redirect_uri` que auth-service registró en Google es
 * `http://localhost:8084/login/oauth2/code/google` y el gateway no rutea
 * `/login/oauth2/…`, así que el callback del proveedor no encontraría camino de
 * vuelta. En Android este flujo hay que rehacerlo igual (client ID de tipo
 * Android + redirect a esquema propio), así que la separación queda modelada
 * pero todavía no resuelta.
 */
@Singleton
class ApiEnvironment @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun apiBase(): String = read(API_BASE_KEY) ?: BuildConfig.DEFAULT_API_BASE

    suspend fun authSsoBase(): String = read(AUTH_BASE_KEY) ?: BuildConfig.DEFAULT_AUTH_SSO_BASE

    suspend fun overrideApiBase(base: String?) = write(API_BASE_KEY, base)

    suspend fun overrideAuthSsoBase(base: String?) = write(AUTH_BASE_KEY, base)

    private suspend fun read(key: Preferences.Key<String>): String? =
        context.environmentStore.data.first()[key]?.takeIf { it.isNotBlank() }

    private suspend fun write(key: Preferences.Key<String>, value: String?) {
        context.environmentStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value.trimEnd('/')
        }
    }
}

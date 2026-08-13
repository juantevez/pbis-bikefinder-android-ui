package pbis.bike.finder.data.local

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenStore by preferencesDataStore(name = "session_tokens")

/**
 * Par de tokens de la sesión.
 *
 * En web viven en `localStorage`, legible por cualquier script de la página. Acá
 * quedan en el almacenamiento privado de la app, que en un dispositivo sin root
 * no es accesible desde otras apps.
 *
 * **Sin cifrado en reposo, y es una decisión pendiente.** `EncryptedSharedPreferences`
 * (androidx.security-crypto) está deprecado y sin reemplazo directo; cifrar a mano
 * con la Keystore es trabajo real y no gratis. Con root o con un backup extraíble,
 * estos tokens se leen. Antes de producción hay que decidir: cifrar con Keystore,
 * o acortar la vida del refresh token para que el robo valga poco. Mientras tanto
 * queda excluido del backup en `backup_rules.xml`, que es lo mínimo.
 *
 * El perfil del usuario **no** va acá. En web se cachea en `sessionStorage`
 * justamente para que muera con la pestaña: con `localStorage` habría que
 * acordarse de borrarlo en los cuatro lugares que cierran sesión, y olvidarse de
 * uno significa mostrarle el nombre del usuario anterior al siguiente que entre.
 * En Android el equivalente de "muere con la pestaña" es memoria del proceso, no
 * disco.
 */
@Singleton
class TokenStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TokenStorage {
    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")

    override val hasSession: Flow<Boolean> =
        context.tokenStore.data.map { it[refreshKey].isNullOrBlank().not() }

    override suspend fun accessToken(): String? = read(accessKey)

    override suspend fun refreshToken(): String? = read(refreshKey)

    override suspend fun save(accessToken: String, refreshToken: String) {
        context.tokenStore.edit {
            it[accessKey] = accessToken
            it[refreshKey] = refreshToken
        }
    }

    /** Única puerta de salida de la sesión. Ver `SessionManager.closeSession`. */
    override suspend fun clear() {
        context.tokenStore.edit { it.clear() }
    }

    private suspend fun read(key: Preferences.Key<String>): String? =
        context.tokenStore.data.first()[key]?.takeIf { it.isNotBlank() }
}

package pbis.bike.finder.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.uiPreferences by preferencesDataStore(name = "ui_preferences")

private val THEME_KEY = stringPreferencesKey("theme")

/**
 * Qué tema pidió el usuario.
 *
 * Son tres opciones y no dos a propósito. Con sólo claro/oscuro habría que elegir
 * un default arbitrario, y ese default le rompe el modo oscuro automático a quien
 * ya lo tiene configurado en el teléfono: la app dejaría de acompañar al sistema
 * sin que nadie lo haya pedido. `SYSTEM` es el estado inicial y también un
 * destino al que se puede volver.
 */
enum class ThemePreference(val label: String) {
    SYSTEM("Automático (sistema)"),
    LIGHT("Claro"),
    DARK("Oscuro"),
}

/**
 * La preferencia de tema, persistida.
 *
 * DataStore propio y no el de la sesión: el tema tiene que sobrevivir al logout.
 * `TokenStore.clear()` vacía su almacenamiento entero al cerrar sesión, así que
 * guardar el tema ahí significaría que la app vuelve al tema del sistema cada vez
 * que alguien sale.
 */
@Singleton
class ThemeStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val preference: Flow<ThemePreference> = context.uiPreferences.data.map { prefs ->
        // Un valor ilegible —una versión anterior, un enum renombrado— vuelve al
        // default en vez de tirar la app abajo: es una preferencia cosmética.
        prefs[THEME_KEY]?.let { saved ->
            runCatching { ThemePreference.valueOf(saved) }.getOrNull()
        } ?: ThemePreference.SYSTEM
    }

    suspend fun save(preference: ThemePreference) {
        context.uiPreferences.edit { it[THEME_KEY] = preference.name }
    }
}

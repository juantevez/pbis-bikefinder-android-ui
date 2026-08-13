package pbis.bike.finder.data.local

import kotlinx.coroutines.flow.Flow

/**
 * Acceso a los tokens de la sesión.
 *
 * Existe como interfaz para que la lógica de renovación —la parte donde se
 * decide si una sesión se pierde o se conserva— se pueda testear sin un
 * `Context` ni DataStore de por medio. Esa decisión es la que el front web tuvo
 * mal durante siete archivos: merece tests que corran en la JVM y no en un
 * emulador.
 */
interface TokenStorage {
    val hasSession: Flow<Boolean>
    suspend fun accessToken(): String?
    suspend fun refreshToken(): String?
    suspend fun save(accessToken: String, refreshToken: String)
    suspend fun clear()
}

package pbis.bike.finder.data.remote

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pbis.bike.finder.data.local.TokenStorage
import pbis.bike.finder.data.remote.api.AuthApi
import pbis.bike.finder.di.RefreshClient
import pbis.bike.finder.data.remote.dto.RefreshTokenRequestDto
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Resultado de intentar renovar la sesión.
 *
 * La distinción entre [Expired] y [NoNetwork] es el punto de todo el módulo, y
 * es la lección más cara que dejó el front web: durante un tiempo el manejo del
 * refresh estuvo copiado en siete archivos, con el mismo bug en los siete —el
 * catch no distinguía "el refresh token no sirve" de "no hubo respuesta", y
 * cualquier bache de conexión terminaba en un redirect al login.
 *
 * Perder la sesión es una decisión que sólo se puede tomar cuando el servidor
 * efectivamente **contestó** que el token no vale. En un teléfono esto importa
 * más que en el navegador: la conectividad se corta sola todo el tiempo —túnel,
 * ascensor, cambio de celda— y desloguear por eso hace la app inusable.
 */
enum class RefreshOutcome {
    /** Hay un accessToken nuevo y usable. */
    Ok,

    /** El servidor dijo que el token no sirve (4xx). Se cierra la sesión. */
    Expired,

    /** No hubo respuesta útil (excepción, 5xx, 429): **no se toca la sesión**. */
    NoNetwork,
}

/** Lo que la UI necesita saber sin preguntar. */
sealed interface SessionEvent {
    /** El servidor rechazó el token: hay que mandar al login. */
    data object Expired : SessionEvent
}

/**
 * Renovación de tokens y cierre de sesión.
 *
 * `AuthApi` llega como [Provider] a propósito: el cliente HTTP que lo construye
 * depende de este mismo objeto (el [TokenAuthenticator] lo usa), y sin la
 * indirección Dagger encuentra un ciclo en tiempo de compilación.
 */
@Singleton
class SessionManager @Inject constructor(
    private val tokenStore: TokenStorage,
    @RefreshClient private val refreshApi: Provider<AuthApi>,
) {
    private val refreshMutex = Mutex()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    /**
     * Renueva el par de tokens.
     *
     * El [Mutex] evita la estampida: si cinco requests reciben 401 al mismo
     * tiempo —cosa normal cuando una pantalla dispara varias llamadas en
     * paralelo— sólo la primera renueva y las demás esperan. Sin esto se mandan
     * cinco refresh, cuatro de los cuales usan un refresh token que la primera
     * acaba de rotar, y el backend los rechaza: la sesión se cae justo por
     * intentar salvarla.
     *
     * Serializar no alcanza, y por eso está [tokenRechazado]: las que esperaban
     * entraban igual a renovar de nuevo, una por una. Funcionaba —cada una leía
     * el refresh token ya rotado— pero emitía N pares de tokens para un problema
     * que se resolvió con el primero, y cada rotación de más es una ventana en la
     * que otro cliente logueado con la misma cuenta se queda con un token que
     * acaba de ser consumido. Con el access token que se comió el 401 se
     * distingue "hay que renovar" de "ya renovó otro": si el guardado es
     * distinto, la sesión ya está sana y no hay nada que pedir.
     *
     * @param tokenRechazado el access token con el que salió la request que
     *   recibió el 401. `null` fuerza la renovación, que es el comportamiento
     *   correcto para un llamador que no sabe con qué token salió.
     */
    suspend fun refresh(tokenRechazado: String? = null): RefreshOutcome = refreshMutex.withLock {
        if (tokenRechazado != null && tokenStore.accessToken() != tokenRechazado) {
            return@withLock RefreshOutcome.Ok
        }

        val refreshToken = tokenStore.refreshToken() ?: return@withLock RefreshOutcome.Expired

        val response = try {
            refreshApi.get().refresh(RefreshTokenRequestDto(refreshToken))
        } catch (e: Exception) {
            // No hubo respuesta: wifi caído, backend apagado, portal cautivo.
            // La sesión sigue siendo tan válida como hace un segundo.
            return@withLock RefreshOutcome.NoNetwork
        }

        // Un 5xx o un 429 NO dicen nada sobre el token: dicen que el backend no
        // pudo contestar. El gateway devuelve 503 cuando se le vence la espera o
        // el circuito está abierto —con auth-service lento, eso llega a los 10
        // segundos— y tratarlo como "sesión vencida" desloguea por una caída del
        // servidor, que es exactamente el error que esta clase existe para no
        // cometer.
        if (response.code() >= 500 || response.code() == 429) return@withLock RefreshOutcome.NoNetwork

        // Acá sí: 400/401/403 son el servidor diciendo que el token no sirve.
        if (!response.isSuccessful) return@withLock RefreshOutcome.Expired

        val body = response.body() ?: return@withLock RefreshOutcome.Expired

        // Los tokens son nullable en el DTO desde que /auth/login puede devolver
        // un challenge de segundo factor en vez de una sesión. Un refresh 200 sin
        // tokens no tiene ese significado —no existe un "refresh con 2FA"— así
        // que es una respuesta que no se entiende: se trata como sesión vencida,
        // que es el camino seguro. Guardar un null dejaría al usuario adentro con
        // un `Authorization: Bearer null`.
        val accessToken = body.accessToken ?: return@withLock RefreshOutcome.Expired
        val refreshedToken = body.refreshToken ?: return@withLock RefreshOutcome.Expired

        tokenStore.save(accessToken, refreshedToken)
        RefreshOutcome.Ok
    }

    /**
     * Borra los tokens y avisa a la UI. Única puerta de salida de la sesión.
     *
     * No navega: emite un evento. Quién decide a dónde ir es la capa de
     * navegación, no la de red — en web esto era un `window.location.href` desde
     * el módulo de sesión, que funciona pero ata la red a la navegación.
     */
    suspend fun closeSession() {
        tokenStore.clear()
        _events.emit(SessionEvent.Expired)
    }
}

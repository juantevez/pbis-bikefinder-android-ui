package pbis.bike.finder.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import pbis.bike.finder.data.local.TokenStorage
import javax.inject.Inject
import javax.inject.Singleton

/** Marca las requests que **no** llevan token: login, registro, refresh, geografía, pistas públicas. */
annotation class NoAuth

private const val HEADER_AUTHORIZATION = "Authorization"

/** Header interno para saltear el Bearer sin depender de la URL. */
const val HEADER_SKIP_AUTH = "X-Skip-Auth"

/**
 * Agrega el `Authorization: Bearer …`.
 *
 * **No manda `X-User-Id`.** El front web lo hace en las pantallas de pistas, pero
 * es residuo: el gateway borra incondicionalmente `X-User-Id`, `X-User-Email` y
 * `X-User-Role` que venga del cliente, y los reinyecta desde el JWT
 * (`JwtAuthenticationFilter.HEADERS_QUE_NO_ESCRIBE_EL_CLIENTE`). Ese borrado
 * existe porque `/api/v1/tips/…` y `/api/v1/conversations/…` son rutas
 * públicas: sin él, cualquiera escribiría como otro usuario.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStorage,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (request.header(HEADER_SKIP_AUTH) != null) {
            return chain.proceed(request.newBuilder().removeHeader(HEADER_SKIP_AUTH).build())
        }

        val token = runBlocking { tokenStore.accessToken() }
            ?: return chain.proceed(request)

        return chain.proceed(
            request.newBuilder().header(HEADER_AUTHORIZATION, "Bearer $token").build()
        )
    }
}

/**
 * Renueva y reintenta **una sola vez** ante un 401.
 *
 * OkHttp llama al [Authenticator] cuando una respuesta vuelve 401, y reintenta
 * con lo que devuelva. Es mejor lugar que un interceptor: no hay que leer y
 * reconstruir el body de la request original.
 *
 * El reintento es uno solo. Si el token recién emitido también da 401, el
 * problema no es el token, y volver a pedir otro sería un bucle — por eso se
 * cuenta la cadena de `priorResponse`.
 *
 * Ante un 401 que no se puede resolver:
 *   - token rechazado por el servidor → cierra la sesión
 *   - sin respuesta al renovar        → devuelve null, y el 401 original llega
 *                                       al llamador **sin** desloguear
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val sessionManager: SessionManager,
    private val tokenStore: TokenStorage,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header(HEADER_SKIP_AUTH) != null) return null
        if (responseCount(response) > 1) return null

        return when (runBlocking { sessionManager.refresh() }) {
            RefreshOutcome.Ok -> {
                val token = runBlocking { tokenStore.accessToken() } ?: return null
                response.request.newBuilder()
                    .header(HEADER_AUTHORIZATION, "Bearer $token")
                    .build()
            }

            RefreshOutcome.Expired -> {
                runBlocking { sessionManager.closeSession() }
                null
            }

            // Se devuelve el 401 original para que el llamador lo maneje como
            // cualquier otro fallo. La sesión queda como estaba.
            RefreshOutcome.NoNetwork -> null
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

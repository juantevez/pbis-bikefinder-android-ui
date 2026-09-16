package pbis.bike.finder.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** El mismo nombre que usa el gateway (`RequestIdFilter.HEADER`): va y vuelve por acá. */
const val HEADER_REQUEST_ID = "X-Request-Id"

/**
 * Le pone un id a cada request antes de que salga del teléfono.
 *
 * **Para qué.** Un fallo que ve un usuario en la app no tiene hoy forma de
 * conectarse con sus líneas en Loki: hay que buscar por hora aproximada y
 * userId. Con este header, el gateway lo acepta tal cual (`RequestIdFilter`
 * respeta cualquier id de la forma `[A-Za-z0-9._-]{1,64}`, y un UUID lo es),
 * lo pone en su MDC, lo reenvía a auth-service y al resto, y lo devuelve en la
 * respuesta. Un solo filtro en Grafana muestra el paso completo.
 *
 * **Se genera acá y no se espera el del gateway** por una razón: si la request
 * nunca vuelve —timeout, wifi que se cae a mitad de camino— no hay respuesta de
 * dónde leerlo, pero si llegó al gateway sus logs lo tienen. Con el id generado
 * en el cliente, el caso "se colgó y no sé si llegó" se puede buscar igual.
 *
 * Si la request ya trae uno, se respeta. Pasa en el reintento del
 * [TokenAuthenticator], que reconstruye la request a partir de la que dio 401,
 * header incluido: el 401 y el reintento comparten id, y eso es justo lo que
 * uno quiere ver junto en Loki.
 */
@Singleton
class RequestIdInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER_REQUEST_ID) != null) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header(HEADER_REQUEST_ID, UUID.randomUUID().toString())
                .build(),
        )
    }
}

package pbis.bike.finder.data.remote

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.dto.ApiErrorDto
import pbis.bike.finder.data.remote.dto.RetryAdvice
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * Resultado de una llamada a la API.
 *
 * La distinción entre [NoNetwork] y [HttpError] es la misma que hace
 * [SessionManager] con el refresh, y por la misma razón: "no hubo respuesta" y
 * "el servidor dijo que no" son dos cosas distintas, y tratarlas igual lleva a
 * mostrarle al usuario un error equivocado o, peor, a tomar decisiones
 * destructivas sobre datos que quizás sí se guardaron.
 */
sealed interface ApiResult<out T> {

    data class Success<T>(val data: T) : ApiResult<T>

    /** No hubo respuesta: wifi caído, backend apagado, timeout local. */
    data object NoNetwork : ApiResult<Nothing>

    /** El servidor contestó con un status de error. */
    data class HttpError(
        val code: Int,
        val body: ApiErrorDto?,
    ) : ApiResult<Nothing> {

        /**
         * Discriminador legible por máquina. Sólo lo mandan los errores de
         * auth-service (`INVALID_CREDENTIALS`, etc.); en el resto es null y hay
         * que mirar el status.
         */
        val errorCode: String? get() = body?.code

        /**
         * Qué se puede hacer ante un 503. Sólo tiene sentido en 503; en
         * cualquier otro status es ruido.
         */
        val retry: RetryAdvice get() = RetryAdvice.from(body?.retry)

        /** Texto para mostrar, o null si el backend no mandó nada legible. */
        val userMessage: String? get() = body?.userMessage
    }

    /**
     * Se recibió una respuesta pero no se pudo interpretar.
     *
     * Es su propio caso y no un [HttpError] porque significa algo distinto: el
     * contrato cambió, o el DTO del cliente está mal. Confundirlo con un error
     * del servidor esconde justamente el problema que hay que arreglar.
     */
    data class Malformed(val cause: Throwable) : ApiResult<Nothing>
}

/**
 * Transforma el dato de un [ApiResult.Success] y deja pasar los tres casos de
 * error tal cual.
 *
 * Sin esto, cambiar el tipo que devuelve un repositorio obliga a reescribir el
 * `when` de cuatro ramas en cada llamador, y tres de esas ramas son siempre la
 * misma línea.
 */
inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.NoNetwork -> ApiResult.NoNetwork
    is ApiResult.HttpError -> this
    is ApiResult.Malformed -> this
}

/**
 * Envuelve una llamada de Retrofit traduciendo excepciones a [ApiResult].
 *
 * Retrofit lanza [HttpException] ante un status de error y [IOException] cuando
 * no hubo respuesta. Cualquier otra excepción se trata como respuesta
 * ininterpretable: en la práctica es un fallo de deserialización, o sea un
 * desajuste de contrato.
 */
suspend fun <T> apiCall(json: Json, block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: HttpException) {
    ApiResult.HttpError(e.code(), parseError(json, e))
} catch (e: IOException) {
    ApiResult.NoNetwork
} catch (e: Exception) {
    ApiResult.Malformed(e)
}

/**
 * Lee el cuerpo de error.
 *
 * Hay **dos formas** de error conviviendo y ninguna las cubre a las dos: los
 * errores de auth-service traen `{ status, code, message, … }` sin campo
 * `error`, y los de theft-report y el gateway traen `{ error, message,
 * timestamp }` sin `code`. [ApiErrorDto] declara la unión de ambas.
 *
 * Nunca lanza: un error de red que además falla al parsearse sigue siendo un
 * error de red, y perderlo detrás de una excepción del parser no ayuda a nadie.
 */
private fun parseError(json: Json, e: HttpException): ApiErrorDto? = try {
    e.response()?.errorBody()?.string()?.takeIf { it.isNotBlank() }?.let {
        json.decodeFromString<ApiErrorDto>(it)
    }
} catch (_: Exception) {
    null
}

/**
 * Hace que un `Response<Unit>` sin cuerpo falle como el resto de las llamadas.
 *
 * Es la trampa de Retrofit que hay que conocer: cuando el tipo de retorno es
 * `Response<T>` en vez de `T`, un status de error **no** lanza [HttpException]
 * —vuelve como una respuesta más, con `isSuccessful` en false—. Sin esto, los
 * endpoints que no devuelven cuerpo (marcar leída, convertir, actualizar
 * componentes) leerían un 403 o un 409 como éxito, y la pantalla le diría al
 * usuario que la operación se hizo.
 *
 * Se relanza para que [apiCall] lo traduzca con el mismo parseo de cuerpo de
 * error que todo lo demás.
 */
fun Response<Unit>.orThrow() {
    if (!isSuccessful) throw HttpException(this)
}

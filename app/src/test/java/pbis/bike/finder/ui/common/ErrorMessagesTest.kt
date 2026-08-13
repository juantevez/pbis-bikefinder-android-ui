package pbis.bike.finder.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.ApiErrorDto

class ErrorMessagesTest {

    private fun httpError(code: Int, message: String? = null, retry: String? = null) =
        ApiResult.HttpError(code, ApiErrorDto(message = message, retry = retry))

    @Test
    fun `el mensaje del backend le gana al texto generico`() {
        // Un 429 trae hasta los segundos que hay que esperar; reemplazarlo por
        // "algo salió mal" tira información que el usuario necesita.
        val result = httpError(429, "Demasiados intentos. Esperá 45 segundos.")

        assertEquals(
            "Demasiados intentos.",
            result.toUserMessage("Error genérico"),
        )
    }

    @Test
    fun `sin cuerpo del backend se usa el texto de la pantalla`() {
        val result = ApiResult.HttpError(500, null)

        assertEquals("No se pudo cargar.", result.toUserMessage("No se pudo cargar."))
    }

    @Test
    fun `sin red se dice sin red, no error del servidor`() {
        // Decirle "algo salió mal" a alguien que está en un ascensor lo manda a
        // reintentar contra la pared.
        assertEquals(
            "No se pudo conectar con el servidor. Revisá tu conexión.",
            ApiResult.NoNetwork.toUserMessage("otra cosa"),
        )
    }

    @Test
    fun `un 503 safe invita a reintentar`() {
        val result = httpError(503, "El servicio no responde.", retry = "safe")

        assertEquals(
            "El servicio no responde. Reintentá en unos minutos.",
            result.toUserMessage("fallback"),
        )
        assertTrue(result.isSafeToRetry())
    }

    @Test
    fun `un 503 unsafe NO invita a reintentar sino a verificar`() {
        // Es el caso que ya pasó en producción: el gateway sirvió un 503 mientras
        // auth-service terminaba el registro. La operación pudo completarse.
        val result = httpError(503, "No se pudo completar la operación.", retry = "unsafe")

        assertEquals(
            "No se pudo completar la operación. " +
                "Puede haberse completado igual: verificá antes de repetirla.",
            result.toUserMessage("fallback"),
        )
        assertFalse(result.isSafeToRetry())
    }

    @Test
    fun `un 503 sin campo retry se trata como unsafe`() {
        val result = httpError(503, "Se cortó la espera.")

        assertTrue(result.toUserMessage("fallback").contains("verificá"))
        assertFalse(result.isSafeToRetry())
    }

    @Test
    fun `un 503 con idempotency key no encima un reintenta generico`() {
        // El mensaje del gateway ya explica que hay que reusar la clave; agregarle
        // "reintentá" invita a mandar una operación nueva, que es cobrar dos veces.
        val message = "Reintentá con la misma X-Idempotency-Key."
        val result = httpError(503, message, retry = "same-idempotency-key")

        assertEquals(message, result.toUserMessage("fallback"))
    }

    @Test
    fun `no se duplica la invitacion a reintentar si el backend ya la trae`() {
        val result = httpError(503, "Servicio ocupado, reintentá luego.", retry = "safe")

        assertEquals("Servicio ocupado, reintentá luego.", result.toUserMessage("fallback"))
    }

    @Test
    fun `un 4xx no ofrece reintentar`() {
        // Mandar lo mismo de nuevo da el mismo 401: el botón sería una mentira.
        assertFalse(httpError(401, "Credenciales incorrectas").isSafeToRetry())
        assertFalse(httpError(404, "No existe").isSafeToRetry())
    }

    @Test
    fun `una respuesta ininterpretable se distingue de un error del servidor`() {
        // Es un desajuste de contrato: no hay nada que el usuario pueda hacer, y
        // por eso tampoco se le ofrece reintentar.
        val result = ApiResult.Malformed(IllegalStateException("campo faltante"))

        assertEquals(
            "La respuesta del servidor no se pudo interpretar.",
            result.toUserMessage("fallback"),
        )
        assertFalse(result.isSafeToRetry())
    }
}

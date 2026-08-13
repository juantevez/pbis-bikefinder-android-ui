package pbis.bike.finder.ui.common

import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.RetryAdvice

/**
 * Convierte un fallo de la API en algo que se le puede decir a una persona.
 *
 * Las reglas son las del front web, y ninguna es cosmética:
 *
 *  - **El mensaje del backend gana.** Viene en español y suele ser más preciso
 *    que cualquier texto genérico: un 429 trae hasta los segundos que hay que
 *    esperar. Sólo se muestra la primera frase; el resto es detalle técnico.
 *  - **Un 503 no dice que la operación no ocurrió.** Dice que se cortó la
 *    espera. Si el timeout venció con la request en vuelo, el servicio de atrás
 *    pudo completarla y lo que se perdió fue la respuesta — ya pasó con un
 *    registro de auth-service. Por eso el texto cambia según `retry`.
 *  - **Sin red no es lo mismo que error del servidor**, y decirle al usuario
 *    "algo salió mal" cuando lo que pasa es que está en un ascensor lo manda a
 *    reintentar contra la pared.
 */
fun ApiResult<*>.toUserMessage(fallback: String): String = when (this) {
    is ApiResult.Success -> ""

    is ApiResult.NoNetwork ->
        "No se pudo conectar con el servidor. Revisá tu conexión."

    is ApiResult.Malformed ->
        // Es un desajuste de contrato, no un problema del usuario. No hay nada
        // que pueda hacer al respecto, así que el texto no lo invita a reintentar.
        "La respuesta del servidor no se pudo interpretar."

    is ApiResult.HttpError -> httpMessage(fallback)
}

private fun ApiResult.HttpError.httpMessage(fallback: String): String {
    val base = userMessage ?: fallback
    if (code != 503) return base

    return when (retry) {
        RetryAdvice.Safe ->
            if (base.contains("reintent", ignoreCase = true)) base
            else "$base Reintentá en unos minutos."

        // El mensaje del gateway ya explica que hay que reusar la clave; no se le
        // encima un "reintentá" genérico que invite a mandar una operación nueva.
        RetryAdvice.SameIdempotencyKey -> base

        RetryAdvice.Unsafe ->
            if (base.contains("verific", ignoreCase = true)) base
            else "$base Puede haberse completado igual: verificá antes de repetirla."
    }
}

/**
 * `true` si el fallo permite reintentar sin riesgo de duplicar algo.
 *
 * Sirve para decidir si se muestra un botón de "Reintentar". En un GET siempre
 * sí; en un 503 de una operación que escribe, no — ofrecer el botón ahí es
 * invitar a un cobro doble.
 */
fun ApiResult<*>.isSafeToRetry(): Boolean = when (this) {
    is ApiResult.NoNetwork -> true
    is ApiResult.Malformed -> false
    is ApiResult.Success -> false
    is ApiResult.HttpError ->
        if (code == 503) retry != RetryAdvice.Unsafe else code !in 400..499
}

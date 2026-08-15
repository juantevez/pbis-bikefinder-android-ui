package pbis.bike.finder.data.remote.dto

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decisiones de mapeo que valen para todos los DTOs de este paquete.
 *
 * Los modelos están derivados de los `record` de Java del backend
 * (auth-service y los 12 servicios de bike-stolen-finder). Cada archivo cita
 * la clase de la que sale, para que se pueda auditar el contrato sin adivinar.
 *
 * ## Por qué no están generados con openapi-generator
 *
 * springdoc está en casi todos los servicios y sirve el spec en `/api-docs`
 * (auth-service, sin override, lo deja en `/v3/api-docs`). Pero levantar el
 * stack para bajarlos arrastra Kafka, Elasticsearch y Selenium, y el spec sale
 * igual de esos mismos records. Si en algún momento se corre el stack completo,
 * conviene bajar los `.json` y enchufar el generador al build: estos modelos
 * quedarían como referencia de las decisiones, no como fuente.
 */

/**
 * Configuración de Json compartida.
 *
 * `ignoreUnknownKeys` no es tolerancia por comodidad: varias respuestas traen
 * campos que la app todavía no usa (`detailedSpecs`, `sightingsCount`,
 * `speedConfigs`) y el backend puede sumar más sin coordinar un release del
 * cliente. Sin esto, agregar un campo en un DTO de Java rompe la app en runtime.
 *
 * `explicitNulls = false` evita mandar `"campo": null` en cada request. Importa
 * en PUT /auth/me, donde el backend interpreta null como "no tocar": omitir y
 * mandar null significan lo mismo ahí, pero omitir deja el payload legible.
 * Cuidado en PUT de preferencias de notificación, que SÍ reemplaza el estado
 * completo — ahí hay que mandar todos los campos explícitamente.
 */
val BikeFinderJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Zona horaria en la que el backend escribe los `LocalDateTime`.
 *
 * El contrato mezcla tres tipos de fecha:
 *
 *  - `Instant`       → bicicletas, denuncias, pagos. Trae offset, no ambiguo.
 *  - `LocalDateTime` → pistas, mensajes y fotos. **Sin zona.**
 *  - `LocalDate`     → fecha de robo y de avistamiento. Es un día, no un instante.
 *
 * Los `LocalDateTime` se interpretan en esta zona porque todos los servicios
 * corren con `TZ: America/Argentina/Buenos_Aires` en el docker-compose.
 *
 * No usar la zona del dispositivo: en web daba igual porque el navegador estaba
 * en el mismo huso que el servidor, pero un teléfono viaja. Con el celular en
 * España, resolver un `submittedAt` contra la zona local corre la hora de la
 * pista tres horas — y en una denuncia de robo la hora es evidencia.
 *
 * Es una convención de despliegue, no del contrato: si algún día un servicio se
 * despliega en otra zona, esto miente en silencio. La solución de fondo es que
 * el backend emita `Instant` en todos lados.
 */
val BackendTimeZone: TimeZone = TimeZone.of("America/Argentina/Buenos_Aires")

/** Convierte un `LocalDateTime` del backend al instante absoluto que representa. */
fun LocalDateTime.toBackendInstant(): Instant = toInstant(BackendTimeZone)

/**
 * Montos como `String` a propósito.
 *
 * En el backend son `BigDecimal` (`amount` en pagos admite 15 enteros y 2
 * decimales; `rewardAmount`, 10 y 2). Deserializar eso a `Double` introduce
 * error de redondeo binario en valores que son plata real. El DTO conserva el
 * texto exacto del JSON y la conversión es explícita, en el borde del dominio.
 */
fun String.toBigDecimalAmount(): BigDecimal = BigDecimal(this)

/**
 * Cuerpo de error de la API.
 *
 * **No hay una sola forma de error: hay dos**, y esto se descubrió recién al
 * pegarle al backend corriendo. Un 401 de auth-service viene así:
 *
 * ```
 * { "status": 401, "code": "INVALID_CREDENTIALS", "message": "…",
 *   "timestamp": "…", "exception": null, "rootCause": null }
 * ```
 *
 * mientras que theft-report y el gateway responden `{ error, message, timestamp }`.
 * O sea: el campo `error` **no existe** en los errores de auth, y `code` no
 * existe en los del resto. Los dos están declarados y nullables porque el
 * cliente no puede saber de antemano cuál de los dos servicios contestó.
 *
 * `code` es el único discriminador legible por máquina que hay, así que cuando
 * viene conviene ramificar por ahí y no por el texto de `message`, que está en
 * español y puede cambiar sin aviso.
 *
 * Hay una **tercera** forma, y es de un solo servicio: notification-service
 * responde `ProblemDetail` (RFC 7807), donde el motivo viaja en `detail` y no en
 * `message`. El front web necesitó una función aparte para leerlo
 * (`describeNotifError` en `perfil.js`); acá alcanza con declarar el campo, y
 * [userMessage] lo prefiere cuando viene. Sin esto, un 400 explicando que la
 * cuenta no tiene email asociado se mostraría como un texto genérico.
 *
 * Al usuario se le muestra sólo la primera frase de `message`: el resto es
 * detalle técnico (host que no resuelve, timeout, status upstream) que sirve
 * para diagnosticar y no para leer en un toast.
 *
 * `retry` sólo viene en los 503 del gateway y decide si se puede reintentar sin
 * duplicar la operación. Ver [RetryAdvice].
 */
@kotlinx.serialization.Serializable
data class ApiErrorDto(
    /** Presente en theft-report y el gateway; ausente en auth-service. */
    val error: String? = null,
    /** Presente en auth-service; ausente en el resto. Ej: `INVALID_CREDENTIALS`. */
    val code: String? = null,
    val status: Int? = null,
    val message: String? = null,
    /** `ProblemDetail` de notification-service. Ausente en el resto. */
    val detail: String? = null,
    val timestamp: String? = null,
    val retry: String? = null,
    val exception: String? = null,
    val rootCause: String? = null,
) {
    /** Primera oración del motivo, que es lo único que se le muestra al usuario. */
    val userMessage: String?
        get() = (detail ?: message)?.let {
            val cut = it.indexOf(". ")
            // Corta en punto-espacio y no en cualquier punto: si no, un host como
            // "bucket.s3.us-east-2.amazonaws.com" queda partido al medio.
            if (cut == -1) it.trim() else it.substring(0, cut + 1).trim()
        }
}

/**
 * Qué se puede hacer ante un 503 del gateway.
 *
 * Un 503 significa que se cortó la espera, **no** que la operación no haya
 * ocurrido: si el timeout venció con la request en vuelo, el servicio de atrás
 * pudo completarla y lo que se perdió fue la respuesta. Ya pasó en producción
 * con un registro de auth-service.
 *
 * Ausente o desconocido ⇒ [Unsafe], que es el lado seguro del error.
 */
enum class RetryAdvice {
    /** GET y compañía: no hay nada que duplicar. */
    Safe,

    /** El servicio deduplica: reintentar con la MISMA `X-Idempotency-Key`. */
    SameIdempotencyKey,

    /** Pudo haberse completado. No invitar a reintentar; invitar a verificar. */
    Unsafe;

    companion object {
        fun from(raw: String?): RetryAdvice = when (raw) {
            "safe" -> Safe
            "same-idempotency-key" -> SameIdempotencyKey
            else -> Unsafe
        }
    }
}

/**
 * Lee un monto que el backend puede mandar como número **o** como cadena.
 *
 * Los importes viajan en DTOs como `String` a propósito: son `BigDecimal` del
 * lado de Java y pasarlos por `Double` pierde centavos. El problema es que
 * Jackson los serializa como número JSON —`"amount":18.99`, sin comillas— y
 * kotlinx-serialization se niega a leer un número dentro de un `String`.
 *
 * El síntoma no fue un campo vacío sino un pago **cobrado** que la app reportó
 * como "no se pudo interpretar la respuesta": el 201 traía `COMPLETED` y la
 * deserialización moría antes de que nadie lo mirara.
 *
 * Se toma el texto crudo del primitivo en vez de pasar por `Double`, así
 * `18.99` sigue siendo `"18.99"` y no `18.989999999999998`.
 */
object LenientAmountSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientAmount", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val json = decoder as? JsonDecoder ?: return decoder.decodeString()
        return json.decodeJsonElement().jsonPrimitive.content
    }

    /** De vuelta siempre como cadena: es lo que el backend acepta en los request. */
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

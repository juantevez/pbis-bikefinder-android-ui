package pbis.bike.finder.data.remote.dto

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * `LocalDateTime` que acepta las **dos** formas que emite el backend.
 *
 * No es tolerancia gratuita: los servicios no serializan igual. `media-service`
 * devuelve
 *
 * ```
 * "uploadedAt": [2026, 8, 13, 16, 37, 19, 199364000]
 * ```
 *
 * que es el default de Jackson para `java.time` cuando no se registra el
 * `JavaTimeModule` (o se deja `WRITE_DATES_AS_TIMESTAMPS` en true), mientras que
 * el resto de los servicios mandan texto ISO-8601. Verificado contra el backend
 * corriendo: la subida de una foto funcionaba y lo que fallaba era leer la
 * respuesta.
 *
 * **La solución de fondo es del lado del servidor**: que todos los servicios
 * serialicen igual, idealmente `Instant` en ISO. Mientras tanto, un cliente que
 * sólo entienda una de las dos formas se rompe con la mitad de la API, y hacer
 * que el usuario vea "no se pudo interpretar la respuesta" porque dos servicios
 * eligieron distinto no le sirve a nadie.
 *
 * El array trae `[año, mes, día, hora, minuto, segundo, nanos]`; los últimos tres
 * pueden faltar cuando son cero, así que se leen con default.
 */
object FlexibleLocalDateTimeSerializer : KSerializer<LocalDateTime> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleLocalDateTime", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): LocalDateTime {
        val input = decoder as? JsonDecoder
            ?: return LocalDateTime.parse(decoder.decodeString())

        return when (val element = input.decodeJsonElement()) {
            is JsonArray -> fromArray(element)
            is JsonPrimitive -> LocalDateTime.parse(element.content)
            else -> error("Formato de fecha no reconocido: $element")
        }
    }

    private fun fromArray(array: JsonArray): LocalDateTime {
        fun at(index: Int): Int =
            array.getOrNull(index)?.jsonPrimitive?.int ?: 0

        return LocalDateTime(
            year = at(0),
            monthNumber = at(1),
            dayOfMonth = at(2),
            hour = at(3),
            minute = at(4),
            second = at(5),
            nanosecond = at(6),
        )
    }

    /**
     * Emite siempre ISO-8601. El cliente no tiene por qué propagar la
     * inconsistencia del servidor hacia arriba.
     */
    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.toString())
    }
}

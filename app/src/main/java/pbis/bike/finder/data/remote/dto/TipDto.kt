package pbis.bike.finder.data.remote.dto

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// theft-report — infrastructure.adapter.in.rest.dto.TipDto
//                infrastructure.adapter.in.rest.dto.TipMessagingDto
//
// Dos lados con contratos distintos:
//   dueño       → /api/v1/theft-reports/{reportId}/tips/…   (Bearer)
//   informante  → /api/v1/tips/{token}, /api/v1/conversations/{token}  (público)
//
// Los endpoints por token son deep links naturales: en Android van como
// App Links, no como pantallas a las que se llega navegando.
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
enum class TipStatus { NEW, READ, REPLIED, CONVERTED_TO_SIGHTING }

/** `POST /api/v1/tips/{token}` — lo manda un tercero, sin login. */
@Serializable
data class SubmitTipRequestDto(
    val sightingDate: LocalDate,
    /** max 50 */
    val sightingTimeApprox: String? = null,
    val sightingLocation: TheftLocationDto? = null,
    /**
     * Obligatoria, max 5000. La columna es TEXT: este límite es el único techo
     * del campo, y el texto termina renderizado en los PDF y en los mails al
     * dueño.
     */
    val description: String,
    /**
     * max 255, **sin validación de formato a propósito**: puede ser un teléfono,
     * un usuario de Instagram o lo que el informante quiera dejar.
     */
    val informantContact: String? = null,
    val wantsReply: Boolean = false,
) {
    companion object {
        const val MAX_TIME_APPROX = 50
        const val MAX_DESCRIPTION = 5000
        const val MAX_INFORMANT_CONTACT = 255
    }
}

/**
 * Respuesta al enviar una pista.
 *
 * **`conversationToken` es el campo que el front web tira.** El informante manda
 * la pista y nunca recibe el link para seguir la conversación, aunque el backend
 * se lo dio en esta misma respuesta: por eso `wantsReply` queda a medio
 * funcionar. En Android hay que guardarlo y ofrecerle el hilo.
 */
@Serializable
data class TipSubmittedDto(
    val tipId: String,
    val conversationToken: String? = null,
    val message: String? = null,
)

@Serializable
data class TipListResponseDto(
    val tips: List<TipDto> = emptyList(),
    val total: Int = 0,
    val unread: Int = 0,
)

@Serializable
data class TipDto(
    val id: String,
    val theftReportId: String? = null,
    val sightingDate: LocalDate? = null,
    val sightingTimeApprox: String? = null,
    val locationDescription: String? = null,
    val description: String? = null,
    val canReply: Boolean = false,
    val status: TipStatus? = null,
    /** Sin zona: interpretar con [BackendTimeZone]. */
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val submittedAt: LocalDateTime? = null,
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val readAt: LocalDateTime? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /**
     * Contacto crudo del informante.
     *
     * **Sólo se completa cuando la denuncia ofrece recompensa** (regla de
     * `OwnerTipController`); null en cualquier otro caso. Es un dato externo sin
     * verificar: el propio DTO del backend pide mostrarlo con disclaimer.
     */
    val informantContact: String? = null,
)

@Serializable
data class TipStatsDto(
    val total: Int = 0,
    val unread: Int = 0,
    val replied: Int = 0,
    val converted: Int = 0,
)

// ── Mensajería dueño ↔ informante ────────────────────────────────────────────

/**
 * `POST …/tips/{tipId}/messages` (dueño) y `POST /api/v1/conversations/{token}`
 * (informante). Mismo cuerpo en los dos extremos.
 */
@Serializable
data class SendMessageRequestDto(
    /** Obligatorio, max 5000. */
    val message: String,
) {
    companion object {
        const val MAX_MESSAGE = 5000
    }
}

/**
 * El hilo completo. Los dos extremos ven los mismos mensajes; el backend media
 * el intercambio para que no se expongan datos de contacto directos.
 */
@Serializable
data class ConversationDto(
    val tipId: String? = null,
    val messages: List<MessageDto> = emptyList(),
    val totalMessages: Int = 0,
    val unreadCount: Int = 0,
    val canReply: Boolean = false,
)

@Serializable
data class MessageDto(
    val id: String,
    val tipId: String? = null,
    /** Quién escribió: dueño o informante. */
    val senderType: String? = null,
    val message: String? = null,
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val sentAt: LocalDateTime? = null,
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val readAt: LocalDateTime? = null,
    val isRead: Boolean = false,
)

@Serializable
data class MessageSentDto(
    val messageId: String,
    val message: String? = null,
    @Serializable(with = FlexibleLocalDateTimeSerializer::class)
    val sentAt: LocalDateTime? = null,
)

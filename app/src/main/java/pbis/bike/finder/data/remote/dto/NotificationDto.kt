package pbis.bike.finder.data.remote.dto

import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// notification-service — infrastructure.adapter.in.rest.dto.NotificationPreferencesDto
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `PUT /api/v1/notification-preferences`.
 *
 * **Reemplaza el estado completo, no parchea.** Hay que mandar todos los campos
 * en cada guardado, incluidos los que no se tocaron — si no, se apagan canales
 * sin querer. Es el caso donde `explicitNulls = false` de [BikeFinderJson] puede
 * morder: los booleanos no son nullables justamente por eso.
 *
 * El email **no se manda**: el backend lo toma de `X-User-Email`, que inyecta el
 * gateway desde el token, "para que nadie pueda derivar sus avisos a una casilla
 * ajena".
 */
@Serializable
data class NotificationPreferencesRequestDto(
    /** Requiere que el token traiga el mail de la cuenta. */
    val emailEnabled: Boolean,
    /** Con código de país, ej. "+5491122334455". */
    val whatsappNumber: String? = null,
    /** Requiere [whatsappNumber] no vacío. */
    val whatsappEnabled: Boolean,
    val telegramChatId: String? = null,
    /** Requiere [telegramChatId] no vacío. */
    val telegramEnabled: Boolean,
    /** Locale de los mails; por defecto es-AR. */
    val locale: String? = null,
)

@Serializable
data class NotificationPreferencesDto(
    val userId: String? = null,
    val email: String? = null,
    val emailEnabled: Boolean = false,
    val whatsappNumber: String? = null,
    val whatsappEnabled: Boolean = false,
    val telegramChatId: String? = null,
    val telegramEnabled: Boolean = false,
    val locale: String? = null,
    /**
     * false ⇒ los avisos se registran como SKIPPED y **no se envía nada**.
     *
     * El front web no lo muestra: el usuario puede quedarse sin ninguna
     * notificación de una pista sobre su bici robada y no enterarse. Vale la
     * pena advertirlo en la app.
     */
    val anyChannelEnabled: Boolean = false,
)

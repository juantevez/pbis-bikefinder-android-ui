package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.NotificationApi
import pbis.bike.finder.data.remote.dto.NotificationPreferencesDto
import pbis.bike.finder.data.remote.dto.NotificationPreferencesRequestDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Por qué canales quiere el usuario que le avisemos.
 *
 * El PUT **reemplaza el estado completo**, no parchea. Por eso [setEmailEnabled]
 * pide el estado vigente en vez de mandar sólo el booleano: un PUT con el resto
 * en su default le apagaría WhatsApp y Telegram a quien los tenga cargados.
 * Modelarlo como "cambiá este canal" y no como "guardá esto" es lo que evita que
 * el que llama se olvide de arrastrar los otros campos.
 *
 * La dirección de email no viaja nunca: el backend la toma del claim del token,
 * que el gateway inyecta como `X-User-Email`. Así nadie puede derivar los avisos
 * de su denuncia a una casilla ajena.
 */
@Singleton
class NotificationRepository @Inject constructor(
    private val api: NotificationApi,
    private val json: Json,
) {
    suspend fun preferences(): ApiResult<NotificationPreferencesDto> =
        apiCall(json) { api.preferences() }

    suspend fun setEmailEnabled(
        current: NotificationPreferencesDto,
        enabled: Boolean,
    ): ApiResult<NotificationPreferencesDto> = apiCall(json) {
        api.updatePreferences(
            NotificationPreferencesRequestDto(
                emailEnabled = enabled,
                whatsappNumber = current.whatsappNumber,
                whatsappEnabled = current.whatsappEnabled,
                telegramChatId = current.telegramChatId,
                telegramEnabled = current.telegramEnabled,
                locale = current.locale,
            ),
        )
    }
}

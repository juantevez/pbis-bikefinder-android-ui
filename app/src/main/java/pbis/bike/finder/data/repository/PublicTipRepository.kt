package pbis.bike.finder.data.repository

import kotlinx.serialization.json.Json
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.PublicTipApi
import pbis.bike.finder.data.remote.dto.ConversationDto
import pbis.bike.finder.data.remote.dto.MessageSentDto
import pbis.bike.finder.data.remote.dto.SendMessageRequestDto
import pbis.bike.finder.data.remote.dto.SubmitTipRequestDto
import pbis.bike.finder.data.remote.dto.TipFormInfoDto
import pbis.bike.finder.data.remote.dto.TipSubmittedDto
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El lado del informante: **sin sesión**, autenticado por el token de la URL.
 *
 * Va aparte de [TheftRepository] justamente por eso. Los métodos de aquél
 * viajan con el `Bearer` del dueño y no sirven acá: quien manda una pista es un
 * tercero que escaneó un cartel y que lo más probable es que no tenga cuenta.
 * Tenerlos separados hace que no se pueda llamar a uno creyendo que es el otro.
 */
@Singleton
class PublicTipRepository @Inject constructor(
    private val api: PublicTipApi,
    private val json: Json,
) {
    /**
     * Qué bicicleta es. Es lo que le confirma al informante que el link sirve y
     * que está por reportar la bici que efectivamente vio.
     *
     * Un **404** significa token inválido, vencido o desactivado, y hay que
     * decirlo como tal: quien reporta no tiene forma de saber que el link
     * caducó, y un error genérico lo deja pensando que la app está rota.
     */
    suspend fun tipFormInfo(token: String): ApiResult<TipFormInfoDto> =
        apiCall(json) { api.tipFormInfo(token) }

    /**
     * Manda la pista.
     *
     * Responde **429** con un `message` propio cuando pega el rate limit. Ese
     * caso no es un fallo a reintentar: hay que mostrar el mensaje del servidor
     * y dejar el botón quieto.
     */
    suspend fun submitTip(
        token: String,
        body: SubmitTipRequestDto,
    ): ApiResult<TipSubmittedDto> = apiCall(json) { api.submitTip(token, body) }

    /**
     * El hilo con el dueño, visto por quien mandó la pista.
     *
     * El token no es el `tipId`: es un valor firmado que sólo el servidor puede
     * producir. Antes sí era el `tipId`, y como el dueño lo recibe al listar sus
     * pistas, podía leer el hilo y responder haciéndose pasar por el informante.
     */
    suspend fun conversation(token: String): ApiResult<ConversationDto> =
        apiCall(json) { api.conversation(token) }

    /**
     * Responde en el hilo.
     *
     * El backend media el intercambio: ninguno de los dos extremos recibe los
     * datos de contacto del otro. Y como el resto del lado informante, tiene
     * rate limit propio — un 429 trae su mensaje y no conviene reintentarlo solo.
     */
    suspend fun sendMessage(
        token: String,
        body: SendMessageRequestDto,
    ): ApiResult<MessageSentDto> = apiCall(json) { api.sendMessage(token, body) }
}

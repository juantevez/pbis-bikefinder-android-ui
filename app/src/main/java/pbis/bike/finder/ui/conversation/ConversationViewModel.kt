package pbis.bike.finder.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.MessageDto
import pbis.bike.finder.data.remote.dto.SendMessageRequestDto
import pbis.bike.finder.data.repository.PublicTipRepository
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

private const val TOKEN_INVALIDO =
    "Este link ya no sirve. Puede haber vencido, o la denuncia pudo cerrarse porque " +
        "la bicicleta apareció."

data class ConversationUiState(
    val loading: Boolean = true,
    val messages: List<MessageDto> = emptyList(),
    val loadError: String? = null,
    val canRetryLoad: Boolean = false,

    /**
     * Si el hilo admite respuesta.
     *
     * Lo decide el backend, no la pantalla. Del lado del informante esto puede
     * cerrarse porque la denuncia se cerró —apareció la bici— y ahí el hilo pasa
     * a ser sólo lectura.
     */
    val canReply: Boolean = false,

    val draft: String = "",
    val sending: Boolean = false,
    val sendError: String? = null,
) {
    val draftTooLong: Boolean
        get() = draft.length > SendMessageRequestDto.MAX_MESSAGE

    val canSend: Boolean
        get() = canReply && !sending && draft.isNotBlank() && !draftTooLong
}

/**
 * El hilo con el dueño, del lado de quien mandó la pista.
 *
 * Como el formulario de pistas, **anda sin sesión**: la credencial es el
 * `conversationToken` que el backend emitió al recibir la pista, firmado de modo
 * que sólo el servidor puede producirlo. Eso importa: antes el token era el
 * propio `tipId`, y el dueño —que lo recibe al listar sus pistas— podía entrar
 * al hilo haciéndose pasar por el informante.
 *
 * Quien abre esto no elige con quién habla ni ve nada de la otra persona. El
 * canal lo abre el dueño y el backend media el intercambio: ninguno de los dos
 * extremos recibe los datos de contacto del otro.
 */
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val repository: PublicTipRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private var token: String? = null

    fun start(token: String) {
        if (this.token != null) return
        this.token = token
        load()
    }

    fun load() {
        val token = token ?: return
        _state.update { it.copy(loading = true, loadError = null) }

        viewModelScope.launch {
            when (val result = repository.conversation(token)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        messages = result.data.messages,
                        canReply = result.data.canReply,
                        loadError = null,
                    )
                }

                else -> _state.update {
                    // Un 404 es el token, y no hay nada que reintentar: no va a
                    // cambiar por esperar.
                    val tokenMuerto = result is ApiResult.HttpError && result.code == 404

                    it.copy(
                        loading = false,
                        loadError = if (tokenMuerto) TOKEN_INVALIDO
                        else result.toUserMessage("No se pudo cargar la conversación."),
                        canRetryLoad = !tokenMuerto && result.isSafeToRetry(),
                    )
                }
            }
        }
    }

    fun onDraftChange(value: String) = _state.update { it.copy(draft = value) }

    /**
     * Manda un mensaje y recarga el hilo.
     *
     * El borrador se limpia recién cuando el backend confirmó. Limpiarlo al
     * tocar "Enviar" es lo cómodo de escribir y lo peor de usar: si la request
     * falla, lo que la persona escribió desaparece y hay que reescribirlo de
     * memoria.
     */
    fun send() {
        val token = token ?: return
        val current = _state.value
        if (!current.canSend) return

        _state.update { it.copy(sending = true, sendError = null) }

        viewModelScope.launch {
            val body = SendMessageRequestDto(message = current.draft.trim())

            when (val result = repository.sendMessage(token, body)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(sending = false, draft = "") }
                    load()
                }

                else -> _state.update {
                    it.copy(
                        sending = false,
                        sendError = result.toUserMessage(
                            "No se pudo enviar el mensaje. Probá de nuevo en un momento.",
                        ),
                    )
                }
            }
        }
    }

    fun dismissSendError() = _state.update { it.copy(sendError = null) }
}

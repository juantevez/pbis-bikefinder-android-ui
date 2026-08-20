package pbis.bike.finder.ui.tips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.MessageDto
import pbis.bike.finder.data.remote.dto.SendMessageRequestDto
import pbis.bike.finder.data.remote.dto.TipDto
import pbis.bike.finder.data.remote.dto.TipStatus
import pbis.bike.finder.data.repository.TheftRepository
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

/** Cuál de las dos acciones irreversibles está esperando confirmación. */
enum class TipConfirmation { MARK_READ, CONVERT }

data class TipDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val canRetry: Boolean = false,

    val tip: TipDto? = null,
    val messages: List<MessageDto> = emptyList(),
    /** Lo dice la conversación, no la pista: es el permiso de responder ahora. */
    val canReply: Boolean = false,

    val reply: String = "",
    val sending: Boolean = false,
    val replyError: String? = null,

    val confirming: TipConfirmation? = null,
    val actionInProgress: Boolean = false,
    val actionError: String? = null,
    /** Aviso de acción cumplida; la pantalla lo muestra y lo limpia. */
    val actionDone: String? = null,
) {
    /** Ya leída no se vuelve a marcar, y una convertida ya pasó por leída. */
    val canMarkRead: Boolean
        get() = tip?.status != null &&
            tip.status != TipStatus.READ &&
            tip.status != TipStatus.CONVERTED_TO_SIGHTING

    val canConvert: Boolean
        get() = tip?.status != null && tip.status != TipStatus.CONVERTED_TO_SIGHTING

    val hasCoordinates: Boolean get() = tip?.latitude != null && tip.longitude != null

    val replyTooLong: Boolean get() = reply.length > SendMessageRequestDto.MAX_MESSAGE
}

/**
 * Una pista y su conversación — `tip-detail.html`.
 *
 * Tiene las dos acciones del dueño sobre una pista: marcarla leída y convertirla
 * en avistamiento oficial. La segunda es **irreversible** —no hay endpoint que
 * la deshaga— y por eso las dos se confirman antes, igual que en la web.
 */
@HiltViewModel
class TipDetailViewModel @Inject constructor(
    private val theftRepository: TheftRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TipDetailUiState())
    val state: StateFlow<TipDetailUiState> = _state.asStateFlow()

    private var reportId: String? = null
    private var tipId: String? = null

    fun start(reportId: String, tipId: String) {
        if (this.tipId != null) return
        this.reportId = reportId
        this.tipId = tipId
        load()
    }

    fun load() {
        val report = reportId ?: return
        val tip = tipId ?: return
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val tipCall = async { theftRepository.tip(report, tip) }
            val conversationCall = async { theftRepository.tipConversation(report, tip) }

            val tipResult = tipCall.await()
            val conversationResult = conversationCall.await()

            when (tipResult) {
                is ApiResult.Success -> _state.update {
                    val conversation = (conversationResult as? ApiResult.Success)?.data
                    it.copy(
                        loading = false,
                        error = null,
                        tip = tipResult.data,
                        messages = conversation?.messages.orEmpty(),
                        // Si la conversación no cargó se cae a lo que dice la
                        // pista: sin esto, un fallo del hilo esconde el cuadro de
                        // respuesta de una pista que sí la admite.
                        canReply = conversation?.canReply ?: tipResult.data.canReply,
                    )
                }

                else -> _state.update {
                    it.copy(
                        loading = false,
                        error = tipResult.toUserMessage("No se pudo cargar la pista."),
                        canRetry = tipResult.isSafeToRetry(),
                    )
                }
            }
        }
    }

    // ── Respuesta al informante ──────────────────────────────────────────────

    fun onReplyChange(value: String) =
        _state.update { it.copy(reply = value, replyError = null) }

    fun sendReply() {
        val report = reportId ?: return
        val tip = tipId ?: return
        val current = _state.value
        val text = current.reply.trim()

        if (current.sending || text.isEmpty()) return
        if (current.replyTooLong) {
            _state.update {
                it.copy(
                    replyError = "El mensaje no puede pasar de " +
                        "${SendMessageRequestDto.MAX_MESSAGE} caracteres.",
                )
            }
            return
        }

        _state.update { it.copy(sending = true, replyError = null) }

        viewModelScope.launch {
            when (val result = theftRepository.replyToTip(report, tip, text)) {
                is ApiResult.Success -> {
                    // El cuadro se vacía recién cuando el mensaje entró: si falla,
                    // el texto sigue ahí y no hay que reescribirlo.
                    _state.update { it.copy(sending = false, reply = "") }
                    refreshConversation()
                }

                else -> _state.update {
                    it.copy(
                        sending = false,
                        replyError = result.toUserMessage("No se pudo enviar la respuesta."),
                    )
                }
            }
        }
    }

    /**
     * Repide el hilo después de escribir.
     *
     * Se relee del servidor en vez de agregar el mensaje a mano: el backend media
     * el intercambio —puede recortar o filtrar contacto— así que lo que quedó
     * guardado no tiene por qué ser exactamente lo que se tipeó.
     */
    private fun refreshConversation() {
        val report = reportId ?: return
        val tip = tipId ?: return

        viewModelScope.launch {
            val result = theftRepository.tipConversation(report, tip)
            if (result is ApiResult.Success) {
                _state.update {
                    it.copy(messages = result.data.messages, canReply = result.data.canReply)
                }
            }
        }
    }

    // ── Acciones sobre la pista ──────────────────────────────────────────────

    fun askConfirmation(action: TipConfirmation) =
        _state.update { it.copy(confirming = action, actionError = null) }

    fun dismissConfirmation() = _state.update { it.copy(confirming = null) }

    fun confirm() {
        val action = _state.value.confirming ?: return
        val report = reportId ?: return
        val tip = tipId ?: return

        _state.update { it.copy(confirming = null, actionInProgress = true, actionError = null) }

        viewModelScope.launch {
            val result = when (action) {
                TipConfirmation.MARK_READ -> theftRepository.markTipRead(report, tip)
                TipConfirmation.CONVERT -> theftRepository.convertTipToSighting(report, tip)
            }

            when (result) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            actionInProgress = false,
                            actionDone = when (action) {
                                TipConfirmation.MARK_READ -> "Marcada como leída."
                                TipConfirmation.CONVERT -> "Convertida en avistamiento."
                            },
                        )
                    }
                    // La web recarga la página entera; acá alcanza con volver a
                    // pedir la pista, que es lo único cuyo estado cambió.
                    reloadTip()
                }

                else -> _state.update {
                    it.copy(
                        actionInProgress = false,
                        actionError = result.toUserMessage("No se pudo completar la acción."),
                    )
                }
            }
        }
    }

    private fun reloadTip() {
        val report = reportId ?: return
        val tip = tipId ?: return

        viewModelScope.launch {
            val result = theftRepository.tip(report, tip)
            if (result is ApiResult.Success) _state.update { it.copy(tip = result.data) }
        }
    }

    fun onActionDoneShown() = _state.update { it.copy(actionDone = null) }
}

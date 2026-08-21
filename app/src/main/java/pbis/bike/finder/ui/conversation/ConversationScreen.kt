package pbis.bike.finder.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pbis.bike.finder.data.remote.dto.MessageDto
import pbis.bike.finder.data.remote.dto.SendMessageRequestDto
import pbis.bike.finder.ui.common.formatDateTime

/**
 * El hilo con el dueño, del lado de quien mandó la pista.
 *
 * Como el formulario, **anda sin sesión**: la credencial es el token del link.
 * Quien entra acá ya mandó una pista y el dueño le contestó; no eligió con quién
 * habla ni ve nada de la otra persona, y el dueño tampoco ve nada de él. El
 * backend media el intercambio.
 *
 * Es deliberadamente pobre comparada con un chat: no hay adjuntos, ni estados de
 * lectura, ni "escribiendo…". El canal existe para que el dueño pueda pedir un
 * detalle más sobre lo que se vio, no para que dos desconocidos negocien.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    token: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(token) { viewModel.start(token) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Tu pista") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Cerrar") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.loadError != null -> LoadFailure(
                    message = state.loadError!!,
                    canRetry = state.canRetryLoad,
                    onRetry = viewModel::load,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> Column(Modifier.fillMaxSize()) {
                    MessageList(state.messages, Modifier.weight(1f))

                    HorizontalDivider()

                    Composer(state = state, viewModel = viewModel)
                }
            }
        }
    }

    state.sendError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSendError,
            title = { Text("No se pudo enviar") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSendError) { Text("Entendido") }
            },
        )
    }
}

@Composable
private fun MessageList(messages: List<MessageDto>, modifier: Modifier = Modifier) {
    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            Text(
                text = "Todavía no hay mensajes.\nSi el dueño te escribe, va a aparecer acá.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { MessageBubble(it) }
    }
}

/**
 * Un mensaje.
 *
 * El dueño va a la izquierda y quien reportó a la derecha, porque esta pantalla
 * la mira el informante: los propios van del lado donde uno escribe. En la
 * pantalla del dueño la asimetría es la inversa, y por eso las dos burbujas no
 * comparten composable.
 */
@Composable
private fun MessageBubble(message: MessageDto) {
    val delInformante = message.senderType.equals("INFORMANT", ignoreCase = true)

    val fondo = if (delInformante) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surface
    val texto = if (delInformante) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (delInformante) Alignment.End else Alignment.Start,
    ) {
        Column(
            modifier = Modifier
                .background(fondo, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                text = if (delInformante) "Vos" else "El dueño",
                style = MaterialTheme.typography.labelSmall,
                color = texto,
            )
            Text(
                text = message.message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = texto,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        message.sentAt?.let {
            Text(
                text = formatDateTime(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

/**
 * El campo de respuesta.
 *
 * Sólo aparece si el backend dice que el hilo admite respuesta. Cuando la
 * denuncia se cierra —apareció la bici— el hilo queda de sólo lectura, y se
 * escribe por qué: un campo que desaparece sin explicación se lee como un error
 * de la app.
 */
@Composable
private fun Composer(state: ConversationUiState, viewModel: ConversationViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding(),
    ) {
        if (!state.canReply) {
            Text(
                text = "Esta conversación está cerrada. Gracias por haber ayudado.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::onDraftChange,
            label = { Text("Responder") },
            placeholder = { Text("Contale lo que recuerdes…") },
            isError = state.draftTooLong,
            supportingText = if (state.draftTooLong) {
                { Text("Máximo ${SendMessageRequestDto.MAX_MESSAGE} caracteres.") }
            } else {
                null
            },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = viewModel::send,
            enabled = state.canSend,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(if (state.sending) "Enviando…" else "Enviar")
        }
    }
}

@Composable
private fun LoadFailure(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        if (canRetry) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Reintentar")
            }
        }
    }
}

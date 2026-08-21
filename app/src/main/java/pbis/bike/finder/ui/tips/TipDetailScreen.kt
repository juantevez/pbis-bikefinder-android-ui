package pbis.bike.finder.ui.tips

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDateTime
import pbis.bike.finder.data.remote.dto.MessageDto
import pbis.bike.finder.data.remote.dto.TipDto
import pbis.bike.finder.ui.common.formatLongDate

/**
 * Una pista y la conversación con quien la mandó — `tip-detail.html`.
 *
 * Es la pantalla donde el dueño decide qué hacer con lo que le reportaron:
 * marcarla leída, convertirla en avistamiento oficial de la denuncia, o
 * escribirle al informante.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipDetailScreen(
    reportId: String,
    tipId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TipDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(reportId, tipId) { viewModel.start(reportId, tipId) }

    LaunchedEffect(state.actionDone) {
        val message = state.actionDone ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.onActionDoneShown()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Pista") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val tip = state.tip
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null -> ErrorState(
                    message = state.error!!,
                    canRetry = state.canRetry,
                    onRetry = viewModel::load,
                    modifier = Modifier.align(Alignment.Center),
                )

                tip != null -> TipContent(
                    tip = tip,
                    state = state,
                    viewModel = viewModel,
                    onOpenMap = {
                        val lat = tip.latitude
                        val lon = tip.longitude
                        if (lat != null && lon != null) {
                            // Se delega en la app de mapas del teléfono en vez de
                            // embeber uno: acá el punto no se edita —sólo se
                            // mira— y desde el mapa del sistema se puede además
                            // trazar el camino hasta ahí, que es lo que alguien
                            // va a querer hacer con un avistamiento.
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                "geo:$lat,$lon?q=$lat,$lon(Avistamiento reportado)".toUri(),
                            )
                            runCatching { context.startActivity(intent) }
                        }
                    },
                )
            }
        }
    }

    state.confirming?.let { action ->
        ConfirmDialog(
            action = action,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirmation,
        )
    }
}

@Composable
private fun TipContent(
    tip: TipDto,
    state: TipDetailUiState,
    viewModel: TipDetailViewModel,
    onOpenMap: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                text = listOfNotNull(
                    tip.sightingDate?.let { formatLongDate(it) },
                    tip.sightingTimeApprox,
                ).joinToString(" · ").ifBlank { "Avistamiento sin fecha" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            tip.submittedAt?.let {
                Text(
                    text = "Recibida el ${formatDateTime(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section("Avistamiento") {
            tip.status?.let {
                LabelledRow("Estado") { StatusBadge(it) }
            }
            Field("Ubicación", tip.locationText() ?: "(no informada)")
            Field("Descripción", tip.description?.takeIf { it.isNotBlank() } ?: "(sin descripción)")

            if (state.hasCoordinates) {
                OutlinedButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) {
                    Text("Ver en el mapa")
                }
            }
        }

        // Sólo llega cuando la denuncia ofrece recompensa, y es dato de un
        // tercero sin verificar: el aviso va pegado al dato, no en una ayuda
        // aparte que nadie lee.
        val contactos = contactosDe(tip)
        if (contactos.isNotEmpty()) {
            Section("Contacto del informante") {
                ContactosDelInformante(contactos)
            }
        }

        Section("Acciones") {
            state.actionError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.askConfirmation(TipConfirmation.MARK_READ) },
                    enabled = state.canMarkRead && !state.actionInProgress,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.canMarkRead) "Marcar leída" else "Ya leída") }

                Button(
                    onClick = { viewModel.askConfirmation(TipConfirmation.CONVERT) },
                    enabled = state.canConvert && !state.actionInProgress,
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.canConvert) "Convertir" else "Ya convertida") }
            }
        }

        Section("Conversación") {
            if (state.messages.isEmpty()) {
                Text(
                    text = "Sin mensajes todavía.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.messages.forEach { MessageBubble(it) }
            }

            if (state.canReply) {
                OutlinedTextField(
                    value = state.reply,
                    onValueChange = viewModel::onReplyChange,
                    label = { Text("Responder") },
                    placeholder = { Text("Escribile al informante…") },
                    isError = state.replyTooLong || state.replyError != null,
                    supportingText = state.replyError?.let { { Text(it) } },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::sendReply,
                    enabled = !state.sending && state.reply.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.sending) "Enviando…" else "Enviar") }
            } else {
                Text(
                    text = "El informante no dejó canal de contacto, así que no se le " +
                        "puede responder desde acá.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(Modifier.padding(bottom = 16.dp))
    }
}

/**
 * El contacto que dejó el informante, con la forma de escribirle que corresponda.
 *
 * El dato es texto libre —el backend no lo valida a propósito— así que se reconoce lo que se
 * puede y lo que no, se muestra tal cual: un usuario de Instagram sigue siendo un contacto
 * util aunque no haya boton que lo abra.
 *
 * **WhatsApp va primero y no hay boton de llamar.** Es una decision de producto: quien reporta
 * una pista sólo quiso ayudar, y una llamada de un desconocido sobre un robo intimida. Un
 * mensaje lo deja leer y contestar cuando quiera — menos invasivo es tambien mas probable que
 * conteste.
 *
 * El aviso de que el dato no está verificado va **pegado a los botones** y no al principio de
 * la pantalla: con acciones de un toque, contactar a alguien que quizas no tiene nada que ver
 * con el robo pasa a ser trivial, y ahi es donde hay que decirlo.
 */
@Composable
private fun ContactosDelInformante(contactos: List<ContactoInformante>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        contactos.forEach { UnContacto(it) }

        Text(
            text = "Lo dejó un tercero y no está verificado. Contactarlo queda bajo tu " +
                "responsabilidad.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnContacto(clasificado: ContactoInformante) {
    val context = LocalContext.current

    fun abrir(uri: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri())) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = clasificado.crudo,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        when (clasificado) {
            is ContactoInformante.Email -> Button(
                onClick = { abrir(mailtoUri(clasificado)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enviar un mail") }

            is ContactoInformante.Telefono -> Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (clasificado.sirveParaWhatsApp) {
                    Button(
                        onClick = { abrir(whatsAppUrl(clasificado)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("WhatsApp") }
                }
                // El SMS queda siempre: funciona con un numero local, que es
                // justamente el caso en que WhatsApp no se puede ofrecer.
                OutlinedButton(
                    onClick = { abrir(smsUri(clasificado)) },
                    modifier = Modifier.weight(1f),
                ) { Text("SMS") }
            }

            is ContactoInformante.Otro -> Text(
                text = "No parece un mail ni un teléfono, así que hay que escribirle por " +
                    "donde corresponda.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBubble(message: MessageDto) {
    val fromOwner = message.senderType == "OWNER"

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (fromOwner) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = if (fromOwner) "Vos" else "Informante",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = message.message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (fromOwner) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message.sentAt?.let {
                Text(
                    text = formatDateTime(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    action: TipConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, text, confirmLabel) = when (action) {
        TipConfirmation.MARK_READ -> Triple(
            "¿Marcar como leída?",
            "Va a dejar de contar en el aviso de pistas sin leer.",
            "Marcar leída",
        )

        TipConfirmation.CONVERT -> Triple(
            "¿Convertir en avistamiento?",
            "La pista pasa a ser un avistamiento oficial de la denuncia. " +
                "Esta acción no se puede deshacer.",
            "Convertir",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LabelledRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/** "20/08 14:30". Es el formato corto del front web para los mensajes. */
private fun formatDateTime(dateTime: LocalDateTime): String {
    fun pad(value: Int) = value.toString().padStart(2, '0')
    return "${pad(dateTime.dayOfMonth)}/${pad(dateTime.monthNumber)} " +
        "${pad(dateTime.hour)}:${pad(dateTime.minute)}"
}

@Composable
private fun ErrorState(
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

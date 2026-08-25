package pbis.bike.finder.ui.tipform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import pbis.bike.finder.data.remote.dto.SubmitTipRequestDto
import pbis.bike.finder.data.remote.dto.TipFormInfoDto
import pbis.bike.finder.ui.common.MapPicker
import pbis.bike.finder.ui.common.formatLongDate

/**
 * "¿Viste esta bicicleta?" — el formulario del informante, equivalente a
 * `tip-form.html`.
 *
 * **La única pantalla de la app que anda sin sesión.** Se llega por un link con
 * token, y quien la abre es alguien que escaneó un cartel: no tiene cuenta, no
 * conoce la app y no le debe nada a nadie. Todo el diseño sale de ahí — un solo
 * campo obligatorio, nada que registrar, y el contacto explicado en vez de
 * pedido a secas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipFormScreen(
    token: String,
    onClose: () -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TipFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(token) { viewModel.start(token) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("¿Viste esta bicicleta?") },
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

                state.submitted -> SuccessState(
                    conversationToken = state.conversationToken,
                    onOpenConversation = onOpenConversation,
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.Center),
                )

                state.loadError != null -> LoadFailure(
                    message = state.loadError!!,
                    canRetry = state.canRetryLoad,
                    onRetry = viewModel::load,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> TipForm(state = state, viewModel = viewModel)
            }
        }
    }

    state.submitError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissSubmitError,
            title = { Text("No se pudo enviar") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSubmitError) { Text("Entendido") }
            },
        )
    }
}

@Composable
private fun TipForm(state: TipFormUiState, viewModel: TipFormViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        state.info?.let { BikeCard(it) }

        SectionTitle("¿Cuándo la viste?")

        SightingDateField(
            value = state.sightingDate,
            onChange = viewModel::setDate,
        )

        OutlinedTextField(
            value = state.sightingTime,
            onValueChange = viewModel::setTime,
            label = { Text("Hora aproximada (opcional)") },
            placeholder = { Text("20:30, o \"a la tarde\"") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("¿Qué viste?")

        OutlinedTextField(
            value = state.description,
            onValueChange = viewModel::setDescription,
            label = { Text("Descripción del avistamiento") },
            placeholder = {
                Text("Quién la llevaba, hacia dónde iba, si estaba en venta…")
            },
            minLines = 4,
            isError = state.descriptionTooLong,
            supportingText = {
                Text(
                    text = if (state.descriptionTooLong) {
                        "Máximo ${SubmitTipRequestDto.MAX_DESCRIPTION} caracteres."
                    } else {
                        "${state.description.length} / ${SubmitTipRequestDto.MAX_DESCRIPTION}"
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("¿Dónde la viste?")

        LocationSection(state = state, viewModel = viewModel)

        SectionTitle("¿Cómo te contactamos?")

        ContactSection(state = state, viewModel = viewModel)

        Button(
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.submitting) "Enviando…" else "Enviar la pista")
        }

        Box(Modifier.navigationBarsPadding().padding(bottom = 24.dp))
    }
}

/**
 * La bici sobre la que se reporta.
 *
 * Va primero y con todo lo que la identifica: quien abre el formulario tiene que
 * poder confirmar que la que vio es ésta antes de escribir nada. Un formulario
 * que arranca pidiendo datos sobre una bici que no se muestra obliga a confiar
 * en que el link era el correcto.
 *
 * La zona va hasta la localidad y nunca la calle. Es el mismo criterio del
 * cartel público, y no es cosmético: la dirección del robo suele ser el
 * domicilio de la víctima.
 */
@Composable
private fun BikeCard(info: TipFormInfoDto) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = listOfNotNull(info.bike?.brandName, info.bike?.modelName)
                    .joinToString(" ")
                    .ifBlank { "Bicicleta robada" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            info.bike?.year?.let { Detail("Año $it") }

            info.theftDate?.let { Detail("Robada el ${formatLongDate(it)}") }

            val zona = listOfNotNull(
                info.location?.localityName,
                info.location?.departmentName,
                info.location?.provinceName,
            ).distinct().joinToString(", ")
            if (zona.isNotBlank()) Detail(zona)

            // La recompensa se muestra si la hay: es lo que puede convertir a
            // alguien que dudaba en alguien que escribe.
            info.reward?.takeIf { it.offered }?.let { reward ->
                Text(
                    text = reward.formatted?.let { "Recompensa: $it" } ?: "Ofrece recompensa",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Detail(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LocationSection(state: TipFormUiState, viewModel: TipFormViewModel) {
    Text(
        text = if (!state.hasPoint) "Tocá el mapa donde la viste"
        else "Arrastrá el marcador si no quedó justo",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    MapPicker(
        latitude = state.latitude,
        longitude = state.longitude,
        centerOn = state.centerOn,
        onPointChanged = viewModel::setPoint,
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (state.hasPoint) {
                Text(
                    text = "Punto marcado",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${state.latitude}, ${state.longitude}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    TextButton(onClick = viewModel::resolveAddress, enabled = !state.geocoding) {
                        Text(if (state.geocoding) "Buscando…" else "¿Qué dirección es?")
                    }
                    TextButton(onClick = viewModel::clearPoint) { Text("Quitar") }
                }
            } else {
                OutlinedButton(
                    onClick = viewModel::useCurrentLocation,
                    enabled = !state.locating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.locating) "Buscando…" else "📍 Estoy en el lugar")
                }
                Text(
                    text = "Marca dónde estás ahora. Si ya te fuiste del lugar, " +
                        "mejor tocá el mapa donde la viste.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            listOfNotNull(state.locationError, state.geocodingError).forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    // La dirección propuesta se confirma o se descarta. El punto ya viaja desde
    // que se marcó; la calle sólo si el informante dice que sí — el backend
    // respeta la que le mandan y no la vuelve a geocodificar.
    state.resolvedAddress?.let { address ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = address.display ?: "Sin dirección conocida",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row {
                    TextButton(onClick = viewModel::acceptAddress) { Text("Usar esta dirección") }
                    TextButton(onClick = viewModel::rejectAddress) { Text("No es") }
                }
            }
        }
    }

    state.acceptedAddress?.let { address ->
        Text(
            text = "Dirección: ${address.display ?: "—"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * El contacto: los dos campos por separado.
 *
 * Es opcional, y por eso el texto explica para qué sirve en vez de pedirlo a
 * secas. Quien reporta una bici robada tiene motivos para no querer dar sus
 * datos, y la aclaración de que el dueño decide —no el informante— es lo que
 * separa dejar un teléfono de no dejar nada.
 *
 * El hint del formato va escrito porque `wa.me` exige el número internacional
 * sin símbolos, y sin el código de país el link del dueño se abre roto.
 */
@Composable
private fun ContactSection(state: TipFormUiState, viewModel: TipFormViewModel) {
    Text(
        text = "Es opcional. Si dejás algo, el dueño puede escribirte para pedirte " +
            "más detalles — nunca al revés, y sólo si él decide hacerlo.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = state.informantEmail,
        onValueChange = viewModel::setEmail,
        label = { Text("Mail (opcional)") },
        placeholder = { Text("nombre@ejemplo.com") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = state.informantPhone,
        onValueChange = viewModel::setPhone,
        label = { Text("Teléfono o WhatsApp (opcional)") },
        placeholder = { Text("541155551234") },
        supportingText = { Text("Con código de país y sin espacios ni guiones.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * El campo de fecha.
 *
 * No admite fechas futuras: un avistamiento que todavía no pasó no existe, y el
 * backend lo rechaza. Cortarlo acá evita perder el formulario entero por eso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SightingDateField(value: LocalDate, onChange: (LocalDate) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val zone = TimeZone.currentSystemDefault()

    OutlinedTextField(
        value = formatLongDate(value),
        onValueChange = {},
        readOnly = true,
        label = { Text("Fecha del avistamiento") },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(onClick = { showPicker = true }) { Text("Cambiar") }
        },
    )

    if (showPicker) {
        val maxMillis = Clock.System.now().toEpochMilliseconds()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = value.atStartOfDayIn(zone).toEpochMilliseconds(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= maxMillis
            },
        )

        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onChange(
                                Instant.fromEpochMilliseconds(millis)
                                    .toLocalDateTime(TimeZone.UTC)
                                    .date,
                            )
                        }
                        showPicker = false
                    },
                ) { Text("Listo") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancelar") }
            },
        ) { DatePicker(state = pickerState) }
    }
}

/**
 * Confirmación del envío.
 *
 * Acá se entrega el link del hilo, que es lo que faltaba para que la
 * conversación existiera de verdad. El backend emitía el `conversationToken` en
 * la respuesta del POST desde siempre; el front web lo descartaba, así que el
 * dueño podía escribir en un hilo que el otro extremo no tenía forma de abrir.
 *
 * No ofrece "mandar otra pista": quien reportó ya dijo lo que sabía, y el rate
 * limit del backend rechazaría la segunda de todas formas.
 *
 * **No promete un aviso por mail.** El dueño puede contestar, pero el envío de
 * ese aviso hoy no sale de theft-report —el adaptador de notificaciones sólo
 * escribe en la consola—, así que decir "te avisamos" sería mentir. Se dice lo
 * que sí es cierto: que este link es el que hay que guardar.
 */
@Composable
private fun SuccessState(
    conversationToken: String?,
    onOpenConversation: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pista enviada",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Gracias. El dueño ya recibió el aviso y va a ver lo que contaste.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (conversationToken != null) {
            Text(
                text = "Si el dueño quiere preguntarte algo, te va a escribir acá. " +
                    "Sigue siendo anónimo: no ve tus datos, ni vos los suyos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Button(
                onClick = { onOpenConversation(conversationToken) },
                modifier = Modifier.padding(top = 16.dp),
            ) { Text("Ver la conversación") }
            TextButton(onClick = onClose) { Text("Cerrar") }
        } else {
            Button(onClick = onClose, modifier = Modifier.padding(top = 24.dp)) { Text("Cerrar") }
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

package pbis.bike.finder.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.Clock
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.core.net.toUri
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import pbis.bike.finder.data.local.ThemePreference
import pbis.bike.finder.data.remote.dto.Gender
import pbis.bike.finder.ui.common.Dropdown
import pbis.bike.finder.ui.common.textoPrimero
import pbis.bike.finder.ui.common.initialsOf
import pbis.bike.finder.ui.common.formatLongDate
import pbis.bike.finder.ui.theme.LocalThemeController
import pbis.bike.finder.ui.theme.isDark

/**
 * Mi perfil, equivalente a `perfil.html` del front web.
 *
 * Conserva el patrón ver/editar de la web —una misma tarjeta que cambia de
 * modo— en vez de abrir una pantalla aparte de edición: los datos son pocos y
 * mandarlos a otra pantalla haría perder de vista lo que se está cambiando.
 *
 * Las cuatro secciones son las de la web: datos personales, ubicación de
 * referencia, apariencia y notificaciones. La única que no se porta tal cual es
 * el avatar — ver [ProfileAvatar].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Mi perfil") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Volver") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            // Sin perfil no hay nada que mostrar ni editar. Las otras secciones
            // sí sobreviven a su propio error, pero ésta es la pantalla entera.
            //
            // **Cerrar sesión va igual acá**, y es el caso que más importa. Si
            // el perfil no carga suele ser porque auth-service no responde, y
            // entonces tampoco anda nada más: la sesión quedó viva con un token
            // que el backend ya no acepta. Sin este botón el usuario queda
            // encerrado —adentro, sin poder hacer nada y sin poder salir—,
            // porque el "Salir" de la barra superior ya no existe. Volver a
            // entrar es lo único que arregla una sesión así, y `logout()` sirve
            // aunque el backend no conteste: avisa si puede, pero borra los
            // tokens y cierra la sesión pase lo que pase con esa llamada.
            state.profile == null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LoadFailure(
                    message = state.loadError ?: "No pudimos cargar tu perfil.",
                    canRetry = state.canRetryLoad,
                    onRetry = viewModel::loadProfile,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Column(Modifier.padding(16.dp).navigationBarsPadding()) {
                    LogoutCard(onLogout = viewModel::logout)
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ProfileAvatar(state.profile?.fullName, state.profile?.email) }

                item {
                    if (state.editing) {
                        EditCard(state = state, viewModel = viewModel)
                    } else {
                        ViewCard(
                            state = state,
                            onEdit = viewModel::enterEditMode,
                            onResendVerification = viewModel::resendVerification,
                        )
                    }
                }

                item { TwoFactorCard(state = state, viewModel = viewModel) }

                item { AppearanceCard() }

                item {
                    NotificationsCard(
                        state = state,
                        onToggle = viewModel::setEmailNotifications,
                        onRetry = viewModel::loadNotifications,
                    )
                }

                item { LogoutCard(onLogout = viewModel::logout) }

                item { Box(Modifier.height(8.dp).navigationBarsPadding()) }
            }
        }
    }
}

/**
 * Iniciales sobre el dorado de marca.
 *
 * El front web muestra una foto según el género (`static/man.jpg`,
 * `woman.jpg`, `alien.jpg`). No se portan: son tres fotos de stock que habría
 * que meter en el APK para decorar, y asignarle una cara genérica a alguien
 * porque marcó un género es una decisión con más aristas que valor. Las
 * iniciales identifican igual de bien y salen del dato que el usuario ya cargó.
 */
@Composable
private fun ProfileAvatar(fullName: String?, email: String?) {
    val initials = remember(fullName, email) { initialsOf(fullName, email) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            text = "Administrá tu información personal",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ── Modo lectura ─────────────────────────────────────────────────────────────

@Composable
private fun ViewCard(
    state: ProfileUiState,
    onEdit: () -> Unit,
    onResendVerification: () -> Unit,
) {
    val profile = state.profile ?: return

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Información personal",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEdit) { Text("Editar") }
        }

        DataRow("Email", profile.email)

        // El estado de verificación va como chip y no como texto: es lo único de
        // la pantalla sobre lo que el usuario puede tener que actuar. El chip
        // sigue siendo sólo el estado —no se toca— y la acción va al lado, que es
        // lo que faltaba: hasta ahora decía "Sin verificar" y no ofrecía salida.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(if (profile.emailVerified == true) "Verificado" else "Sin verificar")
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledLabelColor = if (profile.emailVerified == true) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                ),
            )

            if (profile.emailVerified != true) {
                TextButton(
                    onClick = onResendVerification,
                    enabled = !state.resendingVerification,
                ) {
                    Text("Reenviar mail")
                }
            }
        }

        DataRow("Nombre completo", profile.fullName)
        DataRow("Teléfono", profile.phoneNumber)
        DataRow("Género", Gender.fromApi(profile.gender)?.label)
        DataRow("Fecha de nacimiento", profile.birthDate?.let { formatLongDate(it) })

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Text(
            text = "Ubicación de referencia",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Los tres rótulos salen del país de la ubicación guardada, no de un texto
        // fijo: `department_name` puede tener adentro una provincia chilena.
        DataRow("País", profile.location?.countryName)
        DataRow(state.etiquetaNivel1.nombre, profile.location?.provinceName)
        DataRow(state.etiquetaNivel2.nombre, profile.location?.departmentName)
        DataRow(state.etiquetaLocalidad.nombre, profile.location?.localityName)

        Text(
            text = "Sólo es de referencia y no se comparte públicamente.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Un dato del perfil.
 *
 * Un campo vacío dice "No especificado" y no queda en blanco: un renglón vacío se
 * lee como un dato que no cargó, y manda al usuario a recargar una pantalla que
 * está bien.
 */
@Composable
private fun DataRow(label: String, value: String?) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: "No especificado",
            style = MaterialTheme.typography.bodyLarge,
            color = if (value.isNullOrBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

// ── Modo edición ─────────────────────────────────────────────────────────────

@Composable
private fun EditCard(state: ProfileUiState, viewModel: ProfileViewModel) {
    SectionCard {
        Text(
            text = "Información personal",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // El email es el identificador de la cuenta: el backend no lo acepta en
        // el PUT. Se muestra deshabilitado en vez de ocultarlo, para que quede
        // claro cuál es la cuenta que se está editando.
        OutlinedTextField(
            value = state.profile?.email.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("Email") },
            supportingText = { Text("El email no se puede modificar") },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.fullName,
            onValueChange = viewModel::onFullNameChange,
            label = { Text("Nombre completo") },
            singleLine = true,
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.phoneNumber,
            onValueChange = viewModel::onPhoneChange,
            label = { Text("Teléfono") },
            placeholder = { Text("+5491122334455") },
            singleLine = true,
            isError = state.phoneError != null,
            supportingText = {
                Text(state.phoneError ?: "Con código de país, empezando por +")
            },
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Dropdown(
            label = "Género",
            options = Gender.entries,
            selected = state.gender,
            optionLabel = { it.label },
            onSelect = viewModel::onGenderChange,
            enabled = !state.saving,
            placeholder = "Prefiero no decir",
        )

        BirthDateField(
            date = state.birthDate,
            enabled = !state.saving,
            onDateChange = viewModel::onBirthDateChange,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Text(
            text = "Ubicación de referencia",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Dropdown(
            label = "País",
            options = state.countries,
            selected = state.countries.firstOrNull { it.id == state.countryId },
            optionLabel = { it.name },
            onSelect = { viewModel.selectCountry(it?.id) },
            enabled = !state.saving && state.countries.isNotEmpty(),
        )

        // Cada nivel se deshabilita hasta que el de arriba esté elegido: es el
        // mismo encadenado de los `<select disabled>` de la web.
        Dropdown(
            label = state.etiquetaNivel1.nombre,
            options = state.provinces,
            selected = state.provinces.firstOrNull { it.id == state.provinceId },
            optionLabel = { it.name },
            onSelect = { viewModel.selectProvince(it?.id) },
            enabled = !state.saving && state.provinces.isNotEmpty(),
            placeholder = "Primero elegí un país",
        )

        Dropdown(
            label = state.etiquetaNivel2.nombre,
            options = state.departments,
            selected = state.departments.firstOrNull { it.id == state.departmentId },
            optionLabel = { it.name },
            onSelect = { viewModel.selectDepartment(it?.id) },
            enabled = !state.saving && state.departments.isNotEmpty(),
            placeholder = state.etiquetaNivel1.textoPrimero(),
        )

        Dropdown(
            label = state.etiquetaLocalidad.nombre,
            options = state.localities,
            selected = state.localities.firstOrNull { it.id == state.localityId },
            optionLabel = { it.name },
            onSelect = { viewModel.selectLocality(it?.id) },
            enabled = !state.saving && state.localities.isNotEmpty(),
            placeholder = state.etiquetaNivel2.textoPrimero(),
        )

        if (state.loadingGeo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text(
                    text = "Cargando ubicaciones…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        state.geoError?.let { InlineError(it) }
        state.formError?.let { InlineError(it) }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::cancelEdit,
                enabled = !state.saving,
                modifier = Modifier.weight(1f),
            ) { Text("Cancelar") }

            Button(
                onClick = viewModel::save,
                enabled = !state.saving,
                modifier = Modifier.weight(1f),
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("Guardar")
                }
            }
        }
    }
}

/**
 * Fecha de nacimiento.
 *
 * Mismo patrón que la fecha del robo: campo de sólo lectura más un botón que
 * abre el calendario. Nadie tipea una fecha a mano en un teléfono.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDateField(
    date: LocalDate?,
    enabled: Boolean,
    onDateChange: (LocalDate?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val today = remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }

    OutlinedTextField(
        value = date?.let { formatLongDate(it) } ?: "",
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text("Fecha de nacimiento") },
        placeholder = { Text("Sin especificar") },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { showPicker = true },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) { Text(if (date == null) "Elegir fecha" else "Cambiar") }
    }

    if (showPicker) {
        val maxMillis = today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds(),
            selectableDates = object : SelectableDates {
                // Nadie nació mañana. El backend no lo valida.
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= maxMillis
            },
        )

        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDateChange(
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

// ── Apariencia ───────────────────────────────────────────────────────────────

/**
 * El tema, con las tres opciones.
 *
 * La web tiene un switch binario de modo oscuro. Acá son tres porque existe
 * "seguir al sistema", que en un teléfono es el default razonable y en un
 * navegador de escritorio pesa menos. Es el mismo control que está en el login.
 */
@Composable
private fun AppearanceCard() {
    val controller = LocalThemeController.current

    SectionCard {
        Text(
            text = "Apariencia",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Se recuerda en este dispositivo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Dropdown(
            label = "Tema",
            options = ThemePreference.entries,
            selected = controller.preference,
            optionLabel = { it.label },
            onSelect = { it?.let(controller.onChange) },
        )

        // El desplegable dice la PREFERENCIA; esto dice el ESTADO. Con
        // "Automático" elegido son dos cosas distintas —y es justamente el caso
        // en que el usuario no tiene cómo saber cuál de los dos temas rige—.
        Text(
            text = if (controller.preference.isDark()) {
                "Ahora se ve en modo oscuro."
            } else {
                "Ahora se ve en modo claro."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Segundo factor ───────────────────────────────────────────────────────────

/**
 * Verificación en dos pasos.
 *
 * **No hay QR, a diferencia del front web**, y no es una simplificación: acá el
 * usuario ya está en el teléfono, así que escanear un código que se dibuja en esa
 * misma pantalla es imposible. En su lugar se abre la app de autenticación con la
 * URI `otpauth://` —que es lo que el QR contiene—, y queda la clave en Base32
 * para cargarla a mano si no hay ninguna app instalada o si el segundo factor
 * vive en otro dispositivo.
 *
 * La URI **lleva el secreto adentro**: se abre con un intent local y no se manda
 * a ningún servicio, por la misma razón por la que la web dibuja el QR en el
 * cliente en vez de pedirle el PNG a un tercero.
 */
@Composable
private fun TwoFactorCard(state: ProfileUiState, viewModel: ProfileViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val estado = state.totp

    SectionCard {
        Text(
            text = "Verificación en dos pasos",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = when {
                estado == null -> "No pudimos consultar el estado."
                !estado.enabled ->
                    "Un código que cambia cada 30 segundos, además de tu contraseña."
                estado.recoveryCodesRemaining == 0L ->
                    "Está activa y no te quedan códigos de recuperación: si perdés el " +
                        "teléfono no vas a poder entrar. Generá códigos nuevos."
                estado.recoveryCodesRemaining == 1L ->
                    "Está activa. Te queda 1 código de recuperación."
                else ->
                    "Está activa. Te quedan ${estado.recoveryCodesRemaining} códigos " +
                        "de recuperación."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.totpError?.let { InlineError(it) }

        // El alta en curso ocupa la tarjeta entera: mostrar además el botón de
        // "Activar" sería ofrecer empezar de nuevo algo que ya está empezado.
        val alta = state.totpSetup
        if (alta != null) {
            Text(
                text = "Cargá esta cuenta en tu app de autenticación y escribí el código " +
                    "que te muestre.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            OutlinedButton(
                onClick = {
                    // Si no hay ninguna app que registre otpauth://, el intent no
                    // resuelve. No es fatal: la clave está abajo.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, alta.provisioningUri.toUri()),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Abrir en mi app de autenticación") }

            Text(
                text = "O cargá esta clave a mano:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = alta.secret,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(alta.secret)) }) {
                Text("Copiar la clave")
            }

            OutlinedTextField(
                value = state.totpCode,
                onValueChange = viewModel::onTotpCodeChange,
                label = { Text("Código de 6 dígitos") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                enabled = !state.totpBusy,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::confirmTotpSetup,
                    enabled = !state.totpBusy && state.totpCode.length == 6,
                    modifier = Modifier.weight(1f),
                ) { Text("Confirmar") }
                OutlinedButton(
                    onClick = viewModel::cancelTotpSetup,
                    enabled = !state.totpBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancelar") }
            }
            return@SectionCard
        }

        if (estado == null) {
            // Botón inerte antes que un botón que miente sobre lo que hay
            // configurado: mismo criterio que las preferencias de aviso.
            TextButton(onClick = viewModel::loadTotpStatus) { Text("Reintentar") }
            return@SectionCard
        }

        if (estado.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.askTotpCode(TotpPrompt.REGENERATE) },
                    enabled = !state.totpBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("Códigos nuevos") }
                OutlinedButton(
                    onClick = { viewModel.askTotpCode(TotpPrompt.DISABLE) },
                    enabled = !state.totpBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("Desactivar") }
            }
        } else {
            Button(
                onClick = viewModel::startTotpSetup,
                enabled = !state.totpBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Activar") }
        }
    }

    state.totpPrompt?.let { prompt ->
        TotpCodeDialog(
            prompt = prompt,
            code = state.totpCode,
            busy = state.totpBusy,
            error = state.totpError,
            onCodeChange = viewModel::onTotpCodeChange,
            onConfirm = viewModel::submitTotpPrompt,
            onDismiss = viewModel::dismissTotpPrompt,
        )
    }

    state.recoveryCodes?.let { codes ->
        RecoveryCodesDialog(
            codes = codes,
            onCopy = { clipboard.setText(AnnotatedString(codes.joinToString("\n"))) },
            onDismiss = viewModel::dismissRecoveryCodes,
        )
    }
}

@Composable
private fun TotpCodeDialog(
    prompt: TotpPrompt,
    code: String,
    busy: Boolean,
    error: String?,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                when (prompt) {
                    TotpPrompt.REGENERATE -> "Generar códigos nuevos"
                    TotpPrompt.DISABLE -> "Desactivar la verificación en dos pasos"
                },
            )
        },
        text = {
            Column {
                Text(
                    when (prompt) {
                        TotpPrompt.REGENERATE ->
                            "Los códigos anteriores dejan de servir. Escribí el código de " +
                                "tu app para confirmar."
                        TotpPrompt.DISABLE ->
                            "Tu cuenta va a quedar protegida sólo por la contraseña. " +
                                "Escribí el código de tu app —o uno de recuperación— " +
                                "para confirmar."
                    },
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text("Código") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                    ),
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                error?.let { InlineError(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && code.isNotBlank()) {
                Text(
                    when (prompt) {
                        TotpPrompt.REGENERATE -> "Generar"
                        TotpPrompt.DISABLE -> "Desactivar"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        },
    )
}

/**
 * Los códigos de recuperación, **una sola vez**.
 *
 * El backend guarda sólo su hash y no hay endpoint que los liste: si el usuario
 * cierra esto sin guardarlos, la única salida es regenerarlos. Por eso el
 * diálogo no se cierra tocando afuera y el botón dice explícitamente que ya
 * están guardados.
 */
@Composable
private fun RecoveryCodesDialog(
    codes: List<String>,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Guardá estos códigos") },
        text = {
            Column {
                Text(
                    text = "Son de un solo uso y sirven para entrar si perdés el teléfono. " +
                        "No los vas a poder volver a ver.",
                    style = MaterialTheme.typography.bodySmall,
                )
                codes.forEach { codigo ->
                    Text(
                        text = codigo,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                TextButton(onClick = onCopy, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Copiar todos")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Ya los guardé") }
        },
    )
}

// ── Notificaciones ───────────────────────────────────────────────────────────

@Composable
private fun NotificationsCard(
    state: ProfileUiState,
    onToggle: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    val prefs = state.notifications

    SectionCard {
        Text(
            text = "Notificaciones",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Avisos por email",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Te escribimos cuando alguien deja una pista sobre tu bici " +
                        "o cuando encontramos una publicación que puede ser tuya.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Switch(
                checked = prefs?.emailEnabled == true,
                onCheckedChange = onToggle,
                enabled = state.notificationsReady,
            )
        }

        // El canal puede estar prendido y ser inservible si la cuenta no tiene
        // dirección. Sin este aviso el usuario ve el switch encendido y no
        // entiende por qué no le llega nada.
        if (prefs != null && prefs.emailEnabled && prefs.email.isNullOrBlank()) {
            InlineError(
                "Tu cuenta no tiene una dirección de email asociada, " +
                    "así que todavía no podemos escribirte.",
            )
        }

        // Esto la web no lo muestra, y es el aviso que más importa: con todos los
        // canales apagados, una pista sobre la bici robada se registra y no se
        // notifica por ningún lado.
        if (prefs != null && !prefs.anyChannelEnabled) {
            InlineError(
                "No tenés ningún canal de aviso activo: si alguien deja una pista " +
                    "sobre tu bici, no vamos a poder avisarte.",
            )
        }

        state.notificationsError?.let {
            InlineError(it)
            TextButton(onClick = onRetry) { Text("Reintentar") }
        }
    }
}

// ── Piezas compartidas ───────────────────────────────────────────────────────

/**
 * Cerrar sesión.
 *
 * Vive acá desde que la barra superior quedó sólo con el avatar. Antes era un
 * "Salir" pegado al borde del dashboard: la acción más destructiva de la app
 * ocupando el lugar donde el pulgar cae solo, al lado de "Perfil", con el que se
 * confunde por tamaño y forma. En el perfil está donde uno lo busca —junto al
 * resto de lo que es la cuenta y no las bicicletas— y lejos de todo lo que se
 * usa seguido.
 *
 * Pide confirmación porque volver cuesta: hay que tipear mail y contraseña de
 * nuevo, y eso no puede ser la consecuencia de un toque perdido.
 */
@Composable
private fun LogoutCard(onLogout: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    SectionCard {
        OutlinedButton(
            onClick = { confirming = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cerrar sesión", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("¿Cerrar sesión?") },
            text = { Text("Vas a tener que volver a entrar con tu mail y contraseña.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onLogout()
                    },
                ) { Text("Cerrar sesión", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun InlineError(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
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
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (canRetry) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Reintentar")
            }
        }
    }
}

/**
 * Etiqueta en español de cada género.
 *
 * Vive acá y no en el enum del DTO a propósito: el enum existe para validar
 * contra el `@Pattern` del backend, y meterle texto de interfaz lo ataría al
 * idioma de la app. Son los mismos cuatro rótulos del `<select>` de la web.
 */
private val Gender.label: String
    get() = when (this) {
        Gender.MALE -> "Masculino"
        Gender.FEMALE -> "Femenino"
        Gender.ALIEN -> "Alien"
        Gender.PREFER_NOT_TO_SAY -> "Prefiero no decir"
    }

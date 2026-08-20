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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import pbis.bike.finder.data.local.ThemePreference
import pbis.bike.finder.data.remote.dto.Gender
import pbis.bike.finder.ui.common.Dropdown
import pbis.bike.finder.ui.common.formatLongDate
import pbis.bike.finder.ui.theme.LocalThemeController

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
            state.profile == null -> LoadFailure(
                message = state.loadError ?: "No pudimos cargar tu perfil.",
                canRetry = state.canRetryLoad,
                onRetry = viewModel::loadProfile,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { ProfileAvatar(state.profile?.fullName, state.profile?.email) }

                item {
                    if (state.editing) {
                        EditCard(state = state, viewModel = viewModel)
                    } else {
                        ViewCard(state = state, onEdit = viewModel::enterEditMode)
                    }
                }

                item { AppearanceCard() }

                item {
                    NotificationsCard(
                        state = state,
                        onToggle = viewModel::setEmailNotifications,
                        onRetry = viewModel::loadNotifications,
                    )
                }

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

/** Hasta dos iniciales. Cae al email porque el nombre es opcional en el backend. */
private fun initialsOf(fullName: String?, email: String?): String {
    val fromName = fullName?.trim().orEmpty()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")

    if (fromName.isNotBlank()) return fromName
    return email?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

// ── Modo lectura ─────────────────────────────────────────────────────────────

@Composable
private fun ViewCard(state: ProfileUiState, onEdit: () -> Unit) {
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
        // la pantalla sobre lo que el usuario puede tener que actuar.
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

        DataRow("País", profile.location?.countryName)
        DataRow("Provincia", profile.location?.provinceName)
        DataRow("Departamento / partido", profile.location?.departmentName)
        DataRow("Localidad", profile.location?.localityName)

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
            label = "Provincia",
            options = state.provinces,
            selected = state.provinces.firstOrNull { it.id == state.provinceId },
            optionLabel = { it.name },
            onSelect = { viewModel.selectProvince(it?.id) },
            enabled = !state.saving && state.provinces.isNotEmpty(),
            placeholder = "Primero elegí un país",
        )

        Dropdown(
            label = "Departamento / partido",
            options = state.departments,
            selected = state.departments.firstOrNull { it.id == state.departmentId },
            optionLabel = { it.name },
            onSelect = { viewModel.selectDepartment(it?.id) },
            enabled = !state.saving && state.departments.isNotEmpty(),
            placeholder = "Primero elegí una provincia",
        )

        Dropdown(
            label = "Localidad",
            options = state.localities,
            selected = state.localities.firstOrNull { it.id == state.localityId },
            optionLabel = { it.name },
            onSelect = { viewModel.selectLocality(it?.id) },
            enabled = !state.saving && state.localities.isNotEmpty(),
            placeholder = "Primero elegí un departamento",
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
    }
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

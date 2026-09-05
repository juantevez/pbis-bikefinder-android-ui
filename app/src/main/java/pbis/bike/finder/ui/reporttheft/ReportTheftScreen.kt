package pbis.bike.finder.ui.reporttheft

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import pbis.bike.finder.ui.common.Dropdown
import pbis.bike.finder.ui.common.textoPrimero
import pbis.bike.finder.ui.common.MapPicker

/**
 * Formulario de denuncia.
 *
 * Cuatro secciones, y sólo dos datos obligatorios: la fecha —que nace con hoy— y
 * la ubicación. El orden importa: quien entra acá acaba de sufrir un robo, así
 * que lo imprescindible va arriba y la recompensa al final.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportTheftScreen(
    bikeId: String?,
    reportId: String?,
    onReported: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReportTheftViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Una de las dos, nunca las dos: con `reportId` la pantalla corrige una
    // denuncia ya presentada; con `bikeId`, presenta una nueva.
    LaunchedEffect(bikeId, reportId) {
        if (reportId != null) viewModel.startEdit(reportId) else bikeId?.let(viewModel::start)
    }

    // El permiso de ubicación se pide sólo al tocar el botón: el resto del
    // formulario funciona sin él, eligiendo la localidad a mano.
    val requestLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) viewModel.useCurrentLocation()
        else viewModel.onLocationPermissionDenied()
    }

    // Abrir el PDF es un efecto, no un estado: se dispara una vez y se limpia.
    LaunchedEffect(state.pdfUrl) {
        val url = state.pdfUrl ?: return@LaunchedEffect
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        viewModel.onPdfOpened()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (state.modoEdicion) "Corregir denuncia" else "Denunciar robo")
                        state.bikeName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        if (state.cargandoReporte) {
            // Mostrar el formulario vacío y llenarlo un segundo después sería
            // invitar a escribir sobre algo que se va a pisar solo.
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.modoEdicion) {
                Text(
                    text = "Cambiá lo que haga falta. Al guardar, el PDF que hayas " +
                        "descargado antes queda desactualizado y vas a poder bajar el corregido.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionTitle("Qué pasó")
            DateField(
                date = state.theftDate,
                maxDate = state.maxDate,
                error = state.fieldErrors["fecha"],
                onDateChange = viewModel::setDate,
            )
            Dropdown(
                label = "Hora aproximada",
                options = TheftTimeSlot.entries,
                selected = state.timeSlot,
                optionLabel = { it.label },
                onSelect = viewModel::setTimeSlot,
                placeholder = "No lo sé",
            )
            OutlinedTextField(
                value = state.description,
                onValueChange = viewModel::setDescription,
                label = { Text("Cómo fue") },
                placeholder = { Text("Estaba atada al poste con candado en U…") },
                isError = state.fieldErrors.containsKey("descripcion"),
                supportingText = state.fieldErrors["descripcion"]?.let { { Text(it) } },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle("Dónde fue")
            Text(
                text = "Es el único dato que no podemos deducir. Tocá el mapa donde fue, " +
                    "o elegí la localidad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LocationSection(
                state = state,
                onCountry = viewModel::selectCountry,
                onProvince = viewModel::selectProvince,
                onDepartment = viewModel::selectDepartment,
                onLocality = viewModel::selectLocality,
                onRetryGeo = viewModel::loadCountries,
                onPointChanged = viewModel::setPoint,
                onResolveAddress = viewModel::resolveAddress,
                onApplyAddress = viewModel::applyResolvedAddress,
                onDiscardAddress = viewModel::discardResolvedAddress,
                onUseCurrentLocation = {
                    val fine = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    val coarse = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED

                    if (fine || coarse) {
                        viewModel.useCurrentLocation()
                    } else {
                        requestLocation.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
                onClearPoint = viewModel::clearPoint,
            )

            state.avisoLocalidad?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.fieldErrors["ubicacion"]?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Aviso, no error: la denuncia se puede presentar así. Lo que no
            // sirve es el cartel público, y eso no se nota mirando el PDF
            // privado —que sí muestra la calle—, así que hay que decirlo acá.
            if (state.faltaLocalidadConPunto) {
                // Se explica acá, al lado de los desplegables, y no sólo al enviar:
                // el usuario ya marcó el punto y cree que terminó con la ubicación.
                Text(
                    text = "Falta la localidad. La provincia y la localidad de los PDF " +
                        "salen de acá, no del punto del mapa: sin elegirla, el informe " +
                        "para la policía sale sin jurisdicción.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Dropdown(
                label = "Tipo de vía",
                options = StreetType.entries,
                selected = state.streetType,
                optionLabel = { it.label },
                onSelect = viewModel::setStreetType,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.streetName,
                    onValueChange = viewModel::setStreetName,
                    label = { Text("Calle") },
                    isError = state.fieldErrors.containsKey("calle"),
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = state.streetNumber,
                    onValueChange = viewModel::setStreetNumber,
                    label = { Text("Altura") },
                    isError = state.fieldErrors.containsKey("altura"),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = state.reference,
                onValueChange = viewModel::setReference,
                label = { Text("Referencia") },
                placeholder = { Text("Frente a la plaza") },
                isError = state.fieldErrors.containsKey("referencia"),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle("Cómo te contactan")
            OutlinedTextField(
                value = state.contactPhone,
                onValueChange = viewModel::setContactPhone,
                label = { Text("Teléfono") },
                isError = state.fieldErrors.containsKey("telefono"),
                supportingText = state.fieldErrors["telefono"]?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.contactEmail,
                onValueChange = viewModel::setContactEmail,
                label = { Text("Email") },
                isError = state.fieldErrors.containsKey("email"),
                supportingText = state.fieldErrors["email"]?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                checked = state.contactPublic,
                onCheckedChange = viewModel::setContactPublic,
                title = "Mostrar mi contacto públicamente",
                subtitle = "Cualquiera que vea la denuncia publicada podrá escribirte directo. " +
                    "Si está apagado, las pistas te llegan igual por la app.",
            )

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            SectionTitle("Recompensa")
            ToggleRow(
                checked = state.rewardOffered,
                onCheckedChange = viewModel::setRewardOffered,
                title = "Ofrezco recompensa",
                // Sólo se publica que hay recompensa: el monto se dejó de pedir en
                // agosto de 2026 porque el backend no lo guarda más y el arreglo
                // ocurre fuera del sistema.
                subtitle = "Sólo se publica que ofrecés recompensa. El monto lo arreglás " +
                    "directamente con quien encuentre tu bicicleta.",
            )

            state.formError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(
                onClick = viewModel::submit,
                // Un 409 al guardar no se reintenta: la denuncia se cerró y
                // volver a apretar no puede funcionar nunca.
                enabled = !state.submitting && !state.cerradaAlGuardar,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(
                    when {
                        state.cerradaAlGuardar -> "La denuncia está cerrada"
                        state.submitting && state.modoEdicion -> "Guardando…"
                        state.submitting -> "Enviando…"
                        state.modoEdicion -> "Guardar cambios"
                        else -> "Presentar la denuncia"
                    }
                )
            }
        }
    }

    if (state.createdReportId != null) {
        ReportCreatedDialog(
            modoEdicion = state.modoEdicion,
            generatingPdf = state.generatingPdf,
            pdfError = state.pdfError,
            onDownloadPdf = viewModel::downloadPdf,
            onClose = onReported,
        )
    }

    // La denuncia no se puede corregir y no hay formulario que mostrar: el
    // diálogo es terminal, la única salida es volver.
    state.noEditable?.let { motivo ->
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text("No se puede corregir") },
            text = { Text(motivo) },
            confirmButton = { TextButton(onClick = onBack) { Text("Volver") } },
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    date: LocalDate,
    maxDate: LocalDate,
    error: String?,
    onDateChange: (LocalDate) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = date.toString(),
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text("Fecha del robo") },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Cambiar fecha")
    }

    if (showPicker) {
        val maxMillis = maxDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            selectableDates = object : SelectableDates {
                // Una bici no puede haber sido robada mañana. El backend no lo
                // valida, así que la única barrera es esta.
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

@Composable
private fun LocationSection(
    state: ReportTheftUiState,
    onCountry: (Int?) -> Unit,
    onProvince: (Int?) -> Unit,
    onDepartment: (Int?) -> Unit,
    onLocality: (Int?) -> Unit,
    onRetryGeo: () -> Unit,
    onPointChanged: (Double, Double) -> Unit,
    onResolveAddress: () -> Unit,
    onApplyAddress: () -> Unit,
    onDiscardAddress: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onClearPoint: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Que location-service esté caído tiene que decirse. Sin esto, los
        // desplegables vacíos se leen como "no hay lugares cargados".
        state.geoError?.let {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "Podés marcar el punto en el mapa igual.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    // Mismo caso que la tarjeta de dirección, pero el que queda
                    // ilegible es el tema claro: el dorado sobre el rojo pálido
                    // del `errorContainer` da 1,7:1.
                    TextButton(
                        onClick = onRetryGeo,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) { Text("Reintentar") }
                }
            }
        }

        Dropdown(
            label = "País",
            options = state.countries,
            selected = state.countries.firstOrNull { it.id == state.countryId },
            optionLabel = { it.name },
            onSelect = { onCountry(it?.id) },
        )
        // Los tres rótulos salen del `type` que trae cada lista, no de un `if` por
        // país: en Chile este árbol es Región → Provincia → Comuna, y en CABA es
        // Comuna → Barrio. Ver [LocationLabels].
        Dropdown(
            label = state.etiquetaNivel1.nombre,
            options = state.provinces,
            selected = state.provinces.firstOrNull { it.id == state.provinceId },
            optionLabel = { it.name },
            onSelect = { onProvince(it?.id) },
            enabled = state.provinces.isNotEmpty(),
            placeholder = "Elegí un país primero",
        )
        Dropdown(
            label = state.etiquetaNivel2.nombre,
            options = state.departments,
            selected = state.departments.firstOrNull { it.id == state.departmentId },
            optionLabel = { it.name },
            onSelect = { onDepartment(it?.id) },
            enabled = state.departments.isNotEmpty(),
            placeholder = state.etiquetaNivel1.textoPrimero(),
        )
        Dropdown(
            label = state.etiquetaLocalidad.nombre,
            options = state.localities,
            selected = state.localities.firstOrNull { it.id == state.localityId },
            optionLabel = { it.name },
            onSelect = { onLocality(it?.id) },
            enabled = state.localities.isNotEmpty(),
            placeholder = state.etiquetaNivel2.textoPrimero(),
        )

        Text(
            text = if (state.latitude == null) "Tocá el mapa donde te la robaron"
            else "Arrastrá el marcador si no quedó justo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MapPicker(
            latitude = state.latitude,
            longitude = state.longitude,
            centerOn = state.centerOn,
            onPointChanged = onPointChanged,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                if (state.latitude != null && state.longitude != null) {
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
                        TextButton(onClick = onResolveAddress, enabled = !state.geocoding) {
                            Text(if (state.geocoding) "Buscando…" else "¿Qué dirección es?")
                        }
                        TextButton(onClick = onClearPoint) { Text("Quitar") }
                    }
                } else {
                    OutlinedButton(
                        onClick = onUseCurrentLocation,
                        enabled = !state.locating,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.locating) "Buscando…" else "📍 Estoy en el lugar")
                    }
                    Text(
                        text = "Marca el punto donde estás ahora. Si denunciás desde casa, " +
                            "mejor tocá el mapa en el lugar del robo.",
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

        // La dirección propuesta se confirma o se descarta. El punto ya viaja
        // desde que se marcó; la calle sólo si el usuario dice que sí.
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
                    // La localidad se muestra aparte y con su jerarquía porque es
                    // lo único que va a salir en el cartel público: el usuario
                    // tiene que poder ver que dice el partido correcto antes de
                    // aceptar. La calle, en cambio, sólo la ve él.
                    state.resolvedLocality?.let { locality ->
                        Text(
                            text = locality.fullName
                                ?: listOfNotNull(locality.name, locality.adminLevel1?.name)
                                    .joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // Un TextButton se pinta con `primary`, que acá es el mismo
                    // dorado del `primaryContainer` que tiene debajo: 1,4:1 de
                    // contraste, ilegible. Sobre un contenedor de color el texto
                    // lo fija el rol `on…` que le corresponde, igual que el resto
                    // de esta tarjeta.
                    val onContainer = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Row {
                        TextButton(onClick = onApplyAddress, colors = onContainer) {
                            Text("Usar esta dirección")
                        }
                        TextButton(onClick = onDiscardAddress, colors = onContainer) {
                            Text("Descartar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * El cierre del flujo.
 *
 * No tiene botón de descartar por afuera a propósito: la denuncia ya existe y el
 * PDF es lo que el usuario lleva a la policía. Cerrarlo sin querer con un toque
 * fuera del diálogo sería perder el único momento en que se ofrece.
 */
@Composable
private fun ReportCreatedDialog(
    modoEdicion: Boolean,
    generatingPdf: Boolean,
    pdfError: String?,
    onDownloadPdf: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (modoEdicion) "Denuncia corregida" else "Denuncia presentada") },
        text = {
            Column {
                Text(
                    // Al corregir, el PDF anterior ahora miente: cada PATCH lo
                    // marca como stale del lado del servidor, pero el que el
                    // usuario ya se bajó —y capaz le dio a la policía— sigue en
                    // su teléfono. Hay que decirlo, no esperar a que lo descubra.
                    text = if (modoEdicion) {
                        "El PDF que hayas descargado antes quedó desactualizado. Podés " +
                            "bajar el corregido ahora, o hacerlo después desde el panel."
                    } else {
                        "Ya está registrada y tu bicicleta figura como robada. " +
                            "Podés descargar el PDF para llevarlo a la policía o al seguro."
                    },
                    textAlign = TextAlign.Start,
                )
                pdfError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownloadPdf, enabled = !generatingPdf) {
                Text(
                    when {
                        generatingPdf -> "Generando…"
                        modoEdicion -> "Descargar PDF corregido"
                        else -> "Descargar PDF"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Ahora no") }
        },
    )
}

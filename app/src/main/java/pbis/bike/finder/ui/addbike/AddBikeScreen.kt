package pbis.bike.finder.ui.addbike

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pbis.bike.finder.ui.common.Dropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBikeScreen(
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddBikeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Con fotos fallidas NO se navega automáticamente: la bici quedó creada, y
    // sacar la pantalla de encima borraría el aviso antes de que se lea. El front
    // web resuelve lo mismo salteando la animación de éxito y esperando.
    LaunchedEffect(state.createdBikeId, state.photoWarning) {
        val id = state.createdBikeId
        if (id != null && state.photoWarning == null) onCreated(id)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Registrar bicicleta") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Cancelar") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        when {
            state.loadingCatalog -> Box(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }

            state.catalogError != null -> Box(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.catalogError!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Button(
                        onClick = viewModel::loadCatalog,
                        modifier = Modifier.padding(top = 16.dp),
                    ) { Text("Reintentar") }
                }
            }

            else -> Form(
                state = state,
                viewModel = viewModel,
                onContinue = { state.createdBikeId?.let(onCreated) },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun Form(
    state: AddBikeUiState,
    viewModel: AddBikeViewModel,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ModeSelector(state.mode, viewModel::setMode)

        Text(
            text = when (state.mode) {
                AddBikeMode.CATALOG ->
                    "Elegí tu bici del catálogo: los datos técnicos se completan solos."

                AddBikeMode.MANUAL ->
                    "Cargá los datos a mano. Usalo si tu bici no está en el catálogo."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Dropdown(
            label = "Marca",
            options = state.brands,
            selected = state.brands.firstOrNull { it.id == state.brandId },
            optionLabel = { it.name },
            onSelect = { viewModel.onBrandSelected(it?.id) },
            error = state.fieldErrors[AddBikeViewModel.FIELD_BRAND],
        )

        Dropdown(
            label = if (state.mode == AddBikeMode.CATALOG) "Tipo (opcional, filtra)" else "Tipo",
            options = state.bikeTypes,
            selected = state.bikeTypes.firstOrNull { it.id == state.bikeTypeId },
            optionLabel = { it.name },
            onSelect = { viewModel.onBikeTypeSelected(it?.id) },
        )

        when (state.mode) {
            AddBikeMode.CATALOG -> CatalogFields(state, viewModel)
            AddBikeMode.MANUAL -> ManualFields(state, viewModel)
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        OutlinedTextField(
            value = state.serialNumber,
            onValueChange = viewModel::onSerialNumberChange,
            label = { Text("Número de serie") },
            supportingText = {
                // No es un campo más: es el dato que permite identificar la bici
                // si aparece. Vale la pena decírselo al usuario.
                Text("Es lo que más ayuda a recuperarla si te la roban.")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = state.notes,
            onValueChange = viewModel::onNotesChange,
            label = { Text("Notas") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        PhotoSection(
            photos = state.photos,
            gpsConsent = state.gpsAnalysisConsent,
            onPhotosPicked = viewModel::onPhotosPicked,
            onPhotoRemoved = viewModel::onPhotoRemoved,
            onGpsConsentChanged = viewModel::onGpsConsentChanged,
        )

        state.photoWarning?.let { warning ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text("Continuar") }
                }
            }
        }

        state.formError?.let { error ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Button(
            onClick = viewModel::submit,
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when {
                state.uploadingPhotos -> Text("Subiendo fotos…")
                state.submitting -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )

                else -> Text("Registrar bicicleta")
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: AddBikeMode, onChange: (AddBikeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = mode == AddBikeMode.CATALOG,
            onClick = { onChange(AddBikeMode.CATALOG) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("Desde catálogo") }

        SegmentedButton(
            selected = mode == AddBikeMode.MANUAL,
            onClick = { onChange(AddBikeMode.MANUAL) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("Manual") }
    }
}

@Composable
private fun CatalogFields(state: AddBikeUiState, viewModel: AddBikeViewModel) {
    Dropdown(
        label = "Modelo",
        options = state.models,
        selected = state.models.firstOrNull { it.id == state.catalogBikeId },
        optionLabel = { bike ->
            listOfNotNull(bike.modelName, bike.modelYear?.let { "($it)" }).joinToString(" ")
        },
        onSelect = { viewModel.onModelSelected(it?.id) },
        enabled = state.brandId != null && !state.loadingModels,
        placeholder = when {
            state.brandId == null -> "Primero elegí una marca"
            state.loadingModels -> "Cargando modelos…"
            state.models.isEmpty() -> "Esta marca no tiene modelos cargados"
            else -> "Seleccionar…"
        },
        error = state.fieldErrors[AddBikeViewModel.FIELD_MODEL],
    )

    if (state.colorways.isNotEmpty()) {
        Dropdown(
            label = "Combinación de colores",
            options = state.colorways,
            selected = state.colorways.firstOrNull { it.id == state.colorwayId },
            optionLabel = { it.colorwayName ?: it.colorwayCode ?: "Sin nombre" },
            onSelect = { viewModel.onColorwaySelected(it?.id) },
        )
    }

    // Los talles del catálogo salen del modelo elegido, no del sistema de talles
    // del tipo: son los que ese modelo realmente tuvo.
    SizeDropdown(
        sizes = state.availableSizes,
        selected = state.frameSize,
        enabled = state.catalogBikeId != null,
        onSelect = viewModel::onFrameSizeSelected,
        emptyPlaceholder = "Primero elegí un modelo",
    )
}

@Composable
private fun ManualFields(state: AddBikeUiState, viewModel: AddBikeViewModel) {
    OutlinedTextField(
        value = state.manualModel,
        onValueChange = viewModel::onManualModelChange,
        label = { Text("Modelo") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = state.manualYear,
        onValueChange = viewModel::onManualYearChange,
        label = { Text("Año") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Dropdown(
        label = "Color principal",
        options = state.colors,
        selected = state.colors.firstOrNull { it.id == state.primaryColorId },
        optionLabel = { it.nameEs ?: it.name },
        onSelect = { viewModel.onPrimaryColorSelected(it?.id) },
        error = state.fieldErrors[AddBikeViewModel.FIELD_COLOR],
    )

    OutlinedTextField(
        value = state.primaryColorCustom,
        onValueChange = viewModel::onPrimaryColorCustomChange,
        label = { Text("…o un color personalizado") },
        supportingText = { Text("Si escribís acá, se descarta el color de la lista.") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    // En el alta manual no hay modelo de catálogo, así que los talles salen del
    // sistema de talles del tipo de bici elegido.
    SizeDropdown(
        sizes = state.manualSizes,
        selected = state.frameSize,
        enabled = state.bikeTypeId != null,
        onSelect = viewModel::onFrameSizeSelected,
        emptyPlaceholder = "Primero elegí un tipo",
    )
}

@Composable
private fun SizeDropdown(
    sizes: List<pbis.bike.finder.data.remote.dto.FrameSizeDto>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    emptyPlaceholder: String,
) {
    Dropdown(
        label = "Talle",
        options = sizes,
        selected = sizes.firstOrNull { it.sizeCode == selected },
        optionLabel = { size ->
            val label = size.sizeLabel ?: size.sizeCode
            val hint = if (size.riderHeightMinCm != null && size.riderHeightMaxCm != null) {
                " (${size.riderHeightMinCm}-${size.riderHeightMaxCm}cm)"
            } else {
                ""
            }
            "$label$hint"
        },
        onSelect = { onSelect(it?.sizeCode) },
        enabled = enabled && sizes.isNotEmpty(),
        placeholder = if (sizes.isEmpty()) emptyPlaceholder else "Seleccionar…",
    )
}

package pbis.bike.finder.ui.updatecomponents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Alta y edición de los componentes de una bici — `actualizar-componentes.html`.
 *
 * Quince secciones plegables, una por pieza. Las que ya tienen datos aparecen
 * abiertas y marcadas; el resto arranca plegado, que es lo que hace que una
 * lista de quince formularios se pueda recorrer en un teléfono.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateComponentsScreen(
    bikeId: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UpdateComponentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(bikeId) { viewModel.start(bikeId) }

    // Guardar termina volviendo a la pantalla anterior, igual que la web, que
    // muestra el toast y recién después navega. El aviso va antes del salto para
    // que se alcance a leer.
    LaunchedEffect(state.saved) {
        if (!state.saved) return@LaunchedEffect
        snackbarHostState.showSnackbar("Componentes actualizados")
        viewModel.onSavedHandled()
        onSaved()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Componentes")
                        state.bikeName?.let {
                            Text(
                                text = listOfNotNull(it, state.bikeSubtitle).joinToString(" · "),
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.loadError != null -> LoadErrorState(
                    message = state.loadError!!,
                    canRetry = state.canRetryLoad,
                    onRetry = viewModel::load,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> ComponentsForm(
                    state = state,
                    viewModel = viewModel,
                    onCancel = onBack,
                )
            }
        }
    }
}

@Composable
private fun ComponentsForm(
    state: UpdateComponentsUiState,
    viewModel: UpdateComponentsViewModel,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Cargá lo que sepas de cada pieza. Lo que dejes vacío queda como está.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BIKE_COMPONENT_FIELDS.forEach { field ->
            ComponentSection(
                field = field,
                entry = state.entry(field.key),
                expanded = field.key in state.expanded,
                prefilled = field.key in state.prefilled,
                onToggle = { viewModel.toggleSection(field.key) },
                onBrandChange = { viewModel.onBrandChange(field.key, it) },
                onModelChange = { viewModel.onModelChange(field.key, it) },
                onNotesChange = { viewModel.onNotesChange(field.key, it) },
            )
        }

        state.saveError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
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
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(if (state.saving) "Guardando…" else "Guardar")
            }
        }
    }
}

/**
 * Una pieza: cabecera siempre visible, campos desplegables.
 *
 * La marca de "cargado" va en la cabecera y no adentro porque plegada es la
 * única parte que se ve, y sin ella una bici con doce piezas cargadas se ve
 * igual que una vacía.
 */
@Composable
private fun ComponentSection(
    field: ComponentField,
    entry: ComponentEntry,
    expanded: Boolean,
    prefilled: Boolean,
    onToggle: () -> Unit,
    onBrandChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Plegada, la sección igual dice qué tiene: sin esto hay que
                    // abrir las quince para saber qué se cargó.
                    if (!expanded && !entry.isBlank) {
                        Text(
                            text = listOf(entry.brand, entry.model)
                                .filter { it.isNotBlank() }
                                .joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (prefilled) {
                    Text(
                        text = "Cargado",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }

                TextButton(onClick = onToggle) { Text(if (expanded) "Ocultar" else "Editar") }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = entry.brand,
                        onValueChange = onBrandChange,
                        label = { Text("Marca") },
                        placeholder = { Text(field.brandHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = entry.model,
                        onValueChange = onModelChange,
                        label = { Text("Modelo") },
                        placeholder = { Text(field.modelHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = entry.notes,
                        onValueChange = onNotesChange,
                        label = { Text("Notas") },
                        placeholder = { Text(field.notesHint) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadErrorState(
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

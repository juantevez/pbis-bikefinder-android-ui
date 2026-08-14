package pbis.bike.finder.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pbis.bike.finder.data.remote.dto.BicicletaResumenDto

/**
 * Hub del usuario autenticado, equivalente a `dashboard.html` del front web.
 *
 * No tiene lógica propia: pinta el resumen del agregador y ofrece las tarjetas
 * que llevan al resto de la app. La regla que ordena la pantalla es que
 * **la grilla nunca depende del resumen**: si el agregador falla, los números
 * muestran el error y las tarjetas siguen andando. En el front web ese fallo se
 * llevaba puesta media pantalla, porque la lista de bicis alimentaba también los
 * selectores.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAddBike: () -> Unit,
    onMyBikes: () -> Unit,
    onUpdateComponents: (String) -> Unit,
    onReportTheft: (String) -> Unit,
    onMyReports: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Los números tienen que estar frescos al volver de registrar una bici.
    LifecycleResumeEffect(Unit) {
        viewModel.loadSummary()
        onPauseOrDispose { }
    }

    // Qué acción está esperando que se elija una bici. Null = no hay selector
    // abierto. Se guarda la acción y no un booleano porque el mismo selector
    // sirve a dos tarjetas con destinos distintos.
    var pickerFor by remember { mutableStateOf<BikeAction?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BikeFinder")
                        state.userName?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::logout) { Text("Salir") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatsStrip(state, onRetry = viewModel::loadSummary) }

            item {
                Text(
                    text = "¿Qué querés hacer?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item {
                ActionCard(
                    number = "01",
                    title = "Registrar nueva bicicleta",
                    subtitle = "Cargá marca, modelo, número de serie y fotos",
                    onClick = onAddBike,
                )
            }
            item {
                ActionCard(
                    number = "02",
                    title = "Ver mis bicicletas",
                    subtitle = "El listado completo, con su estado",
                    onClick = onMyBikes,
                )
            }
            item {
                ActionCard(
                    number = "03",
                    title = "Actualizar componentes",
                    subtitle = "Cambiaste ruedas, grupo o cuadro",
                    onClick = { pickerFor = BikeAction.UpdateComponents },
                )
            }
            item {
                ActionCard(
                    number = "04",
                    title = "Me robaron la bici",
                    // El flujo del front web pasa primero por el plan de búsqueda
                    // y recién después por la denuncia. No es un rodeo: el plan
                    // define qué alcance tiene la búsqueda que se va a publicar.
                    subtitle = "Elegí el plan de búsqueda y hacé la denuncia",
                    onClick = { pickerFor = BikeAction.ReportTheft },
                )
            }
            item {
                ActionCard(
                    number = "05",
                    title = "Mis denuncias",
                    subtitle = "Los PDF de cada denuncia y las pistas recibidas",
                    onClick = onMyReports,
                )
            }
            item {
                ActionCard(
                    number = "06",
                    title = "Vendí mi bici",
                    subtitle = "Darla de baja del registro",
                    enabled = false,
                )
            }

            item { Box(Modifier.height(8.dp).navigationBarsPadding()) }
        }
    }

    pickerFor?.let { action ->
        BikePicker(
            title = action.pickerTitle,
            bikes = state.bicicletas,
            loading = state.loadingSummary,
            error = state.summaryError,
            onDismiss = { pickerFor = null },
            onPick = { bikeId ->
                pickerFor = null
                when (action) {
                    BikeAction.UpdateComponents -> onUpdateComponents(bikeId)
                    BikeAction.ReportTheft -> onReportTheft(bikeId)
                }
            },
        )
    }
}

/** Las dos tarjetas que necesitan saber sobre qué bicicleta se opera. */
private enum class BikeAction(val pickerTitle: String) {
    UpdateComponents("¿A qué bicicleta le cambiaste componentes?"),
    ReportTheft("¿Qué bicicleta te robaron?"),
}

/**
 * Los tres números del agregador.
 *
 * El cuarto del front web —"estado de cuenta"— no se porta: el backend lo tiene
 * hardcodeado en "Activa", así que es un cartel que dice siempre lo mismo.
 */
@Composable
private fun StatsStrip(state: DashboardUiState, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Stat("Bicicletas", state.totalBicicletas)
                Stat("Componentes", state.totalComponentes)
                Stat("Reportes activos", state.totalReportesActivos)
            }

            if (state.summaryError != null) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    text = state.summaryError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.canRetrySummary) {
                    TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Reintentar")
                    }
                }
            }
        }
    }
}

/**
 * Un número del encabezado.
 *
 * Arranca en "—" mientras carga, igual que el front web: un 0 provisorio se lee
 * como un dato y le diría al usuario que no tiene bicicletas registradas.
 */
@Composable
private fun Stat(label: String, value: Int?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value?.toString() ?: "—",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionCard(
    number: String,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // El estado de "todavía no está" va escrito, no insinuado con un gris:
            // una tarjeta apagada sin explicación se lee como un error de la app.
            if (!enabled) {
                Text(
                    text = "Próximamente",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Selector de bicicleta.
 *
 * Se alimenta del resumen y no de un GET propio: el agregador ya trajo la lista.
 * Por eso acá sí hay que contemplar que el resumen haya fallado — es el único
 * lugar de la pantalla donde ese fallo bloquea algo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BikePicker(
    title: String,
    bikes: List<BicicletaResumenDto>,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            when {
                loading -> CircularProgressIndicator(
                    Modifier.padding(vertical = 24.dp).align(Alignment.CenterHorizontally),
                )

                error != null -> Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )

                bikes.isEmpty() -> Text(
                    text = "Todavía no tenés bicicletas registradas.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )

                // Lista simple y no `LazyColumn`: son las bicicletas de una
                // persona, no un feed. Un scroll perezoso dentro de una hoja
                // modal pelea con el gesto de arrastre de la hoja.
                else -> Column(Modifier.padding(vertical = 8.dp)) {
                    bikes.forEach { bike ->
                        BikeRow(bike = bike, onClick = { onPick(bike.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BikeRow(bike: BicicletaResumenDto, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                text = listOfNotNull(bike.marca, bike.modelo)
                    .joinToString(" ")
                    .ifBlank { "Bicicleta sin marca" },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val detail = listOfNotNull(
                bike.anio,
                "${bike.totalComponentes} componentes",
            ).joinToString(" · ")
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

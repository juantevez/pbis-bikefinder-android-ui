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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pbis.bike.finder.data.remote.dto.BicicletaResumenDto
import pbis.bike.finder.data.remote.dto.BicycleStatus
import pbis.bike.finder.ui.common.BikeFinderTopBar
import pbis.bike.finder.ui.common.UserNameLine

/**
 * Hub del usuario autenticado, equivalente a `dashboard.html` del front web.
 *
 * No tiene lógica propia: ofrece las tarjetas que llevan al resto de la app. La
 * regla que ordena la pantalla es que **la grilla nunca depende del resumen**:
 * si el agregador falla, las tarjetas siguen andando. En el front web ese fallo
 * se llevaba puesta media pantalla, porque la lista de bicis alimentaba también
 * los selectores.
 *
 * La tira de números del agregador —bicicletas, componentes, reportes activos—
 * no está más. Los dos primeros eran el conteo de la lista que el selector ya
 * muestra, y el tercero no llegaba a justificar la tira él solo. El resumen se
 * sigue pidiendo igual: es de donde sale la lista de bicis de los selectores,
 * que es su uso real.
 *
 * El orden de las tarjetas no es el de la web ni el de antes. Arriba de todo va
 * la denuncia de robo, que es lo único que se hace con urgencia y a menudo desde
 * la vereda donde estaba la bici; el resto son tareas de escritorio y pueden
 * esperar un scroll. Registrar una bici se fue al botón flotante: estaba dos
 * veces en la misma pantalla, como tarjeta y como acción del listado.
 */
@Composable
fun DashboardScreen(
    onAddBike: () -> Unit,
    onMyBikes: () -> Unit,
    onUpdateComponents: (String) -> Unit,
    onReportTheft: (String) -> Unit,
    onMyReports: () -> Unit,
    onProfile: () -> Unit,
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
            BikeFinderTopBar(
                userName = state.userName,
                userEmail = state.userEmail,
                onProfile = onProfile,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddBike,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Registrar bici", modifier = Modifier.padding(start = 8.dp))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // El padding inferior deja pasar el botón flotante: sin esto la
            // última tarjeta queda tapada.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { UserNameLine(state.userName) }

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
                EmergencyCard(
                    // El flujo del front web pasa primero por el plan de búsqueda
                    // y recién después por la denuncia. No es un rodeo: el plan
                    // define qué alcance tiene la búsqueda que se va a publicar,
                    // y es lo único que justifica mandar a alguien apurado a una
                    // pantalla de pago. Por eso ésta es la única tarjeta que
                    // conserva su bajada.
                    subtitle = "Elegí el plan de búsqueda y hacé la denuncia",
                    onClick = { pickerFor = BikeAction.ReportTheft },
                )
            }
            item {
                ActionCard(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "Ver mis bicicletas",
                    onClick = onMyBikes,
                )
            }
            item {
                ActionCard(
                    icon = Icons.Default.Build,
                    title = "Actualizar componentes",
                    onClick = { pickerFor = BikeAction.UpdateComponents },
                )
            }
            item {
                ActionCard(
                    icon = Icons.Default.Info,
                    title = "Mis denuncias",
                    onClick = onMyReports,
                )
            }

            item { Box(Modifier.height(8.dp).navigationBarsPadding()) }
        }
    }

    pickerFor?.let { action ->
        val elegibles = state.bicicletas.filter(action::admite)

        BikePicker(
            title = action.pickerTitle,
            bikes = elegibles,
            loading = state.loadingSummary,
            error = state.summaryError,
            canRetry = state.canRetrySummary,
            emptyMessage = action.emptyMessage,
            onRetry = viewModel::loadSummary,
            onDismiss = { pickerFor = null },
            onPick = { bike ->
                pickerFor = null
                when (action) {
                    BikeAction.UpdateComponents -> onUpdateComponents(bike.id)
                    BikeAction.ReportTheft -> onReportTheft(bike.id)
                }
            },
        )
    }
}

/**
 * Las tarjetas que necesitan saber sobre qué bicicleta se opera.
 *
 * Cada una filtra por su cuenta porque las reglas del backend son distintas, y
 * ofrecer una bici que el servidor va a rechazar es peor que no ofrecerla:
 *
 *  - **Robo**: sólo `ACTIVE`. `canBeReportedStolen()` lo exige, así que listar
 *    una ya `STOLEN` invita a abrir una segunda denuncia sobre la misma bici.
 *  - **Componentes**: sólo `ACTIVE`, que es el único estado editable.
 *
 * La baja no está más acá: dejó de ser una tarjeta del dashboard y pasó a vivir
 * sobre cada bici del listado — ver `puedeDarseDeBaja`, que es donde quedó su
 * regla, más laxa que estas dos.
 *
 * El agregador hoy sólo devuelve `ACTIVE` y `STOLEN`, así que en la práctica
 * estos filtros casi no recortan nada. Están igual: el criterio vive acá y no en
 * lo que el backend haya decidido mandar en esta versión.
 */
// `internal` y no `private`: el criterio de [BikeAction.admite] es una regla del
// backend, no una decisión de layout, y se prueba sola.
internal enum class BikeAction(val pickerTitle: String, val emptyMessage: String) {
    UpdateComponents(
        "¿A qué bicicleta le cambiaste componentes?",
        "No tenés bicicletas activas para editar.",
    ),
    ReportTheft(
        "¿Qué bicicleta te robaron?",
        "No tenés bicicletas activas para denunciar.",
    ),
    ;

    /** El `estado` del resumen llega como texto suelto: lo que no se reconoce no pasa. */
    fun admite(bike: BicicletaResumenDto): Boolean {
        val estado = bike.estado?.let { raw ->
            BicycleStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        } ?: return false

        return estado == BicycleStatus.ACTIVE
    }
}

/**
 * "Me robaron la bici", en bloque lleno.
 *
 * Es la única tarjeta que no comparte el fondo de las demás. La diferencia de
 * color no es jerarquía visual por gusto: quien entra a la app para denunciar un
 * robo acaba de perder algo y busca una sola cosa. Un bloque de color distinto
 * se encuentra sin leer, que es más rápido que leer cinco títulos parecidos.
 */
@Composable
private fun EmergencyCard(subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp).padding(end = 4.dp),
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    text = "Me robaron la bici",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    // Sobre el bloque lleno no hay `onSurfaceVariant` que valga:
                    // el gris de las bajadas normales no tiene contraste contra
                    // el color de marca. Se atenúa el propio `onPrimary`.
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                )
            }
        }
    }
}

/**
 * Una tarjeta común de la grilla.
 *
 * Sin bajada y sin número de orden: los cinco subtítulos anteriores explicaban
 * lo que el título ya decía y estiraban la lista hasta obligar a scrollear para
 * ver la última opción. El ícono ocupa el lugar donde estaba el número, que
 * enumeraba pasos de una secuencia que no existe.
 */
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit = {},
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                // El ícono repite lo que dice el título de al lado; anunciarlo
                // otra vez sólo hace más largo el recorrido con lector de
                // pantalla.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 20.dp),
            )
        }
    }
}

/**
 * Selector de bicicleta.
 *
 * Se alimenta del resumen y no de un GET propio: el agregador ya trajo la lista.
 * Por eso acá sí hay que contemplar que el resumen haya fallado — desde que no
 * está la tira de números, éste es el único lugar de la pantalla donde ese fallo
 * se ve, y el único donde bloquea algo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BikePicker(
    title: String,
    bikes: List<BicicletaResumenDto>,
    loading: Boolean,
    error: String?,
    canRetry: Boolean,
    emptyMessage: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (BicicletaResumenDto) -> Unit,
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

                error != null -> Column(Modifier.padding(vertical = 24.dp)) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // El reintento vivia en la tira de numeros del dashboard. Al
                    // sacarla, este es el unico lugar donde el fallo del resumen
                    // se ve, asi que tambien tiene que ser el lugar donde se
                    // sale de el: sin esto la unica salida es cerrar la app,
                    // porque el resumen solo se recarga en el onResume.
                    if (canRetry) {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text("Reintentar")
                        }
                    }
                }

                // El texto lo pone la acción: "no tenés bicicletas" y "no tenés
                // ninguna que sirva para esto" son cosas distintas, y con el
                // filtro por estado la segunda es la que suele pasar.
                bikes.isEmpty() -> Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )

                // Lista simple y no `LazyColumn`: son las bicicletas de una
                // persona, no un feed. Un scroll perezoso dentro de una hoja
                // modal pelea con el gesto de arrastre de la hoja.
                else -> Column(Modifier.padding(vertical = 8.dp)) {
                    bikes.forEach { bike ->
                        BikeRow(bike = bike, onClick = { onPick(bike) })
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

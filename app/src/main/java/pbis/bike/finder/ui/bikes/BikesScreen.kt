package pbis.bike.finder.ui.bikes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pbis.bike.finder.data.remote.dto.BicycleStatus
import pbis.bike.finder.data.remote.dto.BicycleSummaryDto
import pbis.bike.finder.ui.common.BikeFinderTopBar
import pbis.bike.finder.ui.common.DeregisterBikeDialog
import pbis.bike.finder.ui.common.UserNameLine

/** El nombre visible de una bici, que es lo que se le muestra al usuario. */
internal fun BicycleSummaryDto.nombreVisible(): String =
    listOfNotNull(brandName, model).joinToString(" ").ifBlank { "Bicicleta sin marca" }

@Composable
fun BikesScreen(
    onBikeClick: (String) -> Unit,
    onAddBike: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BikesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Recarga al volver a la pantalla, para que la bici recién registrada
    // aparezca sin que el usuario tenga que tirar de la lista. Sirve también al
    // volver del detalle, que es el otro lugar desde donde se da de baja.
    LifecycleResumeEffect(Unit) {
        viewModel.load()
        onPauseOrDispose { }
    }

    // La bici deslizada, esperando confirmación.
    var confirming by remember { mutableStateOf<BicycleSummaryDto?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // La baja no cambia de pantalla: sin aviso, lo único visible sería que una
    // fila desapareció, que es exactamente lo que pasa cuando algo sale mal.
    LaunchedEffect(state.deregistered) {
        val message = state.deregistered ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.onDeregisteredShown()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            ) { Text("Registrar bici") }
        },
    ) { padding ->
        // El encabezado va afuera del `when`: el título de la pantalla no puede
        // desaparecer porque la lista esté cargando o haya fallado.
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                UserNameLine(state.userName)
                Text(
                    text = "Mis bicicletas",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.error != null -> ErrorState(
                        message = state.error!!,
                        canRetry = state.canRetry,
                        onRetry = viewModel::load,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    state.bikes.isEmpty() -> EmptyState(Modifier.align(Alignment.Center))

                    else -> LazyColumn(
                        // El padding inferior deja pasar el botón flotante. Sin
                        // esto la última tarjeta queda tapada: se ve al final de
                        // una lista real, no en un preview con dos elementos.
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 88.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.bikes, key = { it.id }) { bike ->
                            SwipeableBikeCard(
                                bike = bike,
                                onClick = { onBikeClick(bike.id) },
                                onDeregisterRequest = { confirming = bike },
                            )
                        }
                    }
                }
            }
        }
    }

    confirming?.let { bike ->
        DeregisterBikeDialog(
            bikeName = bike.nombreVisible(),
            onConfirm = {
                confirming = null
                viewModel.deregister(bike.id)
            },
            onDismiss = { confirming = null },
        )
    }

    state.deregisterError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDeregisterError,
            title = { Text("No se pudo dar de baja") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissDeregisterError) { Text("Entendido") }
            },
        )
    }
}

/**
 * La tarjeta de una bici, con el gesto de baja.
 *
 * **El deslizamiento no da de baja nada por sí solo**: pide confirmación y la
 * fila vuelve a su lugar. Por eso `confirmValueChange` devuelve siempre `false`
 * — es lo que le dice al gesto que no complete el descarte. Dejar que la fila se
 * fuera y confirmar después obligaría a reponerla al cancelar, y a mostrar
 * durante un rato una lista que no es la que el backend tiene.
 *
 * El gesto es un atajo, nunca el único camino: es invisible hasta que alguien lo
 * descubre, y con lector de pantalla no existe. La misma baja está escrita como
 * botón en el detalle de la bici, que es adonde lleva un tap sobre esta tarjeta.
 *
 * Sólo se habilita sobre las bicis que el backend admite dar de baja: deslizar y
 * que no pase nada se lee como que la app se colgó.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableBikeCard(
    bike: BicycleSummaryDto,
    onClick: () -> Unit,
    onDeregisterRequest: () -> Unit,
) {
    val habilitado = bike.puedeDarseDeBaja()

    // `rememberUpdatedState` porque `confirmValueChange` queda capturada dentro
    // del estado del gesto: sin esto seguiría llamando a la lambda de la primera
    // composición, con la bici que ocupaba esta posición en ese momento.
    val pedirBaja by rememberUpdatedState(onDeregisterRequest)

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) pedirBaja()
            false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = habilitado,
        backgroundContent = { DeregisterBackground() },
    ) {
        BikeCard(bike = bike, onClick = onClick)
    }
}

/** Lo que asoma detrás de la tarjeta mientras se desliza. */
@Composable
private fun DeregisterBackground() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(CardDefaults.shape)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        // El texto va escrito y no sólo el tacho: un ícono de basura sobre una
        // lista se lee como "borrar", y esto no borra la bici — la saca del
        // registro del usuario, que es otra cosa y es reclamable después.
        Text(
            text = "Dar de baja",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun BikeCard(bike: BicycleSummaryDto, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = bike.nombreVisible(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val detail = listOfNotNull(
                        bike.year?.toString(),
                        bike.primaryColor,
                    ).joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                bike.status?.let { StatusBadge(it) }
            }

            bike.serialNumber?.let {
                Text(
                    text = "Serie $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Estado de la bici.
 *
 * `STOLEN` va en rojo y no como un chip más: es la diferencia entre una bici que
 * el usuario tiene y una que le robaron, y en esta app esa distinción es el
 * punto de todo.
 */
@Composable
private fun StatusBadge(status: BicycleStatus) {
    val (label, background, foreground) = when (status) {
        BicycleStatus.ACTIVE -> Triple(
            "Activa",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

        BicycleStatus.STOLEN -> Triple(
            "Robada",
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onError,
        )

        BicycleStatus.SOLD -> Triple(
            "Vendida",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BicycleStatus.INACTIVE -> Triple(
            "Inactiva",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Todavía no registraste ninguna bicicleta",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Usá el botón de abajo para registrar la primera.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
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
        // El botón sólo aparece cuando reintentar no puede duplicar nada. En un
        // listado siempre puede, pero la regla se respeta desde el principio
        // para que no haya que acordarse en las pantallas que sí escriben.
        if (canRetry) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Reintentar")
            }
        }
    }
}

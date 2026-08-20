package pbis.bike.finder.ui.tips

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pbis.bike.finder.data.remote.dto.TipDto
import pbis.bike.finder.data.remote.dto.TipStatsDto
import pbis.bike.finder.data.remote.dto.TipStatus
import pbis.bike.finder.ui.common.formatLongDate

/**
 * Las pistas recibidas por una denuncia — `tips-list.html`.
 *
 * Se llega desde "Mis denuncias". Cada tarjeta abre el detalle, que es donde
 * están las acciones y la conversación con el informante.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipsListScreen(
    reportId: String,
    onTipClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TipsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(reportId) { viewModel.start(reportId) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Pistas recibidas") },
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

                state.error != null -> ErrorState(
                    message = state.error!!,
                    canRetry = state.canRetry,
                    onRetry = viewModel::load,
                    modifier = Modifier.align(Alignment.Center),
                )

                state.tips.isEmpty() -> EmptyState(Modifier.align(Alignment.Center))

                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.stats?.let { stats ->
                        item(key = "stats") { StatsBar(stats) }
                    }
                    items(state.tips, key = { it.id }) { tip ->
                        TipCard(tip = tip, onClick = { onTipClick(tip.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsBar(stats: TipStatsDto) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Stat("Total", stats.total, Modifier.weight(1f))
        Stat("Sin leer", stats.unread, Modifier.weight(1f))
        Stat("Respondidas", stats.replied, Modifier.weight(1f))
        Stat("Convertidas", stats.converted, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TipCard(tip: TipDto, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            // Una pista sin leer se destaca del resto: puede ser la bici.
            containerColor = if (tip.isUnread) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = listOfNotNull(
                        tip.sightingDate?.let { formatLongDate(it) },
                        tip.sightingTimeApprox,
                    ).joinToString(" · ").ifBlank { "Sin fecha" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                tip.status?.let { StatusBadge(it) }
            }

            tip.locationText()?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = tip.description?.takeIf { it.isNotBlank() } ?: "(sin descripción)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
            )

            // Que el informante quiera respuesta es la señal más accionable de
            // la tarjeta: hay alguien del otro lado esperando.
            if (tip.canReply) {
                Text(
                    text = "Quiere respuesta",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun StatusBadge(status: TipStatus) {
    val (background, foreground) = when (status) {
        TipStatus.NEW -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        TipStatus.READ ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant

        TipStatus.REPLIED ->
            MaterialTheme.colorScheme.secondaryContainer to
                MaterialTheme.colorScheme.onSecondaryContainer

        TipStatus.CONVERTED_TO_SIGHTING ->
            MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
    }

    Text(
        text = status.label,
        style = MaterialTheme.typography.labelSmall,
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Sin pistas todavía",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Cuando alguien escanee el QR del cartel y mande información, " +
                "aparece acá.",
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
        if (canRetry) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text("Reintentar")
            }
        }
    }
}

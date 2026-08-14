package pbis.bike.finder.ui.reports

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pbis.bike.finder.data.remote.dto.ReportStatus

/**
 * Las denuncias ya hechas.
 *
 * Dos pestañas sobre la **misma** lista, como en el front web: en "Mis reportes"
 * cada denuncia ofrece sus dos PDFs; en "Pistas", los avistamientos que llegaron.
 *
 * Es el único lugar de la app donde se puede volver a un PDF después de cerrar
 * la denuncia, y el único desde donde se genera el público.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyReportsScreen(
    onViewTips: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MyReportsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
                title = { Text("Mis denuncias") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                ReportsTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }

            when {
                state.loading -> Centered { CircularProgressIndicator() }

                state.error != null -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = viewModel::load) { Text("Reintentar") }
                    }
                }

                state.isEmpty -> Centered {
                    Text(
                        text = "Todavía no denunciaste ningún robo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.pdfError?.let { error ->
                        item {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    items(state.reports, key = { it.id }) { report ->
                        ReportCard(
                            report = report,
                            tab = state.tab,
                            generating = state.generatingFor == report.id,
                            onPrivatePdf = { viewModel.downloadPdf(report.id, publicVersion = false) },
                            onPublicPdf = { viewModel.downloadPdf(report.id, publicVersion = true) },
                            onViewTips = { onViewTips(report.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ReportCard(
    report: ReportRow,
    tab: ReportsTab,
    generating: Boolean,
    onPrivatePdf: () -> Unit,
    onPublicPdf: () -> Unit,
    onViewTips: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
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
                        text = report.bikeLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = report.theftDate?.let { "Robada el $it" } ?: "Sin fecha",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(report.status)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                if (tab == ReportsTab.REPORTS) {
                    // Los dos documentos, juntos y con la diferencia dicha: uno
                    // lleva calle, hora y contacto; el otro es para repartir.
                    OutlinedButton(
                        onClick = onPrivatePdf,
                        enabled = !generating,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (generating) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text("PDF privado")
                        }
                    }
                    Button(
                        onClick = onPublicPdf,
                        enabled = !generating,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cartel + QR")
                    }
                } else {
                    OutlinedButton(onClick = onViewTips, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (report.unreadTips > 0) {
                                "Ver pistas (${report.unreadTips} sin leer)"
                            } else {
                                "Ver pistas"
                            },
                        )
                    }
                }
            }

            if (tab == ReportsTab.REPORTS) {
                Text(
                    text = "El privado lleva la dirección y tu contacto: es el que va a la " +
                        "policía. El cartel omite todo eso y sirve para compartir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ReportStatus?) {
    val (label, color) = when (status) {
        ReportStatus.ACTIVE -> "Activa" to MaterialTheme.colorScheme.primary
        ReportStatus.FOUND -> "Recuperada" to MaterialTheme.colorScheme.tertiary
        ReportStatus.CLOSED -> "Cerrada" to MaterialTheme.colorScheme.onSurfaceVariant
        null -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

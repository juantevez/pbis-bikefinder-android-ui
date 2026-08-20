package pbis.bike.finder.ui.bikedetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import pbis.bike.finder.data.remote.dto.BicycleDto
import pbis.bike.finder.data.remote.dto.BicycleStatus
import pbis.bike.finder.ui.common.formatLongDate

/**
 * Detalle de una bicicleta — el modal de `ver-bici.html`.
 *
 * Es también el lugar desde donde se entra a las dos acciones sobre una bici:
 * actualizar sus componentes y denunciar el robo. En la web esos dos botones
 * viven en el pie del modal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BikeDetailScreen(
    bikeId: String,
    onUpdateComponents: (String) -> Unit,
    onReportTheft: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BikeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(bikeId) { viewModel.start(bikeId) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title)
                        state.bikeTypeName?.let {
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            val bike = state.bike
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                state.error != null -> ErrorState(
                    message = state.error!!,
                    canRetry = state.canRetry,
                    onRetry = viewModel::load,
                    modifier = Modifier.align(Alignment.Center),
                )

                bike != null -> DetailContent(
                    bike = bike,
                    state = state,
                    onPhotoClick = viewModel::openLightbox,
                    onUpdateComponents = { onUpdateComponents(bikeId) },
                    onReportTheft = { onReportTheft(bikeId) },
                )
            }
        }
    }

    state.lightbox?.let { photo ->
        Dialog(onDismissRequest = viewModel::closeLightbox) {
            AsyncImage(
                model = photo.url,
                contentDescription = photo.description ?: "Foto de la bicicleta",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = viewModel::closeLightbox),
            )
        }
    }
}

@Composable
private fun DetailContent(
    bike: BicycleDto,
    state: BikeDetailUiState,
    onPhotoClick: (BikePhoto) -> Unit,
    onUpdateComponents: () -> Unit,
    onReportTheft: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        bike.status?.let { StatusBadge(it) }

        PhotoGallery(
            photos = state.photos,
            failed = state.photosFailed,
            onPhotoClick = onPhotoClick,
        )

        DetailSection("Cuadro") {
            DetailRow("Marca", bike.frame?.brandName)
            DetailRow("Modelo", bike.frame?.model)
            DetailRow("Año", bike.frame?.year?.toString())
            DetailRow("Talle", bike.frame?.size)
            // El número de serie es el dato con el que la policía identifica una
            // bici recuperada: va en monoespaciada para poder leerlo carácter a
            // carácter, y sin recortar. La web lo corta a 10 en las tarjetas del
            // listado, pero acá lo muestra entero, y así corresponde.
            DetailRow(
                label = "Número de serie",
                value = bike.frame?.serialNumber,
                empty = "No especificado",
                mono = true,
            )
        }

        DetailSection("Colores") {
            DetailRow(
                "Color principal",
                bike.colors?.primaryColor ?: bike.colors?.primaryColorCustom,
            )
            bike.colors?.secondaryColor?.let { DetailRow("Color secundario", it) }
            bike.colors?.accentColor?.let { DetailRow("Color de acento", it) }
            bike.colors?.description?.let { DetailRow("Descripción", it) }
        }

        val purchase = bike.purchaseInfo
        if (purchase?.purchaseDate != null || purchase?.purchasePrice != null) {
            DetailSection("Compra") {
                purchase.purchaseDate?.let { DetailRow("Fecha de compra", formatLongDate(it)) }
                purchase.purchasePrice?.let {
                    DetailRow("Precio", listOfNotNull(purchase.currency, it).joinToString(" "))
                }
                purchase.purchaseMethod?.let { DetailRow("Forma de compra", it.displayName) }
            }
        }

        bike.notes?.takeIf { it.isNotBlank() }?.let {
            DetailSection("Notas") { DetailRow(null, it) }
        }

        DetailSection("Registro") {
            bike.createdAt?.let { DetailRow("Fecha de registro", formatLongDate(it)) }
            bike.registrationType?.let {
                DetailRow("Tipo de alta", if (it.name == "CATALOG") "Desde catálogo" else "Manual")
            }
        }

        Button(
            onClick = onUpdateComponents,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Actualizar componentes") }

        // La denuncia entra por el plan de búsqueda, igual que en el dashboard y
        // que en el front web: `ver-bici.js` manda a `suscripcion.html`, nunca
        // directo al formulario.
        if (state.canReportTheft) {
            OutlinedButton(
                onClick = onReportTheft,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) { Text("Denunciar robo") }
        } else {
            Text(
                text = "Sólo se puede denunciar una bicicleta activa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun PhotoGallery(
    photos: List<BikePhoto>,
    failed: Boolean,
    onPhotoClick: (BikePhoto) -> Unit,
) {
    DetailSection("Fotos") {
        when {
            photos.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(photos, key = { it.id }) { photo ->
                    Box {
                        AsyncImage(
                            model = photo.url,
                            contentDescription = photo.description ?: "Foto de la bicicleta",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(140.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPhotoClick(photo) },
                        )
                        if (photo.isPrimary) {
                            Text(
                                text = "Principal",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // Se distingue "no se pudieron traer" de "no hay": con media-service
            // caído, decir "no hay fotos" sobre una bici que sí las tiene manda a
            // buscar el problema donde no está.
            failed -> EmptyLine("No se pudieron cargar las fotos.")

            else -> EmptyLine("No hay fotos cargadas.")
        }
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

/** Una fila etiqueta/valor. Sin etiqueta cuando el valor se explica solo. */
@Composable
private fun DetailRow(
    label: String?,
    value: String?,
    empty: String = "—",
    mono: Boolean = false,
) {
    Column {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: empty,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

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

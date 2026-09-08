package pbis.bike.finder.ui.updatecomponents

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import pbis.bike.finder.data.remote.dto.PhotoType

/**
 * Las fotos ya cargadas de la bici, con eliminar y agregar — el `fotos-card` de
 * `actualizar-componentes.html`.
 *
 * Vive en esta pantalla y no en el detalle por la misma razón que en la web:
 * acá es donde se viene a corregir lo que la bici tiene cargado. Y está **fuera
 * del formulario**: las dos acciones impactan al toque contra media-service, no
 * al apretar "Guardar". Si colgaran del guardado, "Cancelar" tendría que poder
 * deshacer un DELETE que ya ocurrió.
 *
 * Es la única vía para cambiar las fotos después del alta: hasta que existió,
 * una foto vieja se quedaba vieja para siempre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BikePhotosSection(
    state: UpdateComponentsUiState,
    onPhotosPicked: (List<String>) -> Unit,
    onPhotoTypeChanged: (PhotoType) -> Unit,
    onGpsConsentChanged: (Boolean) -> Unit,
    onDeleteRequested: (BikePhotoItem) -> Unit,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // El selector del sistema: el usuario elige archivo por archivo y sólo eso
    // se comparte, así que no hace falta permiso de galería. Ver PhotoSection.
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_FOTOS),
    ) { uris -> onPhotosPicked(uris.map(Uri::toString)) }

    // `ACCESS_MEDIA_LOCATION` se pide sólo al marcar el consentimiento, igual
    // que en el alta: sin él el sistema entrega la foto sin GPS y el checkbox
    // sería decorativo.
    val requestMediaLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onGpsConsentChanged(granted) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Fotos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Se agregan y se eliminan al toque: no las guarda el botón de abajo. " +
                    "Hasta $MAX_FOTOS por bicicleta; si llegaste al tope, eliminá una para " +
                    "hacer lugar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            when {
                state.photosLoading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Cargando fotos…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Que no se hayan podido traer no es lo mismo que no tener: sin
                // esta distinción, agregar sobre una lista que en realidad tiene
                // cuatro fotos se pasa del tope.
                state.photosError != null -> Column {
                    Text(
                        text = state.photosError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetryLoad) { Text("Reintentar") }
                }

                state.photos.isEmpty() -> Text(
                    text = "Esta bicicleta todavía no tiene fotos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.photos.forEach { photo ->
                        PhotoTile(
                            photo = photo,
                            enabled = !state.photosBusy,
                            onDelete = { onDeleteRequested(photo) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = state.gpsConsent,
                    onCheckedChange = { marcado ->
                        if (marcado && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            requestMediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                        } else {
                            onGpsConsentChanged(marcado)
                        }
                    },
                )
                Text(
                    text = "Autorizo el análisis de la ubicación (GPS) de las fotos que " +
                        "agregue para ayudar a validar futuras denuncias de robo. Es " +
                        "opcional: si lo dejás sin marcar, las fotos se suben igual pero " +
                        "su GPS no se analiza.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            PhotoTypePicker(
                selected = state.photoType,
                enabled = !state.photosBusy,
                onSelected = onPhotoTypeChanged,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            // El botón dice cuántas van y al llegar al tope explica por qué está
            // apagado, en vez de dejarse apretar y fallar después de que el
            // usuario eligió los archivos.
            Button(
                onClick = {
                    pickPhotos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !state.photosBusy && !state.photosLleno && state.photosError == null,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (state.photosBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(
                    when {
                        state.photosBusy -> "Trabajando…"
                        state.photosLleno -> "Llegaste al máximo de $MAX_FOTOS"
                        else -> "Agregar foto (${state.photos.size}/$MAX_FOTOS)"
                    },
                )
            }
        }
    }
}

/**
 * Una foto de la grilla.
 *
 * El botón de eliminar va **siempre visible** y no al mantener apretado: es la
 * única vía para hacer lugar cuando se llegó al tope, y una acción que hay que
 * descubrir es una acción que no existe.
 */
@Composable
private fun PhotoTile(
    photo: BikePhotoItem,
    enabled: Boolean,
    onDelete: () -> Unit,
) {
    Box {
        AsyncImage(
            model = photo.miniaturaUrl,
            contentDescription = photo.fileName ?: "Foto de la bicicleta",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(104.dp)
                .clip(RoundedCornerShape(8.dp)),
        )

        if (photo.isPrimary) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
            ) {
                Text(
                    text = "Principal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        FilledTonalIconButton(
            onClick = onDelete,
            enabled = enabled,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Eliminar la foto ${photo.fileName ?: ""}".trim(),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** El `select` de tipo de foto: qué se le manda al backend con la próxima subida. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoTypePicker(
    selected: PhotoType,
    enabled: Boolean,
    onSelected: (PhotoType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier) {
        OutlinedTextField(
            value = selected.displayName,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Tipo de las fotos que agregues") },
            modifier = Modifier.fillMaxWidth(),
        )
        // La caja transparente encima es lo que abre el menú: un OutlinedTextField
        // de sólo lectura no recibe el click en todo su ancho.
        Box(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(4.dp))
                .clickable(enabled = enabled) { expanded = true },
        )

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PhotoType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.displayName) },
                    onClick = {
                        onSelected(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

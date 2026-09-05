package pbis.bike.finder.ui.addbike

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import pbis.bike.finder.data.repository.PendingPhoto

/**
 * Selección de fotos y consentimiento de análisis GPS.
 *
 * Usa el **selector de fotos del sistema**: no pide permiso de almacenamiento
 * porque el usuario elige archivo por archivo y sólo eso se comparte. Pedir
 * acceso a toda la galería para subir tres fotos sería desproporcionado, y desde
 * Android 13 el sistema directamente desaconseja ese permiso.
 */
@Composable
fun PhotoSection(
    photos: List<PendingPhoto>,
    gpsConsent: Boolean,
    onPhotosPicked: (List<String>) -> Unit,
    onPhotoRemoved: (String) -> Unit,
    onGpsConsentChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AddBikeViewModel.MAX_FOTOS),
    ) { uris -> onPhotosPicked(uris.map(Uri::toString)) }

    /**
     * `ACCESS_MEDIA_LOCATION` se pide **sólo al marcar el consentimiento**.
     *
     * Desde Android 10 el sistema le quita la ubicación a las fotos que entrega,
     * salvo que la app tenga este permiso y pida el original. Sin él, el
     * checkbox sería decorativo: el usuario autorizaría analizar un GPS que el
     * sistema ya borró antes de que la foto salga del teléfono.
     *
     * Pedirlo recién acá —y no al abrir la pantalla— es lo que hace que el
     * permiso se justifique solo: aparece en el momento en que el usuario acaba
     * de decir que quiere justamente eso.
     */
    val requestMediaLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onGpsConsentChanged(granted) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Fotos",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Ayudan a identificarla si aparece. Sacale al cuadro, al número de " +
                "serie y a cualquier marca que la distinga.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )

        if (photos.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .padding(bottom = 8.dp),
            ) {
                items(photos, key = { it.uri }) { photo ->
                    PhotoThumbnail(photo = photo, onRemove = { onPhotoRemoved(photo.uri) })
                }
            }
        }

        val lleno = photos.size >= AddBikeViewModel.MAX_FOTOS

        OutlinedButton(
            onClick = {
                pickPhotos.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            enabled = !lleno,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    lleno -> "Llegaste al máximo de ${AddBikeViewModel.MAX_FOTOS}"
                    photos.isEmpty() -> "Agregar fotos (máx. ${AddBikeViewModel.MAX_FOTOS})"
                    else -> "Agregar más fotos (${photos.size}/${AddBikeViewModel.MAX_FOTOS})"
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = gpsConsent,
                onCheckedChange = { marcado ->
                    if (marcado && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        requestMediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    } else {
                        onGpsConsentChanged(marcado)
                    }
                },
            )
            Text(
                text = "Autorizo el análisis de la ubicación (GPS) de estas fotos para ayudar " +
                    "a validar futuras denuncias de robo. Es opcional: si lo dejás sin marcar, " +
                    "las fotos se suben igual pero su GPS no se analiza.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun PhotoThumbnail(photo: PendingPhoto, onRemove: () -> Unit) {
    Box {
        AsyncImage(
            model = photo.uri,
            contentDescription = "Foto de la bicicleta",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(8.dp)),
        )

        if (photo.isPrimary) {
            // La principal es la que se ve en el listado y en la denuncia.
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(bottomEnd = 8.dp),
            ) {
                Text(
                    text = "Principal",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }

        TextButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.BottomEnd),
        ) { Text("Quitar", style = MaterialTheme.typography.labelSmall) }
    }
}


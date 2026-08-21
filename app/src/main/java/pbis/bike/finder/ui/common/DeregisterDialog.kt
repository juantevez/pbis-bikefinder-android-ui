package pbis.bike.finder.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Confirmación de la baja de una bicicleta.
 *
 * Lleva el nombre de la bici adentro y no un "¿estás seguro?" genérico. Dos
 * bicis de la misma marca no son raras, y desde que la baja se dispara con un
 * deslizamiento sobre una lista —un gesto que se puede empezar sin querer— el
 * nombre escrito es lo único que separa dar de baja la correcta de dar de baja
 * la de al lado.
 *
 * El botón de confirmar va en el color de error: es la única acción de la app
 * que saca algo del registro, y no hay deshacer.
 */
@Composable
fun DeregisterBikeDialog(
    bikeName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Dar de baja $bikeName?") },
        text = {
            Text(
                "Se saca el registro de tu cuenta. Si la vendiste, el nuevo dueño va a " +
                    "poder reclamarla con el número de serie.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Dar de baja", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

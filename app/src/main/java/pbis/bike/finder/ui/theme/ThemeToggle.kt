package pbis.bike.finder.ui.theme

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import pbis.bike.finder.data.local.ThemePreference

/**
 * La preferencia de tema vigente y la forma de cambiarla.
 *
 * Va por `CompositionLocal` y no por parámetro porque el control aparece en dos
 * lugares muy separados del árbol —el login y el dashboard— y enhebrar un
 * callback desde `MainActivity` hasta cada uno obligaría a que todas las
 * pantallas intermedias declaren un parámetro que no usan.
 */
@Immutable
data class ThemeController(
    val preference: ThemePreference,
    val onChange: (ThemePreference) -> Unit,
)

/**
 * El default no cambia nada: si alguien monta una pantalla sin proveer el
 * controlador —un preview, un test— el botón se pinta y no hace nada, en vez de
 * reventar.
 */
val LocalThemeController = staticCompositionLocalOf {
    ThemeController(ThemePreference.SYSTEM) {}
}

/**
 * Selector de tema.
 *
 * Botón de texto que abre las tres opciones. No es un switch claro/oscuro porque
 * un switch tiene dos estados y acá hay tres — "automático" no es el punto medio
 * de nada, es una opción propia.
 *
 * Dice "Tema" y no el valor actual: en la barra del dashboard, un botón que dice
 * "Automático" al lado de "Salir" se lee como una acción, no como un estado.
 */
@Composable
fun ThemeToggle(modifier: Modifier = Modifier) {
    val controller = LocalThemeController.current
    var expanded by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = true }, modifier = modifier) {
        Text("Tema")
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        ThemePreference.entries.forEach { option ->
            val selected = option == controller.preference
            DropdownMenuItem(
                text = {
                    Text(
                        text = option.label,
                        // La opción activa se marca con peso y color, no con un
                        // ícono de tilde: los íconos de brillo viven en
                        // material-icons-extended y traerlo entero por un tilde
                        // es varios MB de APK.
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                onClick = {
                    expanded = false
                    controller.onChange(option)
                },
            )
        }
    }
}

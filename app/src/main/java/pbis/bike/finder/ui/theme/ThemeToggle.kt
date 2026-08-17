package pbis.bike.finder.ui.theme

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import pbis.bike.finder.R
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
 * La siguiente opción del ciclo, en el orden en que están declaradas:
 * automático → claro → oscuro → automático.
 *
 * Se apoya en `entries` en vez de un `when` con los tres casos para que agregar
 * una opción al enum no deje este ciclo saltándosela en silencio.
 */
private fun ThemePreference.next(): ThemePreference {
    val options = ThemePreference.entries
    return options[(ordinal + 1) % options.size]
}

/**
 * El ícono de cada opción. Vive acá y no en el enum porque `ThemePreference` es
 * de datos: no tiene por qué saber que existe una capa de UI.
 */
@Composable
private fun ThemePreference.icon(): ImageVector = when (this) {
    // Engranaje: "lo que diga la configuración del teléfono".
    ThemePreference.SYSTEM -> ImageVector.vectorResource(R.drawable.ic_theme_system)
    ThemePreference.LIGHT -> ImageVector.vectorResource(R.drawable.ic_theme_light)
    ThemePreference.DARK -> ImageVector.vectorResource(R.drawable.ic_theme_dark)
}

/**
 * Selector de tema.
 *
 * Un solo botón que cicla entre las tres opciones: el ícono muestra cuál está
 * activa y cada toque pasa a la siguiente. Antes era un menú desplegable con las
 * tres etiquetas; para tres estados que se prueban a ojo, abrir un menú para
 * elegir es más ceremonia que la que amerita una decisión cosmética.
 *
 * El ciclo no se corta en claro/oscuro: "automático" queda dentro de la vuelta,
 * así que se puede volver a él sin ir a buscarlo a otra pantalla.
 *
 * El `contentDescription` sí nombra el estado y lo que hace el toque: sin texto
 * a la vista, es lo único que le queda a un lector de pantalla. La pantalla de
 * perfil mantiene el selector con las tres etiquetas escritas, para quien
 * prefiera elegir en vez de ciclar.
 */
@Composable
fun ThemeToggle(modifier: Modifier = Modifier) {
    val controller = LocalThemeController.current
    val current = controller.preference
    val next = current.next()

    IconButton(onClick = { controller.onChange(next) }, modifier = modifier) {
        Icon(
            imageVector = current.icon(),
            contentDescription = "Tema: ${current.label}. Tocar para cambiar a ${next.label}",
        )
    }
}

package pbis.bike.finder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import pbis.bike.finder.data.local.ThemePreference

/**
 * El tema según lo que eligió el usuario.
 *
 * Es la entrada real de la app; la sobrecarga booleana de abajo queda para
 * previews y tests, que quieren fijar un tema sin pasar por la preferencia.
 */
@Composable
fun BikeFinderTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) = BikeFinderTheme(darkTheme = preference.isDark(), content = content)

/**
 * Si esta preferencia pinta oscuro *ahora*.
 *
 * Es `@Composable` porque `SYSTEM` depende de la configuración del dispositivo, y
 * eso puede cambiar mientras la app está abierta. Leerlo acá hace que la
 * recomposición llegue sola cuando el usuario cambia el modo del teléfono.
 */
@Composable
fun ThemePreference.isDark(): Boolean = when (this) {
    ThemePreference.SYSTEM -> isSystemInDarkTheme()
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
}

/**
 * Tema de la app.
 *
 * **No usa dynamic color** aunque el dispositivo lo soporte: la paleta
 * crema/dorada es identidad de marca, y dejar que Material la reemplace por los
 * colores del fondo de pantalla del usuario haría que la app no se parezca a
 * BikeFinder. Es una decisión, no un olvido: si en algún momento se prefiere lo
 * contrario, se agrega `dynamicLightColorScheme` acá.
 *
 * El par "invertido" de la web (`--invert-bg` / `--invert-fg`) existía porque
 * `--charcoal` no servía para botones primarios: es la superficie oscura de
 * marca y tiene que seguir oscura en los dos temas, así que usarla de fondo con
 * texto crema daba negro sobre negro en modo oscuro. En Material 3 ese rol lo
 * cubren `primary`/`onPrimary`, que ya invierten juntos por diseño del sistema.
 */
@Composable
fun BikeFinderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

private val LightColors = lightColorScheme(
    primary = Gold,
    onPrimary = BlackInk,
    primaryContainer = GoldLight,
    onPrimaryContainer = BlackInk,
    secondary = Charcoal,
    onSecondary = Cream,
    background = Cream,
    onBackground = BlackInk,
    surface = SurfaceLight,
    onSurface = BlackInk,
    surfaceVariant = CreamDark,
    onSurfaceVariant = Muted,
    surfaceContainerHighest = CardHoverLight,
    outline = BorderLight,
    error = Danger,
    onError = SurfaceLight,
    tertiary = Success,
)

private val DarkColors = darkColorScheme(
    primary = GoldDarkMode,
    onPrimary = CreamDarkMode,
    primaryContainer = GoldLight,
    onPrimaryContainer = CreamDarkMode,
    secondary = CharcoalDarkMode,
    onSecondary = InkDarkMode,
    background = CreamDarkMode,
    onBackground = InkDarkMode,
    surface = SurfaceDarkMode,
    onSurface = InkDarkMode,
    surfaceVariant = CreamDarkModeAlt,
    onSurfaceVariant = MutedDarkMode,
    surfaceContainerHighest = CardHoverDarkMode,
    outline = BorderDarkMode,
    error = Danger,
    onError = InkDarkMode,
    tertiary = Success,
)

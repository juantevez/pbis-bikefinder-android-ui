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

/**
 * **Hay que declarar todos los roles, no sólo los que se usan a ojo.**
 *
 * Cualquier rol que quede sin definir cae al esquema *baseline* de Material 3,
 * que es violeta. Durante un tiempo eso pasó sin que se notara en las pantallas
 * armadas a mano —que nombran sus colores— pero sí en los componentes que eligen
 * su superficie solos: los desplegables se pintaban lavanda con
 * `surfaceContainer`, y el snackbar con `inverseSurface`. El síntoma aparece
 * lejos del error, en un componente que nadie tocó.
 *
 * Los pares invertidos salen de `--invert-bg` / `--invert-fg` de la web, que es
 * exactamente lo que M3 llama `inverseSurface` / `inverseOnSurface`.
 */
private val LightColors = lightColorScheme(
    primary = Gold,
    onPrimary = BlackInk,
    primaryContainer = GoldLight,
    onPrimaryContainer = BlackInk,
    secondary = Charcoal,
    onSecondary = Cream,
    secondaryContainer = CreamDark,
    onSecondaryContainer = BlackInk,
    tertiary = Success,
    onTertiary = SurfaceLight,
    tertiaryContainer = SuccessContainerLight,
    onTertiaryContainer = OnSuccessContainerLight,
    background = Cream,
    onBackground = BlackInk,
    surface = SurfaceLight,
    onSurface = BlackInk,
    surfaceVariant = CreamDark,
    onSurfaceVariant = Muted,
    surfaceDim = CreamDark,
    surfaceBright = SurfaceLight,
    // En claro hay una sola superficie elevada —la tarjeta blanca—, así que los
    // tres niveles bajos son el mismo blanco a propósito: un menú tiene que
    // leerse como una tarjeta y no como un tono intermedio inventado.
    surfaceContainerLowest = SurfaceLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainer = SurfaceLight,
    surfaceContainerHigh = CreamDark,
    surfaceContainerHighest = CardHoverLight,
    inverseSurface = Charcoal,
    inverseOnSurface = Cream,
    inversePrimary = GoldDarkMode,
    outline = BorderLight,
    outlineVariant = BorderLight,
    error = Danger,
    onError = SurfaceLight,
    errorContainer = DangerContainerLight,
    onErrorContainer = Danger,
    scrim = BlackInk,
)

private val DarkColors = darkColorScheme(
    primary = GoldDarkMode,
    onPrimary = CreamDarkMode,
    primaryContainer = GoldLight,
    onPrimaryContainer = CreamDarkMode,
    secondary = CharcoalDarkMode,
    onSecondary = InkDarkMode,
    secondaryContainer = CardHoverDarkMode,
    onSecondaryContainer = InkDarkMode,
    tertiary = Success,
    onTertiary = InkDarkMode,
    tertiaryContainer = SuccessContainerDarkMode,
    onTertiaryContainer = OnSuccessContainerDarkMode,
    background = CreamDarkMode,
    onBackground = InkDarkMode,
    surface = SurfaceDarkMode,
    onSurface = InkDarkMode,
    surfaceVariant = CreamDarkModeAlt,
    onSurfaceVariant = MutedDarkMode,
    surfaceDim = CreamDarkMode,
    surfaceBright = CardHoverDarkMode,
    surfaceContainerLowest = CharcoalDarkMode,
    surfaceContainerLow = PanelDarkMode,
    surfaceContainer = SurfaceDarkMode,
    surfaceContainerHigh = CardHoverDarkMode,
    surfaceContainerHighest = CardHoverDarkMode,
    inverseSurface = Cream,
    inverseOnSurface = CreamDarkMode,
    inversePrimary = Gold,
    outline = BorderDarkMode,
    outlineVariant = BorderDarkMode,
    error = Danger,
    onError = InkDarkMode,
    errorContainer = DangerContainerDarkMode,
    onErrorContainer = OnDangerContainerDarkMode,
    scrim = CharcoalDarkMode,
)

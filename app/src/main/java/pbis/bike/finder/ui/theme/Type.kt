package pbis.bike.finder.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import pbis.bike.finder.R

/**
 * Tipografía de BikeFinder, portada del front web: Cormorant Garamond para
 * títulos y DM Sans para todo lo demás.
 *
 * Las fuentes van **bundleadas** en `res/font` en vez de bajarse con
 * `ui-text-google-fonts`. Descargarlas en runtime ahorra ~1 MB de APK, pero deja
 * la primera pantalla atada a la red: sin conexión —o con el proveedor de Google
 * Play ausente— la app arranca con la fuente del sistema y después salta a la de
 * marca cuando la descarga termina. En una app que se abre justo cuando a
 * alguien le robaron la bici, ese es el peor momento para depender de la red.
 * Bundlear cuesta espacio y se paga una vez.
 *
 * Son instancias estáticas (no fuentes variables): `res/font` recién soporta
 * variables desde API 26 y el `minSdk` es 24, así que en los dos niveles más
 * viejos una variable no cargaría.
 */

/**
 * Los pesos son los que el front realmente usa, no los que pide el `<link>` de
 * Google Fonts: Cormorant siempre en 600, DM Sans en 400 para cuerpo y 500 para
 * botones, etiquetas y énfasis. **No hay 700 en ninguna de las dos.** El único
 * `font-weight: 700` del CSS vive en `admin-reviews.css`, el panel de
 * administración, que sigue siendo web y está fuera del alcance de la app. Bajar
 * las negritas costaba 340 KB para que nadie las viera.
 *
 * Si algún `Text` pide `FontWeight.Bold`, Compose resuelve al peso más cercano
 * —600 en Cormorant, 500 en DM Sans— que es exactamente lo que se ve en la web.
 */

/** Serif de marca. Sólo para títulos: en texto corrido rinde poco por su x-height chica. */
val CormorantGaramond = FontFamily(
    Font(R.font.cormorant_garamond_regular, FontWeight.Normal),
    Font(R.font.cormorant_garamond_semibold, FontWeight.SemiBold),
)

/** Sans de marca. Cuerpo, etiquetas y botones. */
val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
)

/**
 * El reparto sigue al de la web: serif de `displayLarge` a `titleLarge`, sans de
 * `titleMedium` para abajo. `titleMedium` es el título de las tarjetas del
 * dashboard y de las filas de bicicletas —texto chico y denso—, donde el serif
 * se vuelve ilegible; por eso el corte queda ahí y no un escalón más abajo.
 *
 * Los tamaños de los títulos suben un par de puntos sobre el default de Material
 * porque Cormorant tiene x-height chica: al mismo `sp` se ve más chico que
 * cualquier sans al lado. El `letterSpacing` va en negativo por lo contrario:
 * apretado es como se usa en la web.
 *
 * Los anclajes salen del CSS: `.page-title` es 36px (`headlineMedium`),
 * `.nav-brand` y `.section-title` son 22 y 20px (`titleLarge`). Los tamaños de
 * cuerpo y etiquetas, en cambio, **no** se copian: la web baja a 11-13px en
 * `.form-label` y `.btn`, que en un teléfono queda por debajo del mínimo legible.
 * Ahí mandan los defaults de Material.
 */
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = CormorantGaramond,
        fontWeight = FontWeight.SemiBold,
        fontSize = 60.sp,
        lineHeight = 68.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = CormorantGaramond,
        fontWeight = FontWeight.SemiBold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = CormorantGaramond,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = CormorantGaramond,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = CormorantGaramond,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = CormorantGaramond,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = CormorantGaramond,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

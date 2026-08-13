package pbis.bike.finder.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tipografía por defecto de Material, todavía sin portar.
 *
 * El front web usa Cormorant Garamond (títulos) y DM Sans (texto), servidas por
 * Google Fonts. Traerlas exige bundlear los `.ttf` en `res/font` o sumar
 * `androidx.compose.ui:ui-text-google-fonts`, que descarga en runtime — con lo
 * que la primera pantalla depende de la red. Queda pendiente y es decisión de
 * diseño, no de infraestructura: hasta entonces la app se ve con la fuente del
 * sistema y no con la identidad de la marca.
 */
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)
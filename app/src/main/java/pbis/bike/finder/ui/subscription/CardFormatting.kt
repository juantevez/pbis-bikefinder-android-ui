package pbis.bike.finder.ui.subscription

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Formato de tarjeta **para mostrar**, no para guardar.
 *
 * La primera versión reformateaba el texto dentro del `value` del campo: cada
 * cuatro dígitos el ViewModel devolvía una cadena más larga que la tecleada. El
 * cursor se quedaba donde estaba y el dígito siguiente entraba corrido, así que
 * había que borrar todo y volver a escribir.
 *
 * La regla que evita eso es no tocar nunca el texto que el usuario está
 * editando. El estado guarda **sólo dígitos** —lo que se teclea, uno a uno— y
 * los espacios y la barra existen únicamente en pantalla. Compose reposiciona el
 * cursor con el [OffsetMapping], que es justamente para esto.
 */

/** `1234567890123456` → `1234 5678 9012 3456`. */
object CardNumberTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text.take(MAX_CARD_DIGITS)
        val formatted = digits.chunked(4).joinToString(" ")

        return TransformedText(
            AnnotatedString(formatted),
            object : OffsetMapping {
                // Un espacio por cada bloque de 4 ya completo a la izquierda del
                // cursor. El -1 evita contar el espacio del bloque que se está
                // escribiendo: con 4 dígitos el cursor va antes del espacio, no
                // después, o el usuario vería el cursor saltar solo.
                override fun originalToTransformed(offset: Int): Int {
                    val clamped = offset.coerceIn(0, digits.length)
                    if (clamped == 0) return 0
                    return clamped + (clamped - 1) / 4
                }

                override fun transformedToOriginal(offset: Int): Int {
                    val clamped = offset.coerceIn(0, formatted.length)
                    return clamped - clamped / 5
                }
            },
        )
    }
}

/** `1228` → `12/28`. */
object ExpiryTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text.take(MAX_EXPIRY_DIGITS)
        val formatted = if (digits.length > 2) {
            "${digits.take(2)}/${digits.drop(2)}"
        } else {
            digits
        }

        return TransformedText(
            AnnotatedString(formatted),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int {
                    val clamped = offset.coerceIn(0, digits.length)
                    return if (clamped <= 2) clamped else clamped + 1
                }

                override fun transformedToOriginal(offset: Int): Int {
                    val clamped = offset.coerceIn(0, formatted.length)
                    return if (clamped <= 2) clamped else clamped - 1
                }
            },
        )
    }
}

const val MAX_CARD_DIGITS = 16
const val MAX_EXPIRY_DIGITS = 4

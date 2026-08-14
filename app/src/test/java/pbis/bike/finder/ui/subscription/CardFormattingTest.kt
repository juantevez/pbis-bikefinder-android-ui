package pbis.bike.finder.ui.subscription

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El formato de tarjeta se muestra pero no se guarda.
 *
 * Lo que se rompía era el cursor: el ViewModel reformateaba el texto dentro del
 * `value`, el campo crecía bajo el cursor y cada cuarto dígito entraba corrido
 * un lugar. Estos tests miran el [androidx.compose.ui.text.input.OffsetMapping],
 * que es la pieza que decide dónde queda el cursor.
 */
class CardFormattingTest {

    private fun cardNumber(text: String) =
        CardNumberTransformation.filter(AnnotatedString(text))

    private fun expiry(text: String) =
        ExpiryTransformation.filter(AnnotatedString(text))

    @Test
    fun `el numero se muestra en grupos de cuatro`() {
        assertEquals("1234 5678 9012 3456", cardNumber("1234567890123456").text.text)
    }

    @Test
    fun `escribiendo el quinto digito el cursor salta el espacio`() {
        // Es el caso exacto que fallaba: con 4 dígitos el cursor va ANTES del
        // espacio; con 5, después. Si el mapeo se equivoca acá, el dígito
        // siguiente entra corrido.
        val m = cardNumber("12345").offsetMapping

        assertEquals(4, m.originalToTransformed(4))
        assertEquals(6, m.originalToTransformed(5))
    }

    @Test
    fun `el cursor al final del numero completo queda al final del texto`() {
        val t = cardNumber("1234567890123456")

        assertEquals(19, t.offsetMapping.originalToTransformed(16))
        assertEquals(19, t.text.text.length)
    }

    @Test
    fun `el mapeo inverso ignora los espacios`() {
        val m = cardNumber("1234567890123456").offsetMapping

        assertEquals(4, m.transformedToOriginal(4))
        // Posición del espacio: pertenece al dígito de su izquierda.
        assertEquals(4, m.transformedToOriginal(5))
        assertEquals(5, m.transformedToOriginal(6))
        assertEquals(16, m.transformedToOriginal(19))
    }

    @Test
    fun `ida y vuelta se cancelan en todas las posiciones`() {
        // La propiedad que de verdad importa: si esto falla en algún punto, el
        // cursor se corre justo ahí.
        val m = cardNumber("1234567890123456").offsetMapping

        (0..16).forEach { i ->
            assertEquals(i, m.transformedToOriginal(m.originalToTransformed(i)))
        }
    }

    @Test
    fun `el vencimiento muestra la barra recien con el tercer digito`() {
        assertEquals("12", expiry("12").text.text)
        assertEquals("12/2", expiry("122").text.text)
        assertEquals("12/28", expiry("1228").text.text)
    }

    @Test
    fun `el cursor del vencimiento salta la barra`() {
        val m = expiry("1228").offsetMapping

        assertEquals(2, m.originalToTransformed(2))
        assertEquals(4, m.originalToTransformed(3))
        assertEquals(5, m.originalToTransformed(4))
        (0..4).forEach { i ->
            assertEquals(i, m.transformedToOriginal(m.originalToTransformed(i)))
        }
    }

    @Test
    fun `un campo vacio no rompe el mapeo`() {
        assertEquals(0, cardNumber("").offsetMapping.originalToTransformed(0))
        assertEquals(0, expiry("").offsetMapping.originalToTransformed(0))
    }
}

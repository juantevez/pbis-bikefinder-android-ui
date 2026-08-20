package pbis.bike.finder.ui.tips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pbis.bike.finder.data.remote.dto.TipDto
import pbis.bike.finder.data.remote.dto.TipStatus

/** Cómo se muestra una pista. Ver [locationText] para el porqué de cada regla. */
class TipDisplayTest {

    private val tip = TipDto(id = "t1")

    @Test
    fun `la descripcion de la ubicacion gana cuando viene`() {
        val result = tip.copy(
            locationDescription = "Plaza Irlanda",
            latitude = -34.6,
            longitude = -58.4,
        ).locationText()

        assertEquals("Plaza Irlanda", result)
    }

    @Test
    fun `sin descripcion se cae a las coordenadas`() {
        // El caso real: un avistamiento reportado sólo con GPS. El backend manda
        // el campo como texto vacío, no como null.
        val result = tip.copy(
            locationDescription = "",
            latitude = -34.6,
            longitude = -58.4,
        ).locationText()

        assertEquals("-34.6, -58.4", result)
    }

    @Test
    fun `una descripcion en blanco cuenta como ausente`() {
        val result = tip.copy(
            locationDescription = "   ",
            latitude = -34.6,
            longitude = -58.4,
        ).locationText()

        assertEquals("-34.6, -58.4", result)
    }

    @Test
    fun `sin descripcion ni coordenadas no hay ubicacion`() {
        assertNull(tip.locationText())
        assertNull(tip.copy(locationDescription = "").locationText())
        // Una sola coordenada no ubica nada.
        assertNull(tip.copy(latitude = -34.6).locationText())
    }

    @Test
    fun `sin leer es exactamente NEW`() {
        assertTrue(tip.copy(status = TipStatus.NEW).isUnread)
        assertFalse(tip.copy(status = TipStatus.READ).isUnread)
        assertFalse(tip.copy(status = TipStatus.REPLIED).isUnread)
        assertFalse(tip.copy(status = TipStatus.CONVERTED_TO_SIGHTING).isUnread)
        assertFalse(tip.isUnread)
    }
}

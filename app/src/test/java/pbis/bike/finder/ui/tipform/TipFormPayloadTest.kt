package pbis.bike.finder.ui.tipform

import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pbis.bike.finder.data.remote.dto.SubmitTipRequestDto
import pbis.bike.finder.data.repository.ResolvedAddress

/**
 * Qué viaja en el POST de una pista y qué no.
 *
 * Son las reglas que se rompen en silencio: un campo vacío que llega como `""`
 * en vez de `null` se lee después como "dejó un contacto", y una calle que se
 * manda sin que nadie la haya mirado queda fijada como cierta porque el backend
 * respeta la dirección que le dan en vez de geocodificar el punto.
 */
class TipFormPayloadTest {

    private val hoy = LocalDate(2026, 8, 21)

    private fun state(
        description: String = "La vi atada en la puerta de un bar",
        time: String = "",
        email: String = "",
        phone: String = "",
        latitude: Double? = null,
        longitude: Double? = null,
        accepted: ResolvedAddress? = null,
    ) = TipFormUiState(
        sightingDate = hoy,
        sightingTime = time,
        description = description,
        informantEmail = email,
        informantPhone = phone,
        latitude = latitude,
        longitude = longitude,
        acceptedAddress = accepted,
    )

    private fun address(
        streetType: String? = "AVENIDA",
        streetName: String? = "Cabildo",
        streetNumber: String? = "2100",
        locality: String? = "Colegiales",
    ) = ResolvedAddress(
        streetType = streetType,
        streetName = streetName,
        streetNumber = streetNumber,
        display = "Av. Cabildo 2100, Colegiales",
        locality = locality,
        province = "Buenos Aires",
    )

    // ── Contacto ─────────────────────────────────────────────────────────────

    @Test
    fun `los contactos en blanco viajan como null y no como cadena vacia`() {
        val body = state().toRequest()

        // Un "" en la base se lee después como "dejó un contacto": del lado del
        // dueño aparecería un botón de mail que abre el cliente de correo vacío.
        assertNull(body.informantEmail)
        assertNull(body.informantPhone)
    }

    @Test
    fun `mail y telefono viajan por separado`() {
        val body = state(email = "testigo@example.com", phone = "541155551234").toRequest()

        assertEquals("testigo@example.com", body.informantEmail)
        assertEquals("541155551234", body.informantPhone)
        // El campo viejo no se usa más: es el legado de antes de V16.
        assertNull(body.informantContact)
    }

    @Test
    fun `los espacios de mas se recortan`() {
        // Copiar y pegar un mail suele traerse un espacio al final.
        val body = state(email = "  testigo@example.com  ", phone = " 541155551234 ").toRequest()

        assertEquals("testigo@example.com", body.informantEmail)
        assertEquals("541155551234", body.informantPhone)
    }

    @Test
    fun `un contacto con solo espacios cuenta como vacio`() {
        assertNull(state(email = "   ").toRequest().informantEmail)
    }

    // ── Hora y descripción ───────────────────────────────────────────────────

    @Test
    fun `la hora vacia viaja como null`() {
        assertNull(state(time = "").toRequest().sightingTimeApprox)
    }

    @Test
    fun `la hora es texto libre, no solo un reloj`() {
        // El campo es VARCHAR(50) a propósito: quien vio la bici de pasada suele
        // acordarse de "a la tarde", no de las 20:30.
        assertEquals("a la tarde", state(time = "a la tarde").toRequest().sightingTimeApprox)
    }

    @Test
    fun `la descripcion viaja recortada`() {
        assertEquals("La vi en el parque", state(description = "  La vi en el parque  ").toRequest().description)
    }

    // ── Ubicación ────────────────────────────────────────────────────────────

    @Test
    fun `sin punto no se manda ubicacion`() {
        // Alguien que vio la bici pasar y no sabe marcar la esquina igual tiene
        // algo que aportar. La pista vale sin coordenadas.
        assertNull(state().toRequest().sightingLocation)
    }

    @Test
    fun `con punto viajan las coordenadas y la precision`() {
        val location = state(latitude = -34.58, longitude = -58.44).toRequest().sightingLocation

        assertEquals(-34.58, location?.latitude)
        assertEquals(-58.44, location?.longitude)
        // Lo marcó una persona sobre el mapa, no un geocoder.
        assertEquals("EXACT", location?.precision)
    }

    @Test
    fun `la calle NO viaja si el informante no la confirmo`() {
        // Es la regla que importa: el backend respeta la dirección que le mandan
        // y no la sobreescribe. Mandar una que nadie miró fija como cierta una
        // adivinanza de OSM, sobre el dato que después lee la policía.
        val location = state(latitude = -34.58, longitude = -58.44).toRequest().sightingLocation

        assertNull(location?.streetName)
        assertNull(location?.streetNumber)
        assertNull(location?.reference)
    }

    @Test
    fun `la calle viaja cuando la confirmo`() {
        val location = state(
            latitude = -34.58,
            longitude = -58.44,
            accepted = address(),
        ).toRequest().sightingLocation

        assertEquals("AVENIDA", location?.streetType)
        assertEquals("Cabildo", location?.streetName)
        assertEquals("2100", location?.streetNumber)
        assertEquals("Colegiales", location?.reference)
    }

    @Test
    fun `una direccion aceptada sin punto no inventa una ubicacion`() {
        // No deberia poder pasar —la dirección sale de resolver un punto— pero
        // si el punto se quita, lo que queda no es una ubicación.
        assertNull(state(accepted = address()).toRequest().sightingLocation)
    }

    // ── Cuándo se puede enviar ───────────────────────────────────────────────

    @Test
    fun `sin descripcion no se envia`() {
        assertFalse(state(description = "").canSubmit)
        assertFalse(state(description = "   ").canSubmit)
    }

    @Test
    fun `con descripcion alcanza, aunque no haya punto ni contacto`() {
        assertTrue(state().canSubmit)
    }

    @Test
    fun `una descripcion mas larga que el limite no se envia`() {
        // 5000 es el único techo del campo y el backend lo rechaza: cortarlo acá
        // evita perder el formulario entero al enviar.
        val larga = "x".repeat(SubmitTipRequestDto.MAX_DESCRIPTION + 1)

        assertTrue(state(description = larga).descriptionTooLong)
        assertFalse(state(description = larga).canSubmit)
    }

    @Test
    fun `justo en el limite se puede enviar`() {
        val exacta = "x".repeat(SubmitTipRequestDto.MAX_DESCRIPTION)

        assertFalse(state(description = exacta).descriptionTooLong)
        assertTrue(state(description = exacta).canSubmit)
    }

    @Test
    fun `mientras se envia no se puede volver a enviar`() {
        assertFalse(state().copy(submitting = true).canSubmit)
    }
}

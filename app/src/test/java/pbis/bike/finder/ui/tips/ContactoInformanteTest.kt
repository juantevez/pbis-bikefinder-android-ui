package pbis.bike.finder.ui.tips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pbis.bike.finder.data.remote.dto.TipDto

/**
 * Cómo se interpreta el contacto que dejó el informante.
 *
 * El backend lo guarda como **texto libre sin validar**, a propósito: puede ser un mail, un
 * teléfono o un usuario de Instagram. Estos tests fijan las dos reglas que importan — que lo
 * reconocible ofrezca la acción correcta, y que **nada se pierda** cuando no se reconoce.
 */
class ContactoInformanteTest {

    // ── Mail ─────────────────────────────────────────────────────────────────

    @Test
    fun `un mail se reconoce y arma el mailto`() {
        val contacto = clasificarContacto("testigo@example.com")

        assertTrue(contacto is ContactoInformante.Email)
        assertEquals("mailto:testigo@example.com", mailtoUri(contacto as ContactoInformante.Email))
    }

    @Test
    fun `los espacios de mas no rompen el reconocimiento`() {
        // Copiar y pegar de un mail suele traerse un espacio al final.
        assertTrue(clasificarContacto("  testigo@example.com  ") is ContactoInformante.Email)
    }

    @Test
    fun `algo con arroba pero sin dominio no es un mail`() {
        // Un @usuario de Instagram entra por acá: abrir el cliente de correo con eso
        // seria peor que mostrarlo como texto.
        assertTrue(clasificarContacto("@juanciclista") is ContactoInformante.Otro)
        assertTrue(clasificarContacto("testigo@localhost") is ContactoInformante.Otro)
    }

    // ── Teléfono ─────────────────────────────────────────────────────────────

    @Test
    fun `un numero con codigo de pais sirve para WhatsApp`() {
        val contacto = clasificarContacto("5491155551234")

        assertTrue(contacto is ContactoInformante.Telefono)
        val telefono = contacto as ContactoInformante.Telefono
        assertTrue(telefono.sirveParaWhatsApp)
        assertEquals("https://wa.me/5491155551234", whatsAppUrl(telefono))
    }

    @Test
    fun `los simbolos se sacan antes de armar el link`() {
        // Por mas claro que sea el hint del formulario, la gente escribe el numero
        // como lo diria. wa.me no acepta ni el mas ni los espacios ni los guiones.
        val telefono = clasificarContacto("+54 9 11 5555-1234") as ContactoInformante.Telefono

        assertEquals("5491155551234", telefono.paraLinks)
        assertEquals("https://wa.me/5491155551234", whatsAppUrl(telefono))
        // El texto original se conserva: es lo que se le muestra al dueño.
        assertEquals("+54 9 11 5555-1234", telefono.crudo)
    }

    @Test
    fun `un numero local no ofrece WhatsApp pero si SMS`() {
        // wa.me exige formato internacional. Sin codigo de pais el link se abriria
        // roto, y ofrecer un boton que falla es peor que no ofrecerlo.
        val telefono = clasificarContacto("1155551234") as ContactoInformante.Telefono

        assertFalse(telefono.sirveParaWhatsApp)
        assertEquals("smsto:1155551234", smsUri(telefono))
    }

    @Test
    fun `el numero de la pista real se reconoce`() {
        // El que llego en el e2e. Ojo: no tiene el 9 que WhatsApp pide para los
        // celulares argentinos, y a proposito NO se lo agregamos — ver whatsAppUrl.
        val telefono = clasificarContacto("541171005678") as ContactoInformante.Telefono

        assertTrue(telefono.sirveParaWhatsApp)
        assertEquals("https://wa.me/541171005678", whatsAppUrl(telefono))
    }

    // ── Lo que no se reconoce ────────────────────────────────────────────────

    @Test
    fun `un usuario de red social se muestra tal cual`() {
        val contacto = clasificarContacto("instagram.com/juanciclista")

        assertTrue(contacto is ContactoInformante.Otro)
        // Nada se pierde: quien solo tiene Instagram igual puede ayudar.
        assertEquals("instagram.com/juanciclista", contacto.crudo)
    }

    @Test
    fun `pocos digitos no alcanzan para ser telefono`() {
        // Un apodo con numeros, un año. Ofrecer "SMS" sobre esto no tiene sentido.
        assertTrue(clasificarContacto("1234") is ContactoInformante.Otro)
        assertTrue(clasificarContacto("bici2026") is ContactoInformante.Otro)
    }

    @Test
    fun `el texto original siempre queda disponible`() {
        // La garantia de fondo: se reconozca o no, el dueño ve lo que el informante
        // escribio. Es su unico camino hacia quien vio la bici.
        listOf("testigo@example.com", "+54 9 11 5555-1234", "@juanciclista", "1234")
            .forEach { assertEquals(it.trim(), clasificarContacto(it).crudo) }
    }

    // ── Qué contactos se ofrecen ─────────────────────────────────────────────

    private fun tip(
        contact: String? = null,
        email: String? = null,
        phone: String? = null,
    ) = TipDto(id = "t1", informantContact = contact, informantEmail = email,
        informantPhone = phone)

    @Test
    fun `con mail y telefono se ofrecen los dos, telefono primero`() {
        val contactos = contactosDe(tip(email = "testigo@example.com", phone = "5491155551234"))

        assertEquals(2, contactos.size)
        // WhatsApp es menos invasivo que un mail formal y suele responderse antes.
        assertTrue(contactos[0] is ContactoInformante.Telefono)
        assertTrue(contactos[1] is ContactoInformante.Email)
    }

    @Test
    fun `una pista vieja usa el campo legado`() {
        // Anteriores a V16: todo junto en informantContact, sin distinguir qué es.
        val contactos = contactosDe(tip(contact = "541171005678"))

        assertEquals(1, contactos.size)
        assertTrue(contactos[0] is ContactoInformante.Telefono)
    }

    @Test
    fun `el legado no se suma a los campos nuevos`() {
        // Si una pista migrada tuviera el telefono en los dos lados, se mostraria
        // dos veces lo mismo.
        val contactos = contactosDe(tip(contact = "5491155551234", phone = "5491155551234"))

        assertEquals(1, contactos.size)
    }

    @Test
    fun `sin ningun contacto no se ofrece nada`() {
        assertTrue(contactosDe(tip()).isEmpty())
        assertTrue(contactosDe(tip(contact = "", email = "  ")).isEmpty())
    }
}
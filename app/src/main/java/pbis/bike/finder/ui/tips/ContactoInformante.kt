package pbis.bike.finder.ui.tips

/**
 * Qué dejó el informante como contacto, y qué se puede hacer con eso.
 *
 * El backend guarda un **único texto libre** de 255 caracteres, sin validar el formato y a
 * propósito: puede ser un mail, un teléfono, un usuario de Instagram o lo que la persona
 * quiera. Esa flexibilidad importa —quien sólo tiene Instagram igual puede ayudar— así que
 * acá no se rechaza nada: se reconoce lo que se puede y el resto se muestra tal cual.
 */
sealed interface ContactoInformante {

    /** El texto original, siempre disponible para mostrar y copiar. */
    val crudo: String

    data class Email(override val crudo: String) : ContactoInformante

    /**
     * @param paraLinks el número sin símbolos, que es lo que aceptan `wa.me`, `sms:` y `tel:`
     * @param sirveParaWhatsApp si parece tener código de país; ver [pareceInternacional]
     */
    data class Telefono(
        override val crudo: String,
        val paraLinks: String,
        val sirveParaWhatsApp: Boolean,
    ) : ContactoInformante

    /** Un usuario de red social, un apodo, lo que sea. Se muestra y se puede copiar. */
    data class Otro(override val crudo: String) : ContactoInformante
}

/**
 * Un mail simple: algo, arroba, algo, punto, algo.
 *
 * No se busca cumplir el RFC. Acá un falso negativo sólo significa mostrar el dato como texto
 * en vez de ofrecer el botón de mail, que es una degradación aceptable; un falso positivo
 * abriría el cliente de correo con una dirección inválida.
 */
private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]{2,}$""")

/**
 * Cuántos dígitos alcanzan para considerar que hay código de país.
 *
 * Un celular argentino sin código son 10 dígitos (11 + ocho). Con código de país y el 9 de
 * WhatsApp son 13. El corte en 11 deja pasar a los internacionales cortos sin confundirlos con
 * un número local.
 */
private const val DIGITOS_CON_PAIS = 11

/**
 * Reconoce qué es el contacto.
 *
 * El orden importa: primero mail —un mail no tiene por qué parecerse a un teléfono— y después
 * teléfono, que es el caso más ambiguo porque cualquier cosa con muchos números lo parece.
 */
fun clasificarContacto(contacto: String): ContactoInformante {
    val limpio = contacto.trim()

    if (EMAIL.matches(limpio)) return ContactoInformante.Email(limpio)

    val digitos = limpio.filter(Char::isDigit)
    val soloSimbolosDeTelefono = limpio.all { it.isDigit() || it in " +-()." }

    // Con menos de 8 digitos no es un telefono: es un apodo con numeros, un año, un @usuario.
    if (soloSimbolosDeTelefono && digitos.length >= 8) {
        return ContactoInformante.Telefono(
            crudo = limpio,
            paraLinks = digitos,
            sirveParaWhatsApp = digitos.length >= DIGITOS_CON_PAIS,
        )
    }

    return ContactoInformante.Otro(limpio)
}

/**
 * El link de WhatsApp.
 *
 * `wa.me` exige el número **en formato internacional y sin símbolos**, de ahí que se use
 * [ContactoInformante.Telefono.paraLinks] y no el texto original.
 *
 * **No se le agrega el 9 que WhatsApp pide para los celulares argentinos.** Se podría detectar
 * "empieza con 54, no sigue un 9, tiene largo de celular" y meterlo, pero esa regla se
 * equivoca con los fijos y no aplica a ningún otro país: terminaría armando links rotos con
 * aire de correctos. El formulario del informante pide el número ya con el 9; si igual llega
 * sin él, el link se abre y WhatsApp avisa que no existe, que es un fallo visible y no uno
 * silencioso.
 */
fun whatsAppUrl(telefono: ContactoInformante.Telefono): String =
    "https://wa.me/${telefono.paraLinks}"

fun smsUri(telefono: ContactoInformante.Telefono): String = "smsto:${telefono.paraLinks}"

fun mailtoUri(email: ContactoInformante.Email): String = "mailto:${email.crudo}"

/**
 * Los contactos utilizables de una pista, en el orden en que conviene ofrecerlos.
 *
 * Desde V16 el backend manda mail y teléfono por separado, y ahí no hay nada que
 * interpretar: cada uno se sabe lo que es. `informantContact` es el legado —las pistas
 * anteriores traen todo junto en ese campo— y para ésas sí hay que clasificar el texto.
 *
 * **El legado sólo se usa si no vinieron los campos nuevos.** Con los tres presentes se
 * mostraría dos veces lo mismo, porque una pista vieja migrada tendría el teléfono en los
 * dos lados.
 *
 * El teléfono va primero: escribir por WhatsApp es menos invasivo que un mail formal y suele
 * tener respuesta más rápida, que es lo que importa cuando se está buscando una bici.
 */
fun contactosDe(tip: pbis.bike.finder.data.remote.dto.TipDto): List<ContactoInformante> {
    val nuevos = listOfNotNull(
        tip.informantPhone?.takeIf { it.isNotBlank() },
        tip.informantEmail?.takeIf { it.isNotBlank() },
    )

    if (nuevos.isNotEmpty()) return nuevos.map(::clasificarContacto)

    return listOfNotNull(tip.informantContact?.takeIf { it.isNotBlank() })
        .map(::clasificarContacto)
}

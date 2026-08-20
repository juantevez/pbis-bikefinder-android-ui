package pbis.bike.finder.ui.tips

import pbis.bike.finder.data.remote.dto.TipDto
import pbis.bike.finder.data.remote.dto.TipStatus

/** Sin leer es exactamente `NEW`: es lo que cuenta el badge de la denuncia. */
val TipDto.isUnread: Boolean get() = status == TipStatus.NEW

/** La etiqueta de cada estado, con los mismos textos que el front web. */
val TipStatus.label: String
    get() = when (this) {
        TipStatus.NEW -> "Nueva"
        TipStatus.READ -> "Leída"
        TipStatus.REPLIED -> "Respondida"
        TipStatus.CONVERTED_TO_SIGHTING -> "Convertida"
    }

/**
 * Dónde dice el informante que vio la bici.
 *
 * `locationDescription` llega **vacío**, no nulo, cuando el avistamiento se
 * reportó sólo con GPS, así que hay que mirar el contenido y no la ausencia del
 * campo. En ese caso se caen a las coordenadas: son feas de leer, pero son el
 * dato, y decir "sin ubicación" sobre una pista que sí trae un punto es esconder
 * lo único que permite ir a buscar la bici.
 *
 * @return null si no hay ni descripción ni coordenadas.
 */
fun TipDto.locationText(): String? {
    val described = locationDescription?.trim()
    if (!described.isNullOrEmpty()) return described

    val lat = latitude
    val lon = longitude
    return if (lat != null && lon != null) "$lat, $lon" else null
}

package pbis.bike.finder.ui.common

import java.text.Normalizer

/**
 * Normaliza para comparar: sin acentos, sin mayúsculas, sin espacios de más.
 *
 * Hace falta cada vez que hay que cruzar un nombre de lugar con el catálogo de
 * location-service, y eso pasa en más de un lado: la denuncia lo usa para
 * cruzar lo que devuelve OSM contra el catálogo, y el perfil para reencontrar la
 * ubicación guardada dentro de los desplegables.
 *
 * El catálogo escribe "RAMOS MEJIA" y las otras fuentes "Ramos Mejía": son el
 * mismo lugar, y compararlos carácter a carácter da que no.
 */
internal fun String.foldForMatch(): String =
    Normalizer.normalize(trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

/** `true` si los dos nombres son el mismo lugar. Null nunca coincide con nada. */
internal fun String?.matchesName(other: String?): Boolean =
    this != null && other != null && foldForMatch() == other.foldForMatch()

package pbis.bike.finder.ui.common

/**
 * Cómo se llama cada nivel de la jerarquía territorial, según el dato.
 *
 * Los tres desplegables encadenados (nivel 1 → nivel 2 → localidad) decían fijo
 * "Provincia / Departamento o partido / Localidad", que es el árbol argentino.
 * En Chile el mismo árbol se llama Región → Provincia → Comuna, así que al
 * elegir Chile la pantalla mostraba "Departamento: Copiapó" cuando Copiapó es
 * una provincia y Tierra Amarilla una comuna.
 *
 * La corrección **no** es un `if (país == "Chile")`: location-service ya devuelve
 * el `type` de cada nivel (`REGION`, `PROVINCE`, `COMUNA`, `PARTIDO`…), así que
 * la etiqueta sale del dato y el próximo país que se cargue —Uruguay y Brasil ya
 * están en la tabla `countries`— no necesita tocar la app.
 *
 * Efecto lateral buscado: CABA deja de decir "Departamento o partido" para sus
 * comunas y "Localidad" para sus barrios, porque sus niveles ya venían tipados
 * como `COMUNA` y `NEIGHBORHOOD`. Es lo mismo que ya imprime el PDF de la
 * denuncia.
 *
 * Port de `js/location-labels.js` del front web.
 */
data class LocationLabel(
    val nombre: String,
    /**
     * `articulo` y `plural` existen para que los textos de ayuda concuerden en
     * género y número: "Elegí una región primero", "No se pudieron cargar las
     * comunas". Sin eso saldría "un región" o "regións".
     */
    val plural: String,
    val articulo: String,
)

object LocationLabels {

    /**
     * Los defaults son los textos históricos: si el backend suma un tipo que acá
     * no está mapeado, la pantalla queda como estaba en vez de mostrar el enum
     * crudo.
     */
    val NIVEL1_DEFAULT = LocationLabel("Provincia", "provincias", "una")
    val NIVEL2_DEFAULT = LocationLabel("Departamento o partido", "departamentos", "un")
    val LOCALIDAD_DEFAULT = LocationLabel("Localidad", "localidades", "una")

    private val NIVEL1 = mapOf(
        "PROVINCE" to LocationLabel("Provincia", "provincias", "una"),
        "REGION" to LocationLabel("Región", "regiones", "una"),
        "STATE" to LocationLabel("Estado", "estados", "un"),
        "DEPARTMENT" to LocationLabel("Departamento", "departamentos", "un"),
        "PARTIDO" to LocationLabel("Partido", "partidos", "un"),
        "AUTONOMOUS_CITY" to LocationLabel("Ciudad Autónoma", "ciudades", "una"),
        "FEDERAL_DISTRICT" to LocationLabel("Distrito Federal", "distritos", "un"),
    )

    private val NIVEL2 = mapOf(
        "DEPARTMENT" to LocationLabel("Departamento", "departamentos", "un"),
        "PARTIDO" to LocationLabel("Partido", "partidos", "un"),
        "MUNICIPALITY" to LocationLabel("Municipio", "municipios", "un"),
        "CITY" to LocationLabel("Ciudad", "ciudades", "una"),
        "COMUNA" to LocationLabel("Comuna", "comunas", "una"),
        "DISTRICT" to LocationLabel("Distrito", "distritos", "un"),
        "PROVINCE" to LocationLabel("Provincia", "provincias", "una"),
    )

    /**
     * `CITY`, `TOWN` y `VILLAGE` caen todas en "Localidad" a propósito: a quien
     * carga una denuncia no le aporta que el catálogo distinga ciudad de pueblo,
     * y así la pantalla argentina sigue diciendo exactamente lo que decía antes.
     */
    private val LOCALIDAD = mapOf(
        "CITY" to LocationLabel("Localidad", "localidades", "una"),
        "TOWN" to LocationLabel("Localidad", "localidades", "una"),
        "VILLAGE" to LocationLabel("Localidad", "localidades", "una"),
        "NEIGHBORHOOD" to LocationLabel("Barrio", "barrios", "un"),
        "BARRIO" to LocationLabel("Barrio", "barrios", "un"),
        "COMUNA" to LocationLabel("Comuna", "comunas", "una"),
    )

    fun nivel1(tipo: String?): LocationLabel = NIVEL1[tipo] ?: NIVEL1_DEFAULT
    fun nivel2(tipo: String?): LocationLabel = NIVEL2[tipo] ?: NIVEL2_DEFAULT
    fun localidad(tipo: String?): LocationLabel = LOCALIDAD[tipo] ?: LOCALIDAD_DEFAULT

    /**
     * El tipo común a toda la lista, o `null` si no hay uno solo.
     *
     * La etiqueta del nivel se decide con la lista ya traída, no con el país: las
     * 53 provincias chilenas vienen todas como `PROVINCE` y los 24 partidos
     * bonaerenses como `PARTIDO`. Si alguna vez un país mezcla tipos en el mismo
     * nivel (nada en el modelo lo impide: `type` es un varchar libre), devolver
     * null hace caer la etiqueta al default genérico, que es lo honesto: no hay
     * una sola palabra correcta para esa lista.
     */
    fun <T> tipoComun(items: List<T>, tipo: (T) -> String?): String? {
        val primero = items.firstOrNull()?.let(tipo) ?: return null
        return if (items.all { tipo(it) == primero }) primero else null
    }
}

/** "Elegí una región primero" — el placeholder del nivel de abajo. */
fun LocationLabel.textoPrimero(): String = "Elegí $articulo ${nombre.lowercase()} primero"

/** "No se pudieron cargar las comunas." */
fun LocationLabel.textoError(): String {
    val determinante = if (articulo == "una") "las" else "los"
    return "No se pudieron cargar $determinante $plural."
}

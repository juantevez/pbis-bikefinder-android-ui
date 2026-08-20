package pbis.bike.finder.ui.updatecomponents

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Las tres cosas que el usuario carga de una pieza.
 *
 * El mapa que guarda el backend no tiene esquema y admite cualquier clave; estos
 * son los campos que el formulario edita, y los únicos que se pisan. Todo lo
 * demás que traiga un componente —`specs`, por ejemplo— sobrevive intacto.
 */
data class ComponentEntry(
    val brand: String = "",
    val model: String = "",
    val notes: String = "",
) {
    /** Vacío es "el usuario no cargó nada", que **no** es lo mismo que borrar. */
    val isBlank: Boolean get() = brand.isBlank() && model.isBlank() && notes.isBlank()
}

/** Una pieza editable, con el texto que la describe en la UI. */
data class ComponentField(
    val key: String,
    val label: String,
    val brandHint: String,
    val modelHint: String,
    val notesHint: String,
)

/**
 * Las piezas que el formulario muestra, en el mismo orden que la web.
 *
 * Es exactamente el conjunto que `actualizar-componentes.html` dibuja. La lista
 * `COMPONENT_KEYS` del front declara nueve claves más —`wheelset`, `cassette`,
 * `chain`, `derailleur_front`, `derailleur_rear`, `shifters`, `brake_front`,
 * `brake_rear`, `groupset`— que **no tienen campos en el HTML**: el JS las
 * recorre, no encuentra los inputs y copia el valor original tal cual. Acá esa
 * rama no hace falta como caso especial porque [buildComponentsPayload] arranca
 * copiando todo lo que había; el efecto sobre el payload es el mismo.
 */
val BIKE_COMPONENT_FIELDS: List<ComponentField> = listOf(
    ComponentField(
        "crankset", "Plato/Palanca (Crankset)",
        "Ej: Shimano", "Ej: Ultegra R8100", "Ej: 172.5mm, 52/36t",
    ),
    ComponentField(
        "pedals", "Pedales",
        "Ej: Look", "Ej: Keo Blade Carbon", "Ej: Clipless",
    ),
    ComponentField(
        "front_tire", "Cubierta delantera",
        "Ej: Continental", "Ej: GP5000", "Ej: 700x25c, tubeless",
    ),
    ComponentField(
        "rear_tire", "Cubierta trasera",
        "Ej: Continental", "Ej: GP5000", "Ej: 700x25c, tubeless",
    ),
    ComponentField(
        "front_wheel", "Rueda delantera",
        "Ej: Zipp", "Ej: 303 Firecrest", "Ej: Tubeless, 45mm",
    ),
    ComponentField(
        "rear_wheel", "Rueda trasera",
        "Ej: Zipp", "Ej: 303 Firecrest", "Ej: Tubeless, 45mm",
    ),
    ComponentField(
        "brakes", "Frenos",
        "Ej: Shimano", "Ej: Ultegra BR-R8170", "Ej: Disco hidráulico, 160mm",
    ),
    ComponentField(
        "stem", "Stem",
        "Ej: Zipp", "Ej: Service Course SL", "Ej: 100mm, -6°",
    ),
    ComponentField(
        "handlebar", "Manubrio",
        "Ej: Zipp", "Ej: SL-70 Ergo", "Ej: 42cm, carbono",
    ),
    ComponentField(
        "saddle", "Asiento",
        "Ej: Selle Italia", "Ej: SLR Boost", "Ej: 145mm, carbono",
    ),
    ComponentField(
        "seatpost", "Tija del asiento",
        "Ej: Zipp", "Ej: Service Course SL", "Ej: 27.2mm, 350mm",
    ),
    ComponentField(
        "grips_tape", "Puños / Cinta de manubrio",
        "Ej: Lizard Skins", "Ej: DSP 2.5", "Ej: Negro, 2.5mm",
    ),
    ComponentField(
        "rigid_fork", "Horquilla fija",
        "Ej: Enve", "Ej: Road Fork 2.0", "Ej: Full carbon, tapered",
    ),
    ComponentField(
        "front_suspension", "Suspensión delantera",
        "Ej: Fox", "Ej: 34 Factory", "Ej: 140mm, FIT4",
    ),
    ComponentField(
        "rear_suspension", "Amortiguador trasero",
        "Ej: RockShox", "Ej: SIDLuxe Ultimate", "Ej: 165x45(TR)",
    ),
)

// ── Procedencia ──────────────────────────────────────────────────────────────

private const val SOURCE_CATALOG = "catalog"
private const val SOURCE_USER_MODIFIED = "user_modified"
private const val SOURCE_USER_ADDED = "user_added"

/** Lee los componentes que ya tenía la bici en la forma que usa el formulario. */
fun JsonObject?.toComponentEntries(): Map<String, ComponentEntry> {
    if (this == null) return emptyMap()

    return BIKE_COMPONENT_FIELDS.mapNotNull { field ->
        val value = this[field.key] as? JsonObject ?: return@mapNotNull null
        field.key to ComponentEntry(
            brand = value.text("brand").orEmpty(),
            model = value.text("model").orEmpty(),
            notes = value.text("notes").orEmpty(),
        )
    }.toMap()
}

/**
 * Arma el `components` que va en el PATCH.
 *
 * Es el port de `actualizar-componentes.js:120-175`, y la fidelidad importa más
 * que la elegancia: este mapa **reemplaza** al anterior, y la metadata que se
 * escribe acá es la única memoria de qué pieza vino de fábrica y cuál cambió el
 * usuario. Un port "mejorado" que no coincida con la web deja el mismo dato
 * escrito de dos formas distintas según por dónde lo hayan editado.
 *
 * Las reglas, en el orden en que se aplican a cada pieza cargada:
 *
 *  - **Era de fábrica y no se tocó** → sigue siendo de fábrica. Se preserva su
 *    `source` y sus `specs`.
 *  - **Era de fábrica y cambió** → deja de serlo, y se guarda qué había antes en
 *    `originalBrand` / `originalModel`. Es lo que permite decir "venía con una
 *    Shimano y ahora tiene una SRAM", que en una denuncia por robo es
 *    exactamente lo que identifica a la bici.
 *  - **Ya era modificada** → se mantiene esa historia; sólo se refresca
 *    `updatedAt`.
 *  - **No existía** → pieza agregada por el usuario.
 *
 * Dos cosas que parecen detalles y no lo son:
 *
 *  - **Los campos vacíos no borran.** Si el usuario deja una pieza en blanco, se
 *    conserva lo que ya estaba guardado. Borrar de verdad no está en la web y no
 *    se inventa acá; sin un gesto explícito de "quitar esta pieza", vaciar un
 *    campo sin querer no puede costar el historial.
 *  - **Se copia primero todo lo anterior.** Las claves que el formulario no
 *    edita —las nueve sin UI, y cualquier otra que haya escrito otro cliente—
 *    viajan de vuelta intactas. Sin eso, guardar desde el teléfono borraría
 *    silenciosamente lo que se hubiera cargado desde otro lado.
 */
fun buildComponentsPayload(
    original: JsonObject?,
    edited: Map<String, ComponentEntry>,
    now: Instant,
): JsonObject {
    val result = LinkedHashMap<String, JsonElement>()
    original?.forEach { (key, value) -> result[key] = value }

    for (field in BIKE_COMPONENT_FIELDS) {
        val entry = edited[field.key] ?: ComponentEntry()
        val brand = entry.brand.trim()
        val model = entry.model.trim()
        val notes = entry.notes.trim()

        // Sin nada cargado no se toca lo que había: ya está copiado arriba.
        if (brand.isEmpty() && model.isEmpty() && notes.isEmpty()) continue

        val previous = original?.get(field.key) as? JsonObject
        val wasOriginal = previous?.get("isOriginal")?.jsonPrimitive?.booleanOrNull
        val hasChanged = brand != previous.text("brand").orEmpty() ||
            model != previous.text("model").orEmpty() ||
            notes != previous.text("notes").orEmpty()

        result[field.key] = buildJsonObject {
            if (brand.isNotEmpty()) put("brand", brand)
            if (model.isNotEmpty()) put("model", model)
            if (notes.isNotEmpty()) put("notes", notes)

            when {
                wasOriginal == true && !hasChanged -> {
                    put("isOriginal", true)
                    put("source", previous.text("source").orEmptyFallback(SOURCE_CATALOG))
                    previous?.get("specs")?.let { put("specs", it) }
                }

                wasOriginal == true -> {
                    put("isOriginal", false)
                    put("source", SOURCE_USER_MODIFIED)
                    put("updatedAt", now.toString())
                    previous.text("brand")?.let { put("originalBrand", it) }
                    previous.text("model")?.let { put("originalModel", it) }
                }

                wasOriginal == false -> {
                    put("isOriginal", false)
                    put("source", previous.text("source").orEmptyFallback(SOURCE_USER_MODIFIED))
                    put("updatedAt", now.toString())
                    // La pieza ya venía marcada como modificada: lo que había de
                    // fábrica se arrastra sin recalcularlo. Pisarlo con el valor
                    // actual perdería el dato original en la segunda edición.
                    previous.text("originalBrand")?.takeIf { it.isNotEmpty() }
                        ?.let { put("originalBrand", it) }
                    previous.text("originalModel")?.takeIf { it.isNotEmpty() }
                        ?.let { put("originalModel", it) }
                }

                else -> {
                    put("isOriginal", false)
                    put("source", SOURCE_USER_ADDED)
                    put("updatedAt", now.toString())
                }
            }
        }
    }

    return JsonObject(result)
}

/**
 * Lee un campo de texto del JSON sin esquema.
 *
 * Devuelve null tanto si la clave falta como si su valor no es un texto: el mapa
 * es libre y nada garantiza que lo que hay adentro tenga la forma esperada.
 */
private fun JsonObject?.text(key: String): String? =
    (this?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

/** El `||` de JS: un texto vacío también cae al valor por defecto. */
private fun String?.orEmptyFallback(fallback: String): String =
    if (isNullOrEmpty()) fallback else this

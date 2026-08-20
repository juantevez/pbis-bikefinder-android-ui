package pbis.bike.finder.ui.updatecomponents

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El diff de componentes, contra `actualizar-componentes.js:120-175`.
 *
 * Lo que se protege acá no es el formulario sino el **historial**: qué pieza vino
 * de fábrica y cuál cambió el usuario. Ese dato no se puede recalcular después —
 * si se escribe mal una vez, no hay con qué reconstruirlo— y en una denuncia por
 * robo es parte de lo que identifica a la bici.
 */
class BikeComponentsTest {

    private val now = Instant.parse("2026-08-20T12:00:00Z")

    private fun components(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject

    private fun JsonObject.piece(key: String): JsonObject = this[key] as JsonObject

    private fun JsonObject.str(key: String): String? = (this[key])?.jsonPrimitive?.content

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key])?.jsonPrimitive?.content?.toBooleanStrictOrNull()

    // ── Alta ─────────────────────────────────────────────────────────────────

    @Test
    fun `una pieza que no existia queda como agregada por el usuario`() {
        val result = buildComponentsPayload(
            original = null,
            edited = mapOf("saddle" to ComponentEntry(brand = "Fizik", model = "Arione")),
            now = now,
        )

        val saddle = result.piece("saddle")
        assertEquals("Fizik", saddle.str("brand"))
        assertEquals("Arione", saddle.str("model"))
        assertEquals(false, saddle.bool("isOriginal"))
        assertEquals("user_added", saddle.str("source"))
        assertEquals(now.toString(), saddle.str("updatedAt"))
    }

    @Test
    fun `los campos vacios no se escriben`() {
        val result = buildComponentsPayload(
            original = null,
            edited = mapOf("pedals" to ComponentEntry(brand = "Look", notes = "  ")),
            now = now,
        )

        val pedals = result.piece("pedals")
        assertEquals("Look", pedals.str("brand"))
        assertNull(pedals["model"])
        assertNull(pedals["notes"])
    }

    // ── Piezas de fábrica ────────────────────────────────────────────────────

    @Test
    fun `una pieza de fabrica sin tocar sigue siendo de fabrica`() {
        val original = components(
            """
            {"crankset":{"brand":"Shimano","model":"105","isOriginal":true,
                         "source":"catalog","specs":{"length":"172.5mm"}}}
            """,
        )

        val result = buildComponentsPayload(
            original = original,
            edited = mapOf("crankset" to ComponentEntry(brand = "Shimano", model = "105")),
            now = now,
        )

        val crankset = result.piece("crankset")
        assertEquals(true, crankset.bool("isOriginal"))
        assertEquals("catalog", crankset.str("source"))
        // Las specs del catálogo sobreviven aunque el formulario no las muestre.
        assertEquals(original.piece("crankset")["specs"], crankset["specs"])
        // No se tocó nada: no corresponde marcar una fecha de modificación.
        assertNull(crankset["updatedAt"])
    }

    @Test
    fun `cambiar una pieza de fabrica guarda que habia antes`() {
        val original = components(
            """{"crankset":{"brand":"Shimano","model":"105","isOriginal":true,"source":"catalog"}}""",
        )

        val result = buildComponentsPayload(
            original = original,
            edited = mapOf("crankset" to ComponentEntry(brand = "SRAM", model = "Rival")),
            now = now,
        )

        val crankset = result.piece("crankset")
        assertEquals("SRAM", crankset.str("brand"))
        assertEquals(false, crankset.bool("isOriginal"))
        assertEquals("user_modified", crankset.str("source"))
        assertEquals(now.toString(), crankset.str("updatedAt"))
        assertEquals("Shimano", crankset.str("originalBrand"))
        assertEquals("105", crankset.str("originalModel"))
    }

    @Test
    fun `cambiar solo las notas ya cuenta como modificacion`() {
        val original = components(
            """{"saddle":{"brand":"Fizik","notes":"Negro","isOriginal":true}}""",
        )

        val result = buildComponentsPayload(
            original = original,
            edited = mapOf("saddle" to ComponentEntry(brand = "Fizik", notes = "Rojo")),
            now = now,
        )

        assertEquals(false, result.piece("saddle").bool("isOriginal"))
    }

    // ── Segunda edición ──────────────────────────────────────────────────────

    @Test
    fun `editar de nuevo una pieza ya modificada no pisa el dato de fabrica`() {
        val original = components(
            """
            {"crankset":{"brand":"SRAM","model":"Rival","isOriginal":false,
                         "source":"user_modified","originalBrand":"Shimano",
                         "originalModel":"105","updatedAt":"2026-01-01T00:00:00Z"}}
            """,
        )

        val result = buildComponentsPayload(
            original = original,
            edited = mapOf("crankset" to ComponentEntry(brand = "SRAM", model = "Force")),
            now = now,
        )

        val crankset = result.piece("crankset")
        assertEquals("Force", crankset.str("model"))
        // Lo de fábrica sigue siendo Shimano 105 — no SRAM Rival, que es lo que
        // pasaría si el original se recalculara en cada guardado.
        assertEquals("Shimano", crankset.str("originalBrand"))
        assertEquals("105", crankset.str("originalModel"))
        assertEquals(now.toString(), crankset.str("updatedAt"))
    }

    @Test
    fun `una pieza agregada por el usuario conserva ese origen al editarse`() {
        val original = components(
            """{"pedals":{"brand":"Look","isOriginal":false,"source":"user_added"}}""",
        )

        val result = buildComponentsPayload(
            original = original,
            edited = mapOf("pedals" to ComponentEntry(brand = "Shimano")),
            now = now,
        )

        assertEquals("user_added", result.piece("pedals").str("source"))
    }

    // ── Lo que no se toca ────────────────────────────────────────────────────

    @Test
    fun `dejar los campos vacios conserva la pieza guardada`() {
        val original = components(
            """{"saddle":{"brand":"Fizik","model":"Arione","isOriginal":true}}""",
        )

        val result = buildComponentsPayload(
            original = original,
            edited = mapOf("saddle" to ComponentEntry()),
            now = now,
        )

        // Vaciar un campo no es un gesto de borrado: la pieza vuelve tal cual.
        assertEquals(original["saddle"], result["saddle"])
    }

    @Test
    fun `las claves que el formulario no edita viajan intactas`() {
        val original = components(
            """
            {"groupset":{"brand":"Shimano","model":"Di2","isOriginal":true},
             "cassette":{"brand":"SRAM"},
             "algo_que_no_conocemos":{"x":1}}
            """,
        )

        val result = buildComponentsPayload(
            original = original,
            edited = mapOf("saddle" to ComponentEntry(brand = "Fizik")),
            now = now,
        )

        // Ninguna de estas tiene UI. Si el guardado desde el teléfono las
        // borrara, se perdería en silencio lo cargado desde la web.
        assertEquals(original["groupset"], result["groupset"])
        assertEquals(original["cassette"], result["cassette"])
        assertEquals(original["algo_que_no_conocemos"], result["algo_que_no_conocemos"])
        assertEquals("Fizik", result.piece("saddle").str("brand"))
    }

    @Test
    fun `un valor con forma inesperada no rompe el armado`() {
        // El mapa no tiene esquema: nada garantiza que cada pieza sea un objeto.
        val original = components("""{"saddle":"Fizik Arione"}""")

        val result = buildComponentsPayload(
            original = original,
            edited = mapOf("saddle" to ComponentEntry(brand = "Fizik")),
            now = now,
        )

        val saddle = result.piece("saddle")
        assertEquals("Fizik", saddle.str("brand"))
        assertEquals("user_added", saddle.str("source"))
    }

    // ── Lectura ──────────────────────────────────────────────────────────────

    @Test
    fun `el formulario se precarga con lo que ya tenia la bici`() {
        val original = components(
            """
            {"saddle":{"brand":"Fizik","model":"Arione","notes":"Negro"},
             "groupset":{"brand":"Shimano"}}
            """,
        )

        val entries = original.toComponentEntries()

        assertEquals(ComponentEntry("Fizik", "Arione", "Negro"), entries["saddle"])
        // `groupset` no tiene campos en el formulario: no se precarga.
        assertFalse(entries.containsKey("groupset"))
    }

    @Test
    fun `sin componentes el formulario arranca vacio`() {
        assertTrue(null.toComponentEntries().isEmpty())
    }
}

package pbis.bike.finder.ui.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pbis.bike.finder.data.remote.dto.BicicletaResumenDto

/**
 * Qué bicicletas ofrece cada tarjeta del dashboard.
 *
 * Las dos que quedaron —denunciar y editar componentes— exigen lo mismo:
 * `ACTIVE`. Ofrecerlas sobre una `STOLEN` invita a abrir una segunda denuncia
 * sobre la misma bici, y `STOLEN` tampoco admite ediciones.
 *
 * La baja tenía la regla contraria y más laxa, y ya no vive acá: se fue con la
 * tarjeta "Vendí mi bici" al listado. Su criterio se prueba en
 * `PuedeDarseDeBajaTest`.
 */
class BikeActionTest {

    private fun bike(estado: String?) = BicicletaResumenDto(id = "b1", estado = estado)

    @Test
    fun `una bici activa sirve para las dos acciones`() {
        val activa = bike("ACTIVE")

        assertTrue(BikeAction.UpdateComponents.admite(activa))
        assertTrue(BikeAction.ReportTheft.admite(activa))
    }

    @Test
    fun `una bici robada no se denuncia de nuevo ni se edita`() {
        val robada = bike("STOLEN")

        assertFalse(BikeAction.ReportTheft.admite(robada))
        assertFalse(BikeAction.UpdateComponents.admite(robada))
    }

    @Test
    fun `una bici ya vendida o inactiva no entra en ninguna accion`() {
        listOf("SOLD", "INACTIVE").forEach { estado ->
            BikeAction.entries.forEach { action ->
                assertFalse("$action con $estado", action.admite(bike(estado)))
            }
        }
    }

    @Test
    fun `el estado llega como texto suelto y se compara sin importar mayusculas`() {
        assertTrue(BikeAction.ReportTheft.admite(bike("active")))
        assertTrue(BikeAction.UpdateComponents.admite(bike("Active")))
    }

    @Test
    fun `un estado ausente o desconocido no habilita nada`() {
        // El resumen declara `estado` como String, así que nada garantiza que sea
        // uno de los cuatro del enum. Ante la duda no se ofrece: una acción que
        // el backend va a rechazar es peor que una tarjeta con menos opciones.
        BikeAction.entries.forEach { action ->
            assertFalse(action.admite(bike(null)))
            assertFalse(action.admite(bike("")))
            assertFalse(action.admite(bike("PENDIENTE_DE_ALGO")))
        }
    }
}

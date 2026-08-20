package pbis.bike.finder.ui.dashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pbis.bike.finder.data.remote.dto.BicicletaResumenDto

/**
 * Qué bicicletas ofrece cada tarjeta del dashboard.
 *
 * La regla que importa es la asimetría entre robo y baja: una bici ya denunciada
 * **no** se puede volver a denunciar, pero **sí** se puede dar de baja. Ofrecer
 * la denuncia sobre una `STOLEN` lleva a una segunda denuncia sobre la misma
 * bici; no ofrecer la baja deja al que sufrió el robo con el registro colgado
 * para siempre, porque `STOLEN` no admite ninguna otra edición.
 */
class BikeActionTest {

    private fun bike(estado: String?) = BicicletaResumenDto(id = "b1", estado = estado)

    @Test
    fun `una bici activa sirve para las tres acciones`() {
        val activa = bike("ACTIVE")

        assertTrue(BikeAction.UpdateComponents.admite(activa))
        assertTrue(BikeAction.ReportTheft.admite(activa))
        assertTrue(BikeAction.Sell.admite(activa))
    }

    @Test
    fun `una bici robada se puede dar de baja pero no denunciar de nuevo`() {
        val robada = bike("STOLEN")

        assertTrue(BikeAction.Sell.admite(robada))
        assertFalse(BikeAction.ReportTheft.admite(robada))
        // STOLEN tampoco admite ediciones, así que componentes queda afuera.
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
        assertTrue(BikeAction.Sell.admite(bike("active")))
        assertTrue(BikeAction.Sell.admite(bike("Stolen")))
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

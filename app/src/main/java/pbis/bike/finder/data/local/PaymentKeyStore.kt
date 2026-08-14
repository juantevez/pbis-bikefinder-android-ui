package pbis.bike.finder.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.paymentKeys by preferencesDataStore(name = "payment_idempotency")

/**
 * Guarda la `X-Idempotency-Key` de un intento de pago.
 *
 * La clave identifica **un intento de pago**, no una request: payment-service
 * busca por ella y devuelve el resultado ya guardado en vez de volver a cobrar.
 * Por eso tiene que ser la misma en todos los reintentos del mismo intento, y
 * por eso vive en disco y no en el ViewModel.
 *
 * En el front web esto está en `sessionStorage` para que sobreviva a un F5. El
 * equivalente en Android es peor: al ViewModel no lo mata un F5, lo mata el
 * sistema cuando necesita memoria mientras el usuario está en la app de su
 * banco, que es exactamente el momento de un pago dudoso. Una clave en memoria
 * se perdería ahí, y el reintento cobraría de nuevo.
 *
 * A diferencia de `sessionStorage`, esto **sobrevive a cerrar la app**. Es
 * deliberado: un pago con final incierto sigue siendo incierto al día
 * siguiente. Las claves se borran cuando el intento llega a un final conocido
 * —ver [discard]— así que no se acumulan.
 */
/**
 * El contrato, aparte de la implementación, para que el repositorio de pagos se
 * pueda testear sin DataStore ni `Context`. Es el mismo patrón de
 * `TokenStorage`/`TokenStore`.
 */
interface PaymentKeys {
    suspend fun key(bicycleId: String, plan: String): String
    suspend fun discard(bicycleId: String, plan: String)
}

@Singleton
class PaymentKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : PaymentKeys {
    /**
     * La clave del intento, creándola la primera vez.
     *
     * El slot es (bici, plan): cambiar de plan es otra operación y merece otra
     * clave, o el backend devolvería el resultado del plan anterior.
     */
    override suspend fun key(bicycleId: String, plan: String): String {
        val slot = slotFor(bicycleId, plan)
        context.paymentKeys.data.first()[slot]?.let { return it }

        val fresh = UUID.randomUUID().toString()
        context.paymentKeys.edit { it[slot] = fresh }
        return fresh
    }

    /**
     * Descarta la clave: el próximo pago es una operación nueva.
     *
     * Se llama **sólo** cuando el intento llegó a un final conocido: cobrado o
     * rechazado. Un rechazo también cuenta —quedó guardado con esa clave, así
     * que reusarla devolvería el mismo rechazo para siempre, aunque el usuario
     * pruebe con otra tarjeta.
     *
     * Ante un 503, un fallo de red o cualquier final incierto **no se toca**: es
     * justo el caso en que hay que reusarla.
     */
    override suspend fun discard(bicycleId: String, plan: String) {
        context.paymentKeys.edit { it.remove(slotFor(bicycleId, plan)) }
    }

    private fun slotFor(bicycleId: String, plan: String) =
        stringPreferencesKey("pay:idem:$bicycleId:$plan")
}

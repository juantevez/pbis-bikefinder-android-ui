package pbis.bike.finder.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pbis.bike.finder.data.local.PaymentKeys
import pbis.bike.finder.data.remote.api.PaymentApi
import pbis.bike.finder.data.remote.dto.CreatePaymentRequestDto
import pbis.bike.finder.data.remote.dto.PaymentResponseDto
import pbis.bike.finder.data.remote.dto.PaymentStatus
import pbis.bike.finder.data.remote.dto.SearchPlan
import retrofit2.HttpException
import retrofit2.Response

/**
 * El pago es la única operación de la app donde equivocarse cuesta plata literal.
 *
 * Todo lo que sigue mira una sola cosa: **cuándo se conserva y cuándo se descarta
 * la clave de idempotencia**. Conservarla de más devuelve para siempre un rechazo
 * viejo; descartarla de menos cobra dos veces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentRepositoryTest {

    /** Reemplaza al DataStore: lo que importa es qué claves quedan vivas. */
    private class FakeKeyStore : PaymentKeys {
        val keys = mutableMapOf<String, String>()
        var issued = 0

        override suspend fun key(bicycleId: String, plan: String): String =
            keys.getOrPut("$bicycleId:$plan") { "clave-${++issued}" }

        override suspend fun discard(bicycleId: String, plan: String) {
            keys.remove("$bicycleId:$plan")
        }
    }

    private class FakePaymentApi(
        var onCreate: (String) -> PaymentResponseDto,
        var onGet: () -> PaymentResponseDto = { error("no se esperaba consultar el pago") },
    ) : PaymentApi {
        val keysSeen = mutableListOf<String>()

        override suspend fun createPayment(
            idempotencyKey: String,
            body: CreatePaymentRequestDto,
        ): PaymentResponseDto {
            keysSeen += idempotencyKey
            return onCreate(idempotencyKey)
        }

        override suspend fun payment(paymentId: String) = onGet()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun repository(api: PaymentApi, keyStore: PaymentKeys) =
        PaymentRepository(api, keyStore, json)

    private suspend fun PaymentRepository.pay() = payForPlan(
        bicycleId = "bici-1",
        plan = SearchPlan.VIGIA,
        payerEmail = "juan@example.com",
        cardToken = "tok_1111_123",
        paymentMethodId = "visa",
    )

    private fun completed() = PaymentResponseDto(
        paymentId = "pago-1",
        status = PaymentStatus.COMPLETED,
    )

    private fun httpError(code: Int) = HttpException(
        Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun `un pago cobrado descarta la clave porque el proximo es otra operacion`() = runTest {
        val keyStore = FakeKeyStore()
        val sut = repository(FakePaymentApi(onCreate = { completed() }), keyStore)

        val outcome = sut.pay()

        assertTrue(outcome is PaymentOutcome.Paid)
        assertTrue(keyStore.keys.isEmpty())
    }

    @Test
    fun `un 422 descarta la clave para no repetir el rechazo para siempre`() = runTest {
        // El rechazo quedó guardado con esa clave: reusarla devolvería el mismo
        // "no" aunque el usuario pruebe con otra tarjeta.
        val keyStore = FakeKeyStore()
        val sut = repository(FakePaymentApi(onCreate = { throw httpError(422) }), keyStore)

        val outcome = sut.pay()

        assertTrue(outcome is PaymentOutcome.Rejected)
        assertTrue(keyStore.keys.isEmpty())
    }

    @Test
    fun `un 503 conserva la clave porque el cobro pudo haber ocurrido`() = runTest {
        val keyStore = FakeKeyStore()
        val sut = repository(FakePaymentApi(onCreate = { throw httpError(503) }), keyStore)

        val outcome = sut.pay()

        assertTrue(outcome is PaymentOutcome.Uncertain)
        assertEquals(1, keyStore.keys.size)
    }

    @Test
    fun `reintentar tras un 503 manda la MISMA clave`() = runTest {
        // Es la propiedad entera: el backend deduplica por esa clave, así que
        // una clave nueva sería un segundo cobro.
        val keyStore = FakeKeyStore()
        var first = true
        val api = FakePaymentApi(
            onCreate = { if (first) { first = false; throw httpError(503) } else completed() },
        )
        val sut = repository(api, keyStore)

        sut.pay()
        sut.pay()

        assertEquals(2, api.keysSeen.size)
        assertEquals(api.keysSeen[0], api.keysSeen[1])
    }

    @Test
    fun `reintentar tras un rechazo manda una clave NUEVA`() = runTest {
        val keyStore = FakeKeyStore()
        var first = true
        val api = FakePaymentApi(
            onCreate = { if (first) { first = false; throw httpError(422) } else completed() },
        )
        val sut = repository(api, keyStore)

        sut.pay()
        sut.pay()

        assertTrue(api.keysSeen[0] != api.keysSeen[1])
    }

    @Test
    fun `PROCESSING no alcanza para dar por pagado el plan`() = runTest {
        // El front web trata el 201 como final y manda al usuario a denunciar
        // con un cobro que todavía puede fallar.
        val keyStore = FakeKeyStore()
        val api = FakePaymentApi(
            onCreate = { PaymentResponseDto(paymentId = "pago-1", status = PaymentStatus.PROCESSING) },
            onGet = { completed() },
        )
        val sut = repository(api, keyStore)

        val outcome = sut.pay()

        assertTrue(outcome is PaymentOutcome.Paid)
        assertTrue(keyStore.keys.isEmpty())
    }

    @Test
    fun `un PROCESSING que termina en FAILED es un rechazo`() = runTest {
        val keyStore = FakeKeyStore()
        val api = FakePaymentApi(
            onCreate = { PaymentResponseDto(paymentId = "pago-1", status = PaymentStatus.PROCESSING) },
            onGet = {
                PaymentResponseDto(
                    paymentId = "pago-1",
                    status = PaymentStatus.FAILED,
                    failureReason = "Fondos insuficientes",
                )
            },
        )
        val sut = repository(api, keyStore)

        val outcome = sut.pay()

        assertEquals("Fondos insuficientes", (outcome as PaymentOutcome.Rejected).reason)
        assertTrue(keyStore.keys.isEmpty())
    }

    @Test
    fun `un PROCESSING que nunca resuelve queda incierto y conserva la clave`() = runTest {
        val keyStore = FakeKeyStore()
        val procesando =
            PaymentResponseDto(paymentId = "pago-1", status = PaymentStatus.PROCESSING)
        val sut = repository(
            FakePaymentApi(onCreate = { procesando }, onGet = { procesando }),
            keyStore,
        )

        val outcome = sut.pay()

        // No resolver no es fracasar: el cobro sigue pudiendo estar hecho.
        assertTrue(outcome is PaymentOutcome.Uncertain)
        assertEquals(1, keyStore.keys.size)
    }

    @Test
    fun `la orden externa se deriva de la clave y no de un timestamp`() = runTest {
        // Un reintento tiene que referirse a la misma operación, no inventar una
        // orden nueva que el backend vería como un segundo pago.
        val keyStore = FakeKeyStore()
        var captured: CreatePaymentRequestDto? = null
        val api = object : PaymentApi {
            override suspend fun createPayment(
                idempotencyKey: String,
                body: CreatePaymentRequestDto,
            ): PaymentResponseDto {
                captured = body
                return completed()
            }

            override suspend fun payment(paymentId: String) = completed()
        }

        repository(api, keyStore).pay()

        assertTrue(captured!!.externalOrderId.endsWith("clave-1"))
        assertEquals("9.99", captured!!.amount)
        assertEquals("USD", captured!!.currency)
    }
}

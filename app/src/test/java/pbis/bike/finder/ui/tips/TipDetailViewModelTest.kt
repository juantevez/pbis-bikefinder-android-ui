package pbis.bike.finder.ui.tips

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pbis.bike.finder.data.remote.dto.ConversationDto
import pbis.bike.finder.data.remote.dto.MessageDto
import pbis.bike.finder.data.remote.dto.MessageSentDto
import pbis.bike.finder.data.remote.dto.SendMessageRequestDto
import pbis.bike.finder.data.remote.dto.TipDto
import pbis.bike.finder.data.remote.dto.TipStatus
import pbis.bike.finder.data.repository.TheftRepository
import pbis.bike.finder.testing.StubBicycleApi
import pbis.bike.finder.testing.StubTheftReportApi
import retrofit2.Response

/**
 * El detalle de una pista.
 *
 * Lo que se prueba acá son las dos cosas que pueden hacer daño: que una acción
 * irreversible no salga sin confirmar, y que un envío fallido no se lleve puesto
 * el texto que el usuario escribió.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TipDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val tip = TipDto(
        id = "tip-1",
        sightingDate = LocalDate(2026, 8, 1),
        description = "La vi atada en la plaza",
        canReply = true,
        status = TipStatus.NEW,
    )

    private open class FakeTheftApi : StubTheftReportApi() {
        var tip: TipDto? = null
        var messages: List<MessageDto> = emptyList()
        var conversationCanReply = true
        var replyFails = false
        var sentMessages = mutableListOf<String>()
        var markReadCalls = 0
        var convertCalls = 0

        override suspend fun tip(reportId: String, tipId: String): TipDto = tip!!

        override suspend fun tipConversation(reportId: String, tipId: String) = ConversationDto(
            tipId = tipId,
            messages = messages,
            canReply = conversationCanReply,
        )

        override suspend fun replyToTip(
            reportId: String,
            tipId: String,
            body: SendMessageRequestDto,
        ): MessageSentDto {
            if (replyFails) throw java.io.IOException("sin red")
            sentMessages += body.message
            messages = messages + MessageDto(
                id = "m${messages.size}",
                senderType = "OWNER",
                message = body.message,
            )
            return MessageSentDto(messageId = "m${messages.size}")
        }

        override suspend fun markTipRead(reportId: String, tipId: String): Response<Unit> {
            markReadCalls++
            this.tip = this.tip?.copy(status = TipStatus.READ)
            return Response.success(Unit)
        }

        override suspend fun convertTipToSighting(
            reportId: String,
            tipId: String,
        ): Response<Unit> {
            convertCalls++
            this.tip = this.tip?.copy(status = TipStatus.CONVERTED_TO_SIGHTING)
            return Response.success(Unit)
        }
    }

    private fun viewModel(api: FakeTheftApi): TipDetailViewModel {
        val json = Json { ignoreUnknownKeys = true }
        val repository = TheftRepository(
            bicycleApi = object : StubBicycleApi() {},
            theftApi = api,
            json = json,
        )
        return TipDetailViewModel(repository)
    }

    // ── Carga ────────────────────────────────────────────────────────────────

    @Test
    fun `carga la pista y su conversacion`() = runTest {
        val api = FakeTheftApi().apply {
            tip = this@TipDetailViewModelTest.tip
            messages = listOf(MessageDto(id = "m0", senderType = "INFORMANT", message = "Hola"))
        }
        val vm = viewModel(api)

        vm.start("r1", "tip-1")
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals("tip-1", state.tip?.id)
        assertEquals(1, state.messages.size)
        assertTrue(state.canReply)
    }

    // ── Acciones irreversibles ───────────────────────────────────────────────

    @Test
    fun `convertir no sale sin confirmacion`() = runTest {
        val api = FakeTheftApi().apply { tip = this@TipDetailViewModelTest.tip }
        val vm = viewModel(api)
        vm.start("r1", "tip-1")
        advanceUntilIdle()

        vm.askConfirmation(TipConfirmation.CONVERT)
        advanceUntilIdle()

        // Pedir la confirmación no ejecuta nada: la pista sigue intacta.
        assertEquals(0, api.convertCalls)
        assertEquals(TipConfirmation.CONVERT, vm.state.value.confirming)

        vm.dismissConfirmation()
        advanceUntilIdle()

        // Y cancelar tampoco. Es irreversible: no hay endpoint que la deshaga.
        assertEquals(0, api.convertCalls)
        assertNull(vm.state.value.confirming)
    }

    @Test
    fun `confirmar convierte y refresca el estado de la pista`() = runTest {
        val api = FakeTheftApi().apply { tip = this@TipDetailViewModelTest.tip }
        val vm = viewModel(api)
        vm.start("r1", "tip-1")
        advanceUntilIdle()

        vm.askConfirmation(TipConfirmation.CONVERT)
        vm.confirm()
        advanceUntilIdle()

        assertEquals(1, api.convertCalls)
        assertEquals(TipStatus.CONVERTED_TO_SIGHTING, vm.state.value.tip?.status)
        assertNotNull(vm.state.value.actionDone)
        // Ya convertida: el botón no puede volver a ofrecerse.
        assertFalse(vm.state.value.canConvert)
    }

    @Test
    fun `una pista convertida ya no se puede marcar como leida`() = runTest {
        val api = FakeTheftApi().apply {
            tip = this@TipDetailViewModelTest.tip.copy(status = TipStatus.CONVERTED_TO_SIGHTING)
        }
        val vm = viewModel(api)
        vm.start("r1", "tip-1")
        advanceUntilIdle()

        assertFalse(vm.state.value.canMarkRead)
        assertFalse(vm.state.value.canConvert)
    }

    @Test
    fun `marcar leida deja de ofrecerse despues de hacerlo`() = runTest {
        val api = FakeTheftApi().apply { tip = this@TipDetailViewModelTest.tip }
        val vm = viewModel(api)
        vm.start("r1", "tip-1")
        advanceUntilIdle()
        assertTrue(vm.state.value.canMarkRead)

        vm.askConfirmation(TipConfirmation.MARK_READ)
        vm.confirm()
        advanceUntilIdle()

        assertEquals(1, api.markReadCalls)
        assertFalse(vm.state.value.canMarkRead)
    }

    // ── Respuesta al informante ──────────────────────────────────────────────

    @Test
    fun `enviar limpia el cuadro y trae el hilo actualizado`() = runTest {
        val api = FakeTheftApi().apply { tip = this@TipDetailViewModelTest.tip }
        val vm = viewModel(api)
        vm.start("r1", "tip-1")
        advanceUntilIdle()

        vm.onReplyChange("  ¿Seguís viéndola?  ")
        vm.sendReply()
        advanceUntilIdle()

        assertEquals(listOf("¿Seguís viéndola?"), api.sentMessages)
        assertEquals("", vm.state.value.reply)
        assertEquals(1, vm.state.value.messages.size)
    }

    @Test
    fun `si el envio falla el texto no se pierde`() = runTest {
        val api = FakeTheftApi().apply {
            tip = this@TipDetailViewModelTest.tip
            replyFails = true
        }
        val vm = viewModel(api)
        vm.start("r1", "tip-1")
        advanceUntilIdle()

        vm.onReplyChange("Un mensaje largo que costó escribir")
        vm.sendReply()
        advanceUntilIdle()

        // Vaciar el cuadro ante un error obligaría a reescribir todo: el texto
        // sólo se descarta cuando el servidor confirmó que lo recibió.
        assertEquals("Un mensaje largo que costó escribir", vm.state.value.reply)
        assertNotNull(vm.state.value.replyError)
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun `no se manda un mensaje vacio`() = runTest {
        val api = FakeTheftApi().apply { tip = this@TipDetailViewModelTest.tip }
        val vm = viewModel(api)
        vm.start("r1", "tip-1")
        advanceUntilIdle()

        vm.onReplyChange("   ")
        vm.sendReply()
        advanceUntilIdle()

        assertTrue(api.sentMessages.isEmpty())
    }

    @Test
    fun `un mensaje mas largo que el maximo se rechaza antes de salir`() = runTest {
        val api = FakeTheftApi().apply { tip = this@TipDetailViewModelTest.tip }
        val vm = viewModel(api)
        vm.start("r1", "tip-1")
        advanceUntilIdle()

        vm.onReplyChange("x".repeat(SendMessageRequestDto.MAX_MESSAGE + 1))
        vm.sendReply()
        advanceUntilIdle()

        // El backend lo rechazaría igual; cortarlo acá evita el viaje y explica
        // el problema donde el usuario puede arreglarlo.
        assertTrue(api.sentMessages.isEmpty())
        assertNotNull(vm.state.value.replyError)
    }

    @Test
    fun `si el hilo no carga se respeta lo que dice la pista sobre responder`() = runTest {
        val api = object : FakeTheftApi() {
            override suspend fun tipConversation(
                reportId: String,
                tipId: String,
            ): ConversationDto = throw retrofit2.HttpException(
                Response.error<Unit>(500, "".toResponseBody("application/json".toMediaType())),
            )
        }.apply { tip = this@TipDetailViewModelTest.tip }

        val vm = viewModel(api)
        vm.start("r1", "tip-1")
        advanceUntilIdle()

        // La pista dice canReply=true: un fallo del hilo no puede esconder el
        // cuadro de respuesta de una pista que sí la admite.
        assertTrue(vm.state.value.canReply)
        assertNull(vm.state.value.error)
    }
}

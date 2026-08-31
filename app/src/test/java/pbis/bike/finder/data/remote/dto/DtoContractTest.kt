package pbis.bike.finder.data.remote.dto

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica que los DTOs deserialicen JSON con la forma que emite el backend.
 *
 * Los payloads están escritos a mano siguiendo los `record` de Java, no
 * capturados de un servicio corriendo: cubren la forma y los tipos, no los datos
 * reales. Cuando se pueda levantar el stack, conviene reemplazarlos por
 * respuestas capturadas — es la única forma de detectar que un DTO cambió.
 */
class DtoContractTest {

    private val json = BikeFinderJson

    @Test
    fun `login deserializa y expone los campos de expiracion que el front ignoraba`() {
        val payload = """
            {
              "accessToken": "eyJhbGciOi...",
              "refreshToken": "d290f1ee-6c54-4b01-90e6-d701748f0851",
              "tokenType": "Bearer",
              "expiresIn": 3600,
              "expiresAt": "2026-08-13T18:30:00Z",
              "user": {
                "id": "9b2c1e40-1111-4222-8333-444455556666",
                "email": "juan@example.com",
                "emailVerified": true,
                "fullName": "Juan Pérez",
                "phoneNumber": "+5491122334455",
                "phoneVerified": false,
                "avatarUrl": null,
                "gender": "PREFER_NOT_TO_SAY",
                "birthDate": "1990-05-17",
                "location": {
                  "localityId": 42,
                  "localityName": "Villa Crespo",
                  "departmentName": "Comuna 15",
                  "provinceName": "CABA",
                  "countryName": "Argentina"
                }
              }
            }
        """.trimIndent()

        val res = json.decodeFromString<AuthResponseDto>(payload)

        assertEquals(3600L, res.expiresIn)
        assertEquals("Juan Pérez", res.user?.fullName)
        assertEquals(Gender.PREFER_NOT_TO_SAY, Gender.fromApi(res.user?.gender))
        assertEquals(1990, res.user?.birthDate?.year)
        assertEquals("Villa Crespo", res.user?.location?.localityName)
    }

    @Test
    fun `el challenge de segundo factor deserializa sin tokens`() {
        // La forma real que devuelve /auth/login cuando la cuenta tiene 2FA:
        // 200, mfaRequired en true y TODO lo demás en null. Con accessToken y
        // refreshToken declarados como String no-nulo —como estaban— esto tiraba
        // SerializationException y el login era imposible.
        val payload = """
            {
              "accessToken": null,
              "refreshToken": null,
              "tokenType": null,
              "expiresIn": null,
              "expiresAt": null,
              "user": null,
              "mfaRequired": true,
              "mfaToken": "eyJhbGciOi..."
            }
        """.trimIndent()

        val res = json.decodeFromString<AuthResponseDto>(payload)

        assertTrue(res.mfaRequired)
        assertEquals("eyJhbGciOi...", res.mfaToken)
        assertNull(res.accessToken)
        assertNull(res.refreshToken)
        assertNull(res.user)
    }

    @Test
    fun `una respuesta sin los campos de 2FA sigue siendo un login normal`() {
        // Los defaults tienen que aguantar un backend viejo —o el /auth/refresh,
        // que nunca manda esos campos— sin declarar mfaRequired en false.
        val payload = """
            { "accessToken": "abc", "refreshToken": "def" }
        """.trimIndent()

        val res = json.decodeFromString<AuthResponseDto>(payload)

        assertEquals("abc", res.accessToken)
        assertTrue(!res.mfaRequired)
        assertNull(res.mfaToken)
    }

    @Test
    fun `un campo nuevo en el backend no rompe la deserializacion`() {
        // ignoreUnknownKeys: el backend puede sumar campos sin coordinar un
        // release del cliente. Sin esto, agregar una property a un record de
        // Java tira la app en runtime.
        val payload = """
            { "id": "1", "email": "a@b.com", "campoQueTodaviaNoExiste": 123 }
        """.trimIndent()

        val user = json.decodeFromString<UserInfoDto>(payload)

        assertEquals("a@b.com", user.email)
    }

    @Test
    fun `la lista de bicis es plana y el detalle anida en frame`() {
        val lista = """
            {
              "bicycles": [
                {
                  "id": "aaa",
                  "brandName": "Trek",
                  "model": "Marlin 7",
                  "year": 2023,
                  "serialNumber": "WTU123",
                  "primaryColor": "Negro",
                  "status": "ACTIVE",
                  "updatedAt": "2026-08-01T10:00:00Z"
                }
              ],
              "total": 1
            }
        """.trimIndent()

        val detalle = """
            {
              "id": "aaa",
              "ownerId": "bbb",
              "registrationType": "CATALOG",
              "frame": {
                "brandId": 7,
                "brandName": "Trek",
                "model": "Marlin 7",
                "year": 2023,
                "size": "M",
                "serialNumber": "WTU123"
              },
              "bikeTypeId": 3,
              "colors": { "primaryColorId": 1, "primaryColor": "Negro" },
              "components": { "crankset": { "brand": "Shimano", "isOriginal": true } },
              "originalComponents": { "crankset": { "brand": "Shimano" } },
              "distinguishingMarks": [],
              "photos": [],
              "purchaseInfo": { "purchasePrice": "850000.50", "currency": "ARS" },
              "status": "ACTIVE",
              "createdAt": "2026-07-01T09:00:00Z",
              "updatedAt": "2026-08-01T10:00:00Z"
            }
        """.trimIndent()

        val resumen = json.decodeFromString<BicycleListResponseDto>(lista)
        val bici = json.decodeFromString<BicycleDto>(detalle)

        // El mismo dato vive en dos lugares distintos según el endpoint.
        assertEquals("Trek", resumen.bicycles.first().brandName)
        assertEquals("Trek", bici.frame?.brandName)
        assertEquals(BicycleStatus.ACTIVE, bici.status)
        assertEquals(RegistrationType.CATALOG, bici.registrationType)
    }

    @Test
    fun `los montos conservan los decimales exactos`() {
        val payload = """{ "purchasePrice": "850000.50", "currency": "ARS" }"""

        val info = json.decodeFromString<PurchaseInfoDto>(payload)
        val monto = info.purchasePrice!!.toBigDecimalAmount()

        // Con Double esto sería 850000.4999999999... El String preserva el valor
        // que mandó el backend, que en pagos es plata real.
        assertEquals("850000.50", monto.toPlainString())
    }

    @Test
    fun `la pista sin recompensa no expone el contacto del informante`() {
        val payload = """
            {
              "id": "t1",
              "theftReportId": "r1",
              "sightingDate": "2026-08-10",
              "sightingTimeApprox": "por la tarde",
              "description": "La vi atada en Corrientes y Medrano",
              "canReply": true,
              "status": "NEW",
              "submittedAt": "2026-08-10T17:45:00",
              "latitude": -34.6,
              "longitude": -58.42,
              "informantContact": null
            }
        """.trimIndent()

        val tip = json.decodeFromString<TipDto>(payload)

        assertEquals(TipStatus.NEW, tip.status)
        // Sólo viene cuando la denuncia ofrece recompensa (OwnerTipController).
        assertNull(tip.informantContact)
        // submittedAt no trae zona: se resuelve contra la del backend, no la del
        // dispositivo. Con el teléfono en otro huso, la hora de la pista se
        // correría — y en una denuncia la hora es evidencia.
        val instante = tip.submittedAt!!.toBackendInstant()
        assertEquals("2026-08-10T20:45:00Z", instante.toString())
    }

    @Test
    fun `el token de conversacion viene en la respuesta al enviar una pista`() {
        // Es el campo que el front web descarta, dejando al informante sin
        // forma de seguir el hilo que él mismo pidió con wantsReply.
        val payload = """
            {
              "tipId": "t1",
              "conversationToken": "conv_9f8e7d6c",
              "message": "Gracias por tu ayuda"
            }
        """.trimIndent()

        val res = json.decodeFromString<TipSubmittedDto>(payload)

        assertEquals("conv_9f8e7d6c", res.conversationToken)
    }

    @Test
    fun `PROCESSING no es un estado terminal`() {
        val payload = """
            {
              "paymentId": "p1",
              "externalOrderId": "theft-abc-SABUESO-key1",
              "status": "PROCESSING",
              "amount": "18.99",
              "currency": "USD"
            }
        """.trimIndent()

        val pago = json.decodeFromString<PaymentResponseDto>(payload)

        assertEquals(PaymentStatus.PROCESSING, pago.status)
        // La app no puede dar por pagado el plan con esta respuesta.
        assertTrue(!pago.status!!.isTerminal)
    }

    @Test
    fun `el resumen del dashboard mapea el campo con tilde`() {
        val payload = """
            {
              "totalBicicletas": 2,
              "totalComponentes": 14,
              "totalReportesActivos": 1,
              "estadoCuenta": "Activa",
              "bicicletas": [
                {
                  "id": "aaa",
                  "marca": "Trek",
                  "modelo": "Marlin 7",
                  "año": "2023",
                  "estado": "ACTIVE",
                  "totalComponentes": 7
                }
              ]
            }
        """.trimIndent()

        val resumen = json.decodeFromString<ResumenUsuarioDto>(payload)

        assertEquals("2023", resumen.bicicletas.first().anio)
        assertEquals(2, resumen.totalBicicletas)
    }

    @Test
    fun `el 503 sin campo retry se trata como inseguro`() {
        val payload = """
            {
              "error": "SERVICE_UNAVAILABLE",
              "message": "No se pudo completar el registro. El servicio tardó más de 10s en responder.",
              "timestamp": "2026-08-13T12:00:00Z"
            }
        """.trimIndent()

        val err = json.decodeFromString<ApiErrorDto>(payload)

        // Sólo la primera frase va a la UI; el detalle técnico queda para el log.
        assertEquals("No se pudo completar el registro.", err.userMessage)
        // Sin `retry`, asumir que la operación PUDO haberse completado.
        assertEquals(RetryAdvice.Unsafe, RetryAdvice.from(err.retry))
    }

    @Test
    fun `el error de auth-service no trae error sino code`() {
        // Payload REAL, capturado de POST /auth/login contra el backend corriendo.
        // Es la única forma de error de este documento que no fue deducida: los
        // errores de auth-service no tienen campo `error`, y sí tienen `code`.
        val payload = """
            {
              "status": 401,
              "code": "INVALID_CREDENTIALS",
              "message": "Email o contraseña incorrectos",
              "timestamp": "2026-08-13T15:45:23.934394924Z",
              "exception": null,
              "rootCause": null
            }
        """.trimIndent()

        val err = json.decodeFromString<ApiErrorDto>(payload)

        assertNull(err.error)
        // `code` es el único discriminador legible por máquina: ramificar por él
        // y no por el texto de `message`, que está en español y puede cambiar.
        assertEquals("INVALID_CREDENTIALS", err.code)
        assertEquals(401, err.status)
        assertEquals("Email o contraseña incorrectos", err.userMessage)
    }

    @Test
    fun `las preferencias de notificacion se mandan completas`() {
        // El PUT reemplaza el estado completo: los booleanos no son nullables
        // para que explicitNulls = false no los borre del payload.
        val req = NotificationPreferencesRequestDto(
            emailEnabled = true,
            whatsappNumber = null,
            whatsappEnabled = false,
            telegramChatId = null,
            telegramEnabled = false,
            locale = "es-AR",
        )

        val serializado = json.encodeToString(req)

        assertTrue(serializado.contains("\"emailEnabled\":true"))
        assertTrue(serializado.contains("\"whatsappEnabled\":false"))
        assertTrue(serializado.contains("\"telegramEnabled\":false"))
    }

    @Test
    fun `el perfil omite los nulls porque el backend los lee como no tocar`() {
        val req = UpdateProfileRequestDto(fullName = "Juan Pérez", phoneNumber = null)

        val serializado = json.encodeToString(req)

        assertTrue(serializado.contains("fullName"))
        assertTrue(!serializado.contains("phoneNumber"))
    }

    /**
     * Regresión: el wrapper de localidades nombra la lista `localities`, no
     * `items` como los otros dos niveles.
     *
     * Leerlo como `items` no fallaba —el default deja la lista vacía— así que el
     * desplegable de localidad quedaba siempre vacío, sin error visible. Como el
     * punto del mapa alcanza para enviar, la denuncia viajaba sin `localityId` y
     * el PDF público salía sin ubicación: es el único campo que ese PDF puede
     * mostrar.
     *
     * El test del ViewModel no lo agarraba porque arma el DTO en Kotlin y nunca
     * pasa por el JSON.
     */
    @Test
    fun `la lista de localidades viene en localities y no en items`() {
        val payload = """
            {
              "localities": [
                {
                  "id": 9,
                  "adminLevel2Id": 3,
                  "name": "Ramos Mejia",
                  "type": "CIUDAD",
                  "postalCode": "1704",
                  "latitude": -34.64,
                  "longitude": -58.56
                }
              ],
              "total": 1,
              "adminLevel2Id": 3
            }
        """.trimIndent()

        val res = json.decodeFromString<LocalityListResponseDto>(payload)

        assertEquals(1, res.localities.size)
        assertEquals("Ramos Mejia", res.localities.first().name)
        assertEquals(-34.64, res.localities.first().latitude!!, 0.001)
    }

    @Test
    fun `los niveles 1 y 2 siguen viniendo en items`() {
        val level1 = json.decodeFromString<AdminLevel1ListResponseDto>(
            """{"items":[{"id":1,"name":"Buenos Aires","type":"Provincia"}],"total":1,"countryId":1}""",
        )
        val level2 = json.decodeFromString<AdminLevel2ListResponseDto>(
            """{"items":[{"id":3,"adminLevel1Id":1,"name":"La Matanza","type":"Partido"}],"total":1}""",
        )

        assertEquals("Buenos Aires", level1.items.single().name)
        assertEquals("La Matanza", level2.items.single().name)
    }

    /** La búsqueda trae la jerarquía desnormalizada: es lo que resuelve el `localityId`. */
    @Test
    fun `la busqueda de localidades trae provincia y partido en cada resultado`() {
        val payload = """
            {
              "results": [
                {
                  "id": 9,
                  "name": "Ramos Mejia",
                  "latitude": -34.64,
                  "longitude": -58.56,
                  "fullName": "Ramos Mejia, Buenos Aires, Argentina",
                  "adminLevel2": {"id": 3, "name": "La Matanza", "type": "Partido"},
                  "adminLevel1": {"id": 1, "name": "Buenos Aires", "type": "Provincia"},
                  "country": {"id": 1, "name": "Argentina", "isoCode": "AR"}
                }
              ],
              "total": 1,
              "query": "Ramos Mejia"
            }
        """.trimIndent()

        val res = json.decodeFromString<LocalitySearchResponseDto>(payload)

        assertEquals(1, res.adminLevel1Id())
        assertEquals(3, res.results.single().adminLevel2?.id)
    }

    private fun LocalitySearchResponseDto.adminLevel1Id() = results.single().adminLevel1?.id

    /**
     * Regresión: `installments` vale 1 por default y kotlinx-serialization omite
     * los defaults, así que el payload salía sin el campo y el backend —que lo
     * marca `@NotNull`— mataba **todo** pago con
     * `400 VALIDATION_ERROR — installments is required`.
     *
     * El front web no lo sufre porque arma el JSON a mano.
     */
    @Test
    fun `el pago manda installments aunque valga el default`() {
        val req = CreatePaymentRequestDto(
            externalOrderId = "theft-bici-1-sabueso-clave",
            amount = "18.99",
            currency = "USD",
            payerEmail = "juan@example.com",
            description = "Plan Sabueso",
            cardToken = "tok_7008_369",
            paymentMethodId = "master",
        )

        val serializado = json.encodeToString(req)

        assertTrue(serializado.contains("\"installments\":1"))
    }

    /**
     * Regresión: el backend manda los importes como **número** JSON —Jackson
     * serializa así los `BigDecimal`— y el DTO los declara `String`, que
     * kotlinx-serialization se niega a leer desde un número.
     *
     * El síntoma no fue un campo vacío: fue un pago **cobrado** que la app
     * reportó como "no se pudo interpretar la respuesta". El 201 traía
     * `COMPLETED` y la deserialización moría antes de que nadie lo mirara.
     */
    @Test
    fun `el monto del pago se lee aunque venga sin comillas`() {
        val payload = """
            {
              "paymentId": "62c27a61-31a7-4e54-a8e6-41ebe2592a49",
              "status": "COMPLETED",
              "amount": 18.99,
              "currency": "USD",
              "gatewayReference": "STUB-73f55aed",
              "failureReason": null
            }
        """.trimIndent()

        val res = json.decodeFromString<PaymentResponseDto>(payload)

        assertEquals(PaymentStatus.COMPLETED, res.status)
        // Se conserva el texto exacto: pasar por Double daría 18.989999999999998.
        assertEquals("18.99", res.amount)
    }

    @Test
    fun `el monto tambien se lee si viniera entrecomillado`() {
        val res = json.decodeFromString<PaymentResponseDto>(
            """{"paymentId":"p-1","amount":"18.99"}""",
        )

        assertEquals("18.99", res.amount)
    }

    @Test
    fun `la recompensa de la denuncia tiene el mismo tratamiento`() {
        val res = json.decodeFromString<RewardDto>("""{"offered":true,"amount":250.00}""")

        assertEquals("250.00", res.amount)
    }

    @Test
    fun `el telefono se valida contra el mismo regex que el backend`() {
        assertTrue(E164_REGEX.matches("+5491122334455"))
        assertTrue(!E164_REGEX.matches("11 2233-4455"))
        assertTrue(!E164_REGEX.matches("+0491122334455"))
    }
}

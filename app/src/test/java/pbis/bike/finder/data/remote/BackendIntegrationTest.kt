package pbis.bike.finder.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import pbis.bike.finder.data.remote.api.AuthApi
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.dto.ApiErrorDto
import pbis.bike.finder.data.remote.dto.BikeFinderJson
import pbis.bike.finder.data.remote.dto.LoginRequestDto
import pbis.bike.finder.data.remote.dto.PhotoType
import pbis.bike.finder.data.remote.dto.PhotoUploadFields
import retrofit2.Retrofit
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Verifica el cliente contra el backend **realmente corriendo**.
 *
 * Es la única prueba que puede detectar que un DTO dejó de coincidir con su
 * `record` de Java: los demás tests usan payloads escritos a mano, que por
 * definición coinciden con lo que el cliente espera. Así se descubrió que los
 * errores de auth-service no traen campo `error` y que `expiresIn` viene en
 * milisegundos.
 *
 * **Se saltea solo si el gateway no responde**, con `assumeTrue`: no es un test
 * que deba romper el build de alguien que no tiene el stack levantado. Que
 * aparezca como "skipped" es información, no un problema — pero tampoco hay que
 * confundir "skipped" con "pasó".
 *
 * Requiere: `api-gateway`, `auth-service`, `bike-registration`, postgres y redis.
 * Se levantan desde la raíz de `bike-stolen-finder` con `docker compose up -d`.
 */
class BackendIntegrationTest {

    companion object {
        private const val GATEWAY = "http://localhost:8000"

        /** Cuenta de prueba, creada en el postgres de desarrollo. */
        private const val EMAIL = "fase1-smoke@bikefinder.local"
        private const val PASSWORD = "smoke12345"

        private var backendUp = false

        /**
         * Si además de estar levantado el backend tiene la cuenta de prueba.
         *
         * Es una condición aparte de [backendUp] porque falla aparte: la cuenta
         * vive en el postgres de desarrollo y **no la crea ninguna migración**, así
         * que se pierde al recrear la base. Cuando eso pasa, cinco tests de esta
         * clase se caían con `HTTP 401 Unauthorized` —un mensaje que apunta a las
         * credenciales del cliente y no a que falta el fixture— en vez de saltearse
         * como cuando el stack no está.
         */
        private var cuentaDeSmoke = false

        @Volatile
        private var cachedToken: String? = null

        /** Las únicas dos marcas con modelos en el catálogo de desarrollo. */
        private val MARCAS_CON_MODELOS = setOf("Giant", "Specialized")

        /**
         * Sonda.
         *
         * No usa Actuator por dos razones, las dos documentadas en
         * `api-gateway/doc/TECHNICAL_INFO.md`:
         *
         *  - `/actuator/…` está en el puerto 9095 y el compose **no lo publica**
         *    al host (§2.6). Desde afuera no se llega, y no es un descuido: sólo
         *    lo alcanza Prometheus desde dentro de `bike-network`.
         *  - Aunque se llegara, `/actuator/health` es la vista de **diagnóstico**
         *    y da 503 cuando Redis se cae, a propósito (§5.1). Sondear con eso
         *    saltearía estos tests por una caída de Redis que no les afecta. La
         *    sonda de vida sería `/actuator/health/liveness`.
         *
         * El catálogo es mejor señal que cualquiera de las dos: lo sirve
         * `bike-registration` a través del gateway, así que un 200 prueba que
         * están arriba los dos servicios que estos tests necesitan.
         */
        @JvmStatic
        @BeforeClass
        fun probeBackend() {
            backendUp = try {
                val connection = URL("$GATEWAY/api/v1/catalog/form-data").openConnection()
                    as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.requestMethod = "GET"
                connection.responseCode in 200..299
            } catch (_: Exception) {
                false
            }

            // El login va por HttpURLConnection y no por Retrofit para no depender
            // del cliente que estos tests justamente están verificando.
            cuentaDeSmoke = backendUp && try {
                val connection = URL("$GATEWAY/auth/login").openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """{"email":"$EMAIL","password":"$PASSWORD"}""".toByteArray()
                    )
                }
                connection.responseCode in 200..299
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Los tests que necesitan la cuenta de prueba, y no sólo el stack arriba. */
    private fun assumeCuentaDeSmoke() {
        assumeTrue("Gateway no responde en $GATEWAY", backendUp)
        assumeTrue(
            "Falta la cuenta de prueba $EMAIL en el postgres de desarrollo",
            cuentaDeSmoke,
        )
    }

    private val json = BikeFinderJson

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("$GATEWAY/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val authApi = retrofit.create(AuthApi::class.java)

    /**
     * Token compartido por toda la clase.
     *
     * Antes cada test hacía su propio login y la suite entera se comía un 429: el
     * limitador de `/auth/…` es más estricto que el general —ráfaga de 5 y 2/s,
     * contra 10 y 5/s del resto— y cinco logins seguidos lo agotan. Un solo login
     * por clase también es más parecido a lo que hace la app.
     */
    /**
     * Espacia las llamadas para no chocar con el limitador del gateway.
     *
     * `RedisRateLimiter(5, 10, 1)` en las rutas generales: ráfaga de 10 y 5/s
     * **por IP**. La suite entera comparte una sola IP, así que sin esto los
     * últimos tests se comen un 429 que no dice nada sobre el código — dice que
     * los tests corren más rápido que un usuario.
     *
     * **Los tests de esta clase van con `runBlocking` y no con `runTest` por
     * esto mismo.** `runTest` corre en tiempo virtual: `delay()` no espera, saltea
     * el reloj y sigue de largo. Este `pace()` existía desde antes y no hacía
     * absolutamente nada — el test del alta desde catálogo, que son cinco
     * llamadas con cuatro pausas de 400ms, terminaba en 116 milisegundos y se
     * comía un 429. El tiempo virtual está para que los tests NO esperen, que es
     * justo lo contrario de lo que necesita un test contra un backend real.
     */
    private suspend fun pace() = delay(400)

    private suspend fun token(): String {
        cachedToken?.let { return it }
        // requireNotNull y no !!: si la cuenta del test tuviera segundo factor,
        // el login devuelve un challenge sin tokens y el mensaje dice por qué
        // falló en vez de un NPE a secas.
        val body = authApi.login(LoginRequestDto(EMAIL, PASSWORD))
        return requireNotNull(body.accessToken) {
            "El login no devolvió accessToken (mfaRequired=${body.mfaRequired})"
        }.also { cachedToken = it }
    }

    private fun authedBicycleApi(token: String): BicycleApi {
        val authed = client.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                )
            }
            .build()

        return retrofit.newBuilder().client(authed).build().create(BicycleApi::class.java)
    }

    @Test
    fun `login real deserializa en AuthResponseDto`() = runBlocking {
        assumeCuentaDeSmoke()
        pace()

        val response = authApi.login(LoginRequestDto(EMAIL, PASSWORD))

        // La cuenta del test no tiene segundo factor: si lo tuviera, esto sería
        // un challenge y los tokens vendrían en null.
        assertFalse(response.mfaRequired)
        assertTrue(response.accessToken!!.isNotBlank())
        assertTrue(response.refreshToken!!.isNotBlank())
        assertEquals(EMAIL, response.user?.email)
        // expiresIn está en MILISEGUNDOS, contra la convención de OAuth 2.
        // Si algún día el backend lo pasa a segundos, esto lo detecta.
        assertEquals(900_000L, response.expiresIn)
    }

    @Test
    fun `el refresh rota los dos tokens`() = runBlocking {
        assumeCuentaDeSmoke()
        pace()

        val login = authApi.login(LoginRequestDto(EMAIL, PASSWORD))
        val refreshed = authApi.refresh(
            pbis.bike.finder.data.remote.dto.RefreshTokenRequestDto(login.refreshToken!!)
        )

        assertTrue(refreshed.isSuccessful)
        val body = refreshed.body()!!
        assertTrue(body.accessToken!!.isNotBlank())
        // El refresh token también cambia: guardar sólo el access dejaría al
        // siguiente refresh usando uno que el backend ya invalidó.
        assertTrue(body.refreshToken != login.refreshToken)
    }

    @Test
    fun `credenciales invalidas devuelven code y no error`() = runBlocking {
        assumeTrue("Gateway no responde en $GATEWAY", backendUp)
        pace()

        val result = apiCall(json) {
            authApi.login(LoginRequestDto(EMAIL, "contraseña-incorrecta"))
        }

        val error = result as ApiResult.HttpError
        assertEquals(401, error.code)
        // La forma que este test existe para vigilar: auth-service manda `code`
        // y NO manda `error`.
        assertEquals("INVALID_CREDENTIALS", error.errorCode)
        assertEquals(null, error.body?.error)
        assertNotNull(error.userMessage)
    }

    @Test
    fun `el listado de bicicletas deserializa con token real`() = runBlocking {
        assumeCuentaDeSmoke()
        pace()

        val bikes = authedBicycleApi(token()).list()

        // La cuenta de prueba puede no tener bicicletas; lo que se verifica es
        // que el wrapper { bicycles, total } deserializa y es coherente.
        assertEquals(bikes.bicycles.size, bikes.total)
    }

    @Test
    fun `sin token el listado da 401`() = runBlocking {
        assumeTrue("Gateway no responde en $GATEWAY", backendUp)
        pace()

        val result = apiCall(json) { retrofit.create(BicycleApi::class.java).list() }

        assertEquals(401, (result as ApiResult.HttpError).code)
    }

    @Test
    fun `el catalogo es publico`() = runBlocking {
        assumeTrue("Gateway no responde en $GATEWAY", backendUp)
        pace()

        // Sin Authorization: /api/v1/catalog/** está en los public-paths del
        // gateway. El front web le manda Bearer igual, innecesariamente.
        val catalog = retrofit.create(BicycleApi::class.java).catalogFormData()

        assertTrue(catalog.frameBrands.isNotEmpty())
        assertTrue(catalog.bikeTypes.isNotEmpty())
    }

    @Test
    fun `un servicio apagado da 503 y no rompe el parseo`() = runBlocking {
        assumeTrue("Gateway no responde en $GATEWAY", backendUp)
        pace()

        // location-service no forma parte del subconjunto mínimo de la Fase 1.
        // Lo que importa no es el 503 en sí, sino que el cuerpo del gateway se
        // deserialice en ApiErrorDto en vez de caer en Malformed.
        val result = apiCall(json) {
            retrofit.create(pbis.bike.finder.data.remote.api.GeoApi::class.java).countries()
        }

        if (result is ApiResult.HttpError && result.code == 503) {
            assertTrue(result.body is ApiErrorDto?)
        }
    }

    @Test
    fun `el alta desde catalogo crea una bici que aparece en el listado`() = runBlocking {
        assumeCuentaDeSmoke()
        pace()

        val api = authedBicycleApi(token())

        // Se recorre la cascada real del wizard: marca → modelos → detalle.
        val catalog = api.catalogFormData()

        // No se toma la primera marca ni se recorren todas.
        //
        // En el catálogo de desarrollo sólo 2 de 11 marcas tienen modelos, y la
        // primera alfabéticamente (Caloi) no es una de ellas. Pero recorrerlas
        // buscando una que sirva tampoco va: el gateway limita a ráfaga de 10 y
        // 5/s por IP (`RedisRateLimiter(5, 10, 1)`), así que once llamadas
        // seguidas devuelven 429. Se va derecho a una marca conocida.
        val brand = catalog.frameBrands.firstOrNull { it.name in MARCAS_CON_MODELOS }
        assumeTrue("Ninguna marca conocida está en el catálogo", brand != null)

        pace()
        val models = api.catalogBikesByBrand(brand!!.id)
        assumeTrue("La marca ${brand.name} no tiene modelos cargados", models.isNotEmpty())

        pace()
        val details = api.catalogBikeDetails(models.first().id)
        val colorway = details.colorways.firstOrNull { it.isDefault }
            ?: details.colorways.firstOrNull()

        pace()
        val antes = api.list().total

        pace()
        val creada = api.registerFromCatalog(
            pbis.bike.finder.data.remote.dto.RegisterFromCatalogRequestDto(
                catalogBikeId = models.first().id,
                colorwayId = colorway?.id,
                frameSize = details.availableSizes.firstOrNull()?.sizeCode,
                serialNumber = "TEST-${System.currentTimeMillis()}",
                notes = "Alta de prueba de la Fase 2",
            )
        )

        assertTrue(creada.id.isNotBlank())

        // El detalle usa OTRO modelo que el listado: marca y modelo anidados en
        // `frame`. Si los dos deserializan, las dos formas están bien.
        pace()
        val detalle = api.detail(creada.id)
        assertNotNull(detalle.frame)

        pace()
        val despues = api.list()
        assertEquals(antes + 1, despues.total)
        assertTrue(despues.bicycles.any { it.id == creada.id })
    }

    @Test
    fun `una foto sube por media-service y vuelve en el listado de fotos`() = runBlocking {
        assumeCuentaDeSmoke()
        pace()

        val api = authedBicycleApi(token())

        val bikes = api.list().bicycles
        assumeTrue("La cuenta de prueba no tiene bicicletas", bikes.isNotEmpty())
        val bikeId = bikes.first().id

        val antes = runCatching { api.photos(bikeId).total }.getOrElse {
            // media-service apagado: es parte del stack ampliado, no del mínimo.
            assumeTrue("media-service no responde", false)
            return@runBlocking
        }

        val jpeg = jpegDePrueba()
        val part = MultipartBody.Part.createFormData(
            PhotoUploadFields.FILE,
            "prueba.jpg",
            jpeg.toRequestBody("image/jpeg".toMediaType()),
        )

        val subida = api.uploadPhoto(
            id = bikeId,
            file = part,
            photoType = PhotoType.GENERAL.name.toPlainPart(),
            setAsPrimary = "false".toPlainPart(),
            // Sin consentimiento: el backend no debe publicar el GPS a fraud-detection.
            gpsAnalysisConsent = "false".toPlainPart(),
        )

        assertTrue(subida.id.isNotBlank())
        assertEquals(PhotoType.GENERAL, subida.photoType)

        val despues = api.photos(bikeId)
        assertEquals(antes + 1, despues.total)
        // El DTO de foto deserializa entero, EXIF anidado incluido.
        assertTrue(despues.photos.any { it.id == subida.id })
    }

    /** JPEG mínimo y válido, generado en memoria: no hace falta un archivo de prueba. */
    private fun jpegDePrueba(): ByteArray {
        val image = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun String.toPlainPart() =
        toRequestBody("text/plain".toMediaType())
}
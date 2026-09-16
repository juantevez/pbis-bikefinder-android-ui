package pbis.bike.finder.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RequestIdInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder().addInterceptor(RequestIdInterceptor()).build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get(build: Request.Builder.() -> Unit = {}): String? {
        server.enqueue(MockResponse())
        client.newCall(Request.Builder().url(server.url("/bikes")).apply(build).build())
            .execute().close()
        return server.takeRequest().getHeader(HEADER_REQUEST_ID)
    }

    @Test
    fun `cada request sale con un X-Request-Id que el gateway acepta`() {
        val id = get()

        assertNotNull("tiene que ir el header", id)
        // Si no tuviera forma de UUID, RequestIdFilter lo descartaría en silencio
        // y el id que quedó en logcat no serviría para buscar en Loki.
        UUID.fromString(id)
    }

    @Test
    fun `dos requests no comparten id`() {
        assertNotEquals(get(), get())
    }

    @Test
    fun `respeta el id que ya trae la request`() {
        // El reintento del TokenAuthenticator sale con el header de la request
        // que dio 401; pisarlo separaría en Loki dos intentos de la misma cosa.
        val id = get { header(HEADER_REQUEST_ID, "id-del-primer-intento") }

        assertEquals("id-del-primer-intento", id)
    }
}

package pbis.bike.finder.data.remote

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pbis.bike.finder.data.remote.dto.BikeFinderJson
import retrofit2.HttpException
import retrofit2.Response

class ApiResultRequestIdTest {

    private fun errorDelGateway(code: Int, requestId: String?): HttpException {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("http://localhost/bikes").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("")
            .apply { if (requestId != null) header(HEADER_REQUEST_ID, requestId) }
            .build()
        val body = """{"error":"x","message":"y"}""".toResponseBody("application/json".toMediaType())
        return HttpException(Response.error<Unit>(body, raw))
    }

    @Test
    fun `el X-Request-Id de la respuesta llega al HttpError`() = runTest {
        val result = apiCall(BikeFinderJson) { throw errorDelGateway(503, "abc-123") }

        assertTrue(result is ApiResult.HttpError)
        assertEquals("abc-123", (result as ApiResult.HttpError).requestId)
        assertEquals(503, result.code)
    }

    @Test
    fun `sin header en la respuesta, el requestId es null y el error se arma igual`() = runTest {
        val result = apiCall(BikeFinderJson) { throw errorDelGateway(500, null) }

        assertTrue(result is ApiResult.HttpError)
        assertNull((result as ApiResult.HttpError).requestId)
    }
}

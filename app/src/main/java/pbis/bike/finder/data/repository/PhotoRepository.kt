package pbis.bike.finder.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.apiCall
import pbis.bike.finder.data.remote.api.BicycleApi
import pbis.bike.finder.data.remote.dto.PhotoType
import pbis.bike.finder.data.remote.dto.PhotoUploadFields
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Una foto elegida por el usuario, todavía sin subir.
 *
 * La URI va como `String` y no como [Uri] a propósito: `Uri` es una clase de
 * Android y no existe en un test de JVM, así que tenerla acá obligaba a traer
 * Robolectric para probar reglas que no tienen nada de Android — cuál foto es la
 * principal, qué pasa al quitarla. La conversión ocurre en el borde, al leer el
 * archivo.
 */
data class PendingPhoto(
    val uri: String,
    val photoType: PhotoType = PhotoType.GENERAL,
    val isPrimary: Boolean = false,
)

/** Cuántas entraron y cuántas no. Subir fotos nunca invalida el alta. */
data class PhotoUploadOutcome(val uploaded: Int, val failed: Int)

@Singleton
class PhotoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: BicycleApi,
    private val json: Json,
    private val rescaler: ImageRescaler,
) : PhotoUploader {
    /**
     * Sube las fotos de una bici **ya creada**.
     *
     * De a [SUBIDAS_EN_PARALELO] y no todas juntas: son archivos de varios MB y
     * el cuello es el ancho de subida del teléfono, no el servidor. Diez a la vez
     * se reparten el mismo caño y no terminan antes; de a tres se cubre la
     * latencia de ida y vuelta sin saturar. Es el mismo número que usa el front
     * web, por la misma razón.
     *
     * **Nunca lanza.** Una foto que falla no invalida el alta: la bicicleta ya
     * existe cuando esto corre, y decirle al usuario que el registro falló porque
     * no entró una foto sería mentirle.
     */
    override suspend fun uploadAll(
        bicycleId: String,
        photos: List<PendingPhoto>,
        gpsAnalysisConsent: Boolean,
    ): PhotoUploadOutcome = coroutineScope {
        val gate = Semaphore(SUBIDAS_EN_PARALELO)

        val results = photos.map { photo ->
            async { gate.withPermit { upload(bicycleId, photo, gpsAnalysisConsent) } }
        }.map { it.await() }

        PhotoUploadOutcome(
            uploaded = results.count { it },
            failed = results.count { !it },
        )
    }

    private suspend fun upload(
        bicycleId: String,
        photo: PendingPhoto,
        gpsAnalysisConsent: Boolean,
    ): Boolean {
        val uri = photo.uri.toUri()
        val original = readBytes(uri, gpsAnalysisConsent) ?: return false
        val mimeOriginal = context.contentResolver.getType(uri) ?: "image/jpeg"

        // Reescalado recién acá y no al elegir la foto: la vista previa sale del
        // original, y si el reescalado falla se sube el original sin que el
        // usuario se entere de nada. Ver [ImageRescaler] — conserva el EXIF, que es
        // de donde salen el GPS (con consentimiento) y los datos de cámara.
        val reescalada = withContext(Dispatchers.Default) {
            rescaler.rescale(mimeOriginal, original)
        }
        val bytes = reescalada ?: original
        val mime = if (reescalada != null) "image/jpeg" else mimeOriginal

        val part = MultipartBody.Part.createFormData(
            PhotoUploadFields.FILE,
            fileName(uri, mime),
            bytes.toRequestBody(mime.toMediaTypeOrNull()),
        )

        val result = apiCall(json) {
            api.uploadPhoto(
                id = bicycleId,
                file = part,
                photoType = photo.photoType.name.toPlainPart(),
                setAsPrimary = photo.isPrimary.toString().toPlainPart(),
                gpsAnalysisConsent = gpsAnalysisConsent.toString().toPlainPart(),
            )
        }

        return result is ApiResult.Success
    }

    /**
     * Lee la imagen.
     *
     * Cuando hay consentimiento se pide el **original** con
     * [MediaStore.setRequireOriginal]: desde Android 10, el sistema le quita la
     * ubicación a las fotos que entrega, salvo que la app tenga
     * `ACCESS_MEDIA_LOCATION` y la pida explícitamente. Sin esto el checkbox de
     * consentimiento sería decorativo — el usuario autoriza analizar un GPS que
     * el sistema operativo ya borró antes de que la foto salga del teléfono.
     *
     * Si pedir el original falla (permiso denegado, o una URI que no es de
     * MediaStore) se cae a la lectura normal: mejor subir la foto sin GPS que no
     * subirla.
     */
    private suspend fun readBytes(uri: Uri, wantsOriginal: Boolean): ByteArray? =
        withContext(Dispatchers.IO) {
            val target = if (wantsOriginal) {
                runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
            } else {
                uri
            }

            runCatching {
                context.contentResolver.openInputStream(target)!!.use { it.readBytes() }
            }.recoverCatching {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            }.getOrNull()
        }

    private fun fileName(uri: Uri, mime: String): String {
        val extension = mime.substringAfterLast('/', "jpg").ifBlank { "jpg" }
        return "${uri.lastPathSegment?.takeLast(24)?.filter(Char::isLetterOrDigit) ?: "foto"}.$extension"
    }

    private fun String.toPlainPart() = toRequestBody("text/plain".toMediaTypeOrNull())

    private companion object {
        const val SUBIDAS_EN_PARALELO = 3
    }
}

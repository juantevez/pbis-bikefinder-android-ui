package pbis.bike.finder.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reescala una foto antes de subirla, conservando su EXIF.
 *
 * Es el port de `js/reescalar-imagen.js` del front web, y acá pesa más que allá:
 * una foto de un teléfono son 12 Mpx y ~6MB, y para lo que se usa —el detalle de
 * la bici y el cartel PDF, donde entra a menos de 1000pt— eso es entre cinco y
 * diez veces más de lo necesario. Subir desde el celular es lo más lento del
 * alta, y el PDF hoy reescala del lado del servidor, que es lo que lo acerca al
 * timeout.
 *
 * **El EXIF es la parte delicada.** El bitmap reescalado sale sin metadatos:
 * pierde GPS, fecha, marca y modelo de cámara. Eso no es cosmético —media-service
 * los extrae, el GPS viaja con consentimiento explícito y fraud-detection los
 * usa— así que los tags del original se copian al JPEG nuevo.
 *
 * A diferencia del navegador, acá **no se toca la orientación**: `BitmapFactory`
 * no aplica la rotación del EXIF al decodificar, así que los píxeles salen como
 * estaban y el tag original sigue siendo verdad. Corregirlo a 1, como hace la
 * versión web (donde `createImageBitmap` sí rota), dejaría las fotos acostadas.
 *
 * También resuelve el HEIC del iPhone y de varios Android, que `media-service` no
 * sabe leer: al reencodear siempre sale un JPEG. En API < 28 el sistema no
 * decodifica HEIF y no hay nada que hacer — se sube el original, igual que ante
 * cualquier otro fallo.
 */
@Singleton
class ImageRescaler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Devuelve el JPEG reescalado, o `null` si conviene subir el original.
     *
     * **Nunca lanza.** Ante cualquier problema devuelve null y el llamador sube
     * el archivo tal cual, que es lo que pasaba antes de que esto existiera:
     * fallar el reescalado no puede costar una foto.
     */
    fun rescale(mime: String, bytes: ByteArray): ByteArray? = try {
        rescaleOrThrow(mime, bytes)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        // Una foto grande decodificada a bitmap son varios cientos de MB. Que se
        // acabe la memoria no puede tumbar un alta ya hecha.
        null
    }

    private fun rescaleOrThrow(mime: String, bytes: ByteArray): ByteArray? {
        val esHeic = mime.startsWith("image/heic") || mime.startsWith("image/heif")

        // El HEIC se convierte siempre, por chico que sea: el problema ahí no es el
        // tamaño sino que del otro lado nadie lo puede abrir.
        if (!esHeic) {
            if (mime !in FORMATOS_REESCALABLES) return null
            if (bytes.size < MINIMO_PARA_REESCALAR) return null
        }

        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, medidas)
        val ladoMayor = maxOf(medidas.outWidth, medidas.outHeight)
        if (ladoMayor <= 0) return null
        if (ladoMayor <= LADO_MAXIMO && !esHeic) return null

        // inSampleSize baja la resolución al decodificar, así que el bitmap grande
        // nunca llega a existir en memoria. Es potencia de 2 y siempre por encima
        // del objetivo; el ajuste fino lo hace el escalado de abajo.
        val opciones = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(ladoMayor)
        }
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opciones)
            ?: return null

        val escala = LADO_MAXIMO.toFloat() / maxOf(original.width, original.height)
        val escalado = if (escala >= 1f) {
            original
        } else {
            Bitmap.createScaledBitmap(
                original,
                Math.round(original.width * escala),
                Math.round(original.height * escala),
                true,
            ).also { if (it !== original) original.recycle() }
        }

        val salida = ByteArrayOutputStream()
        val comprimio = escalado.compress(Bitmap.CompressFormat.JPEG, CALIDAD_JPEG, salida)
        escalado.recycle()
        if (!comprimio) return null

        val jpeg = conExifDe(bytes, salida.toByteArray()) ?: salida.toByteArray()

        // Si el "reescalado" no achicó nada, se manda el original: conserva su EXIF
        // sin que lo toque nadie. Con el HEIC no aplica —el original no sirve—.
        if (!esHeic && jpeg.size >= bytes.size) return null

        return jpeg
    }

    /** La potencia de 2 más grande que deja el lado mayor sobre [LADO_MAXIMO]. */
    private fun sampleSize(ladoMayor: Int): Int {
        var muestra = 1
        while (ladoMayor / (muestra * 2) >= LADO_MAXIMO) muestra *= 2
        return muestra
    }

    /**
     * Copia al JPEG nuevo los tags del original.
     *
     * El EXIF se lee de los **bytes ya leídos** y no reabriendo la URI: cuando hay
     * consentimiento de GPS, [PhotoRepository] pidió el original con
     * `setRequireOriginal`, y una segunda lectura de la misma URI volvería sin
     * ubicación. Copiar de ahí borraría en silencio justo el dato que el usuario
     * autorizó a analizar.
     *
     * [ExifInterface] sólo escribe sobre un archivo, así que el JPEG pasa por
     * `cacheDir` y se lee de vuelta. Devuelve null si algo falla: se sube el
     * reescalado sin metadatos, que sigue siendo mejor que no subir nada.
     */
    private fun conExifDe(origenBytes: ByteArray, jpeg: ByteArray): ByteArray? = try {
        val origen = ByteArrayInputStream(origenBytes).use { ExifInterface(it) }

        val temporal = File.createTempFile("reescalada", ".jpg", context.cacheDir)
        try {
            temporal.writeBytes(jpeg)
            val destino = ExifInterface(temporal.absolutePath)
            TAGS_A_CONSERVAR.forEach { tag ->
                origen.getAttribute(tag)?.let { destino.setAttribute(tag, it) }
            }
            destino.saveAttributes()
            temporal.readBytes()
        } finally {
            temporal.delete()
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Lado mayor del resultado. 2000px cubre el detalle y el PDF con aire. */
        const val LADO_MAXIMO = 2000

        /** Debajo de esto el reescalado costaría más de lo que ahorra. */
        const val MINIMO_PARA_REESCALAR = 1.5 * 1024 * 1024

        const val CALIDAD_JPEG = 85

        val FORMATOS_REESCALABLES = setOf("image/jpeg", "image/png", "image/webp")

        /**
         * Qué se copia del EXIF original.
         *
         * No es "todo": [ExifInterface] no ofrece copiar el bloque entero como sí
         * podía el front web, hay que enumerar. Están los dos grupos que el
         * backend realmente lee —la ubicación, que va con consentimiento, y los
         * datos de cámara y fecha que usa fraud-detection— y nada más.
         *
         * `TAG_ORIENTATION` se copia tal cual a propósito: ver el comentario de
         * clase.
         */
        val TAGS_A_CONSERVAR = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
        )
    }
}

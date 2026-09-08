package pbis.bike.finder.data.repository

/**
 * Subida de fotos de una bicicleta.
 *
 * Existe como interfaz por la misma razón que [TokenStorage]: la regla de que
 * una foto fallida **no** invalida un alta ya hecha es lógica de producto, y
 * merece tests que corran en la JVM sin un `Context` ni un `ContentResolver` de
 * por medio.
 */
interface PhotoUploader {
    suspend fun uploadAll(
        bicycleId: String,
        photos: List<PendingPhoto>,
        gpsAnalysisConsent: Boolean,
    ): PhotoUploadOutcome

    /**
     * Sube una sola foto a una bici que ya existe y ya tiene fotos.
     *
     * Devuelve tres estados y no un booleano porque acá la diferencia importa:
     * ver [PhotoUploadResult].
     */
    suspend fun uploadOne(
        bicycleId: String,
        photo: PendingPhoto,
        gpsAnalysisConsent: Boolean,
    ): PhotoUploadResult
}

/**
 * Cómo terminó la subida de una foto.
 *
 * [INCIERTA] no es un caso de [RECHAZADA] con otro nombre: el servidor puede
 * haberla guardado y haberse perdido sólo la respuesta —pasó el 4/9/2026 en la
 * web: la foto quedó en S3 y en la base, con miniatura, y el teléfono mostró
 * error—. Decir "no se pudo subir" ahí es mentir, y el usuario que reintenta
 * termina con la foto duplicada.
 */
enum class PhotoUploadResult {
    /** El servidor confirmó que la guardó. */
    OK,

    /** El servidor la rechazó con un status: no quedó guardada. */
    RECHAZADA,

    /** La request no llegó, o se perdió la respuesta: no se sabe si quedó. */
    INCIERTA,
}

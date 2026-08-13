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
}

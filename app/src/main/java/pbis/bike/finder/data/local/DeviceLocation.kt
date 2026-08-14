package pbis.bike.finder.data.local

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Un punto del teléfono, ya redondeado para viajar. */
data class DevicePoint(val latitude: Double, val longitude: Double)

/**
 * De dónde salen las coordenadas del "usar mi ubicación".
 *
 * Es una interfaz para que el ViewModel se pueda testear sin un teléfono: el
 * GPS es de las pocas cosas que no se pueden simular con un doble de red.
 */
interface DeviceLocationProvider {

    /** `null` si no hay proveedor disponible o si no se pudo fijar la posición. */
    suspend fun currentPoint(): DevicePoint?
}

/**
 * Implementación sobre `LocationManager`, sin Google Play Services.
 *
 * Se eligió así para no arrastrar una dependencia entera —y una que no está en
 * todos los teléfonos— por un botón. `LocationManagerCompat.getCurrentLocation()`
 * da el equivalente a un `getCurrentLocation` moderno hasta `minSdk 24`.
 *
 * Pide una posición nueva y no `getLastKnownLocation()` a secas: la última
 * conocida puede ser de hace horas y de otra ciudad, y acá el dato termina en
 * una denuncia. La última conocida sólo se usa como respaldo si la fija falla.
 */
@Singleton
class SystemLocationProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceLocationProvider {

    // El permiso lo pide la pantalla antes de llamar acá; sin él, el sistema
    // tira SecurityException y se devuelve null como cualquier otro fallo.
    @SuppressLint("MissingPermission")
    override suspend fun currentPoint(): DevicePoint? {
        val manager = context.getSystemService<LocationManager>() ?: return null
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            ?: return null

        val executor = Executor { it.run() }

        val fresh = runCatching {
            suspendCancellableCoroutine { continuation ->
                val signal = androidx.core.os.CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    executor,
                ) { location -> continuation.resume(location) }
            }
        }.getOrNull()

        val location = fresh
            ?: runCatching { manager.getLastKnownLocation(provider) }.getOrNull()

        return location?.toPoint()
    }
}

/**
 * Redondea a 7 decimales, ~1 cm.
 *
 * No es privacidad —esa la aplica el backend al publicar el robo, redondeando a
 * ~1 km— sino no mandar los quince decimales que devuelve el sistema, que son
 * precisión inventada.
 */
private fun Location.toPoint() = DevicePoint(
    latitude = round7(latitude),
    longitude = round7(longitude),
)

internal fun round7(value: Double): Double = Math.round(value * 1e7) / 1e7

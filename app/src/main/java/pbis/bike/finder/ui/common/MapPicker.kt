package pbis.bike.finder.ui.common

import android.preference.PreferenceManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

/** Punto de partida cuando no hay nada elegido: Buenos Aires, igual que el front web. */
private val DEFAULT_CENTER = GeoPoint(-34.6037, -58.3816)
private const val DEFAULT_ZOOM = 12.0
private const val PLACED_ZOOM = 16.0

/**
 * Mapa para marcar un punto.
 *
 * Es osmdroid sobre tiles de OpenStreetMap: los mismos que usa el Leaflet del
 * front web. Se eligió sobre Google Maps porque no necesita API key ni un
 * proyecto de Google Cloud con facturación — para marcar un punto, esa
 * infraestructura no se paga sola.
 *
 * El marcador se pone tocando el mapa y se puede arrastrar. Cada vez que cambia,
 * `onPointChanged` recibe las coordenadas ya redondeadas a 7 decimales: mandar
 * los quince que devuelve el mapa es precisión inventada.
 *
 * `centerOn` mueve la cámara sin tocar el marcador. Sirve para el atajo de
 * "elegiste una localidad, te llevo ahí", que es distinto de "marcaste el lugar
 * del robo": lo primero es navegación, lo segundo es un dato de la denuncia.
 */
@Composable
fun MapPicker(
    latitude: Double?,
    longitude: Double?,
    centerOn: Pair<Double, Double>?,
    onPointChanged: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // osmdroid guarda su cache de tiles en disco y exige que se lo configure
    // antes de inflar el MapView. El User-Agent es requisito de la política de
    // uso de OSM: el genérico de Android está bloqueado.
    remember {
        Configuration.getInstance().apply {
            load(context, PreferenceManager.getDefaultSharedPreferences(context))
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = context.cacheDir.resolve("osmdroid-tiles")
        }
    }

    val currentOnPointChanged by rememberUpdatedState(onPointChanged)

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(DEFAULT_CENTER)
        }
    }

    // El MapView tiene ciclo de vida propio: sin este par la app se queda con
    // hilos de descarga de tiles vivos después de salir de la pantalla.
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxWidth().height(260.dp),
        update = { map ->
            map.overlays.clear()

            map.overlays.add(
                MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            p ?: return false
                            currentOnPointChanged(round7(p.latitude), round7(p.longitude))
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint?) = false
                    },
                ),
            )

            if (latitude != null && longitude != null) {
                val point = GeoPoint(latitude, longitude)
                map.overlays.add(
                    Marker(map).apply {
                        position = point
                        isDraggable = true
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = "Acá fue"
                        setOnMarkerDragListener(
                            object : Marker.OnMarkerDragListener {
                                override fun onMarkerDrag(marker: Marker) = Unit
                                override fun onMarkerDragStart(marker: Marker) = Unit
                                override fun onMarkerDragEnd(marker: Marker) {
                                    currentOnPointChanged(
                                        round7(marker.position.latitude),
                                        round7(marker.position.longitude),
                                    )
                                }
                            },
                        )
                    },
                )
            }

            map.invalidate()
        },
    )

    // Centrar es un efecto y no parte del `update`: si moviera la cámara en cada
    // recomposición, el usuario no podría desplazar el mapa — cada gesto se
    // desharía solo.
    val point = if (latitude != null && longitude != null) latitude to longitude else null
    DisposableEffect(point ?: centerOn) {
        (point ?: centerOn)?.let { (lat, lng) ->
            mapView.controller.animateTo(GeoPoint(lat, lng))
            mapView.controller.setZoom(PLACED_ZOOM)
        }
        onDispose { }
    }
}

/** ~1 cm. No es privacidad —esa la aplica el backend al publicar— sino no inventar precisión. */
private fun round7(value: Double): Double = Math.round(value * 1e7) / 1e7

package pbis.bike.finder

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import javax.inject.Provider

/**
 * Además de arrancar Hilt, le da a Coil **el** ImageLoader de la app.
 *
 * Coil, librado a sí mismo, se construye un cliente HTTP propio. Eso alcanza
 * para una imagen pública, pero acá las fotos de las bicis salen por
 * `/api/files/download`, que exige el Bearer y vive en un host que se decide en
 * runtime. Con el loader por defecto, cada foto daría 401 y la galería quedaría
 * vacía sin ningún error visible.
 *
 * El `Provider` evita construir el loader —y con él toda la cadena de red— en el
 * `onCreate` de la aplicación: Coil lo pide la primera vez que hay una imagen
 * que cargar, que es cuando corresponde pagarlo.
 */
@HiltAndroidApp
class BikeFinderApp : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var imageLoader: Provider<ImageLoader>

    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader = imageLoader.get()
}

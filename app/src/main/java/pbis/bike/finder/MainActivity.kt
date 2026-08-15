package pbis.bike.finder

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.ui.navigation.BikeFinderNavHost
import pbis.bike.finder.ui.theme.BikeFinderTheme
import pbis.bike.finder.ui.theme.LocalThemeController
import pbis.bike.finder.ui.theme.ThemeController
import pbis.bike.finder.ui.theme.isDark
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var sessionManager: SessionManager

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preference by viewModel.theme.collectAsStateWithLifecycle()

            // Mientras no se sabe qué tema pidió el usuario no se pinta nada. Es
            // la misma espera que la de los tokens y dura lo mismo: el spinner de
            // abajo ya cubre ese instante.
            preference?.let { themePreference ->
                val dark = themePreference.isDark()

                // `enableEdgeToEdge()` del arranque decide los iconos de las
                // barras del sistema mirando el tema del *dispositivo*, no el
                // nuestro. Con el teléfono en oscuro y la app en claro eso deja
                // iconos blancos sobre fondo crema: ilegibles. Se vuelve a
                // aplicar acá cada vez que cambia el tema efectivo, forzando la
                // detección a nuestro valor.
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(
                            Color.TRANSPARENT,
                            Color.TRANSPARENT,
                        ) { dark },
                        navigationBarStyle = SystemBarStyle.auto(
                            NavigationBarLightScrim,
                            NavigationBarDarkScrim,
                        ) { dark },
                    )
                    onDispose { }
                }

                val controller = remember(themePreference) {
                    ThemeController(themePreference, viewModel::onThemeChange)
                }

                BikeFinderTheme(preference = themePreference) {
                    CompositionLocalProvider(LocalThemeController provides controller) {
                        val start by viewModel.startDestination.collectAsStateWithLifecycle()

                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            // Mientras no se sabe si hay sesión no se pinta ningún
                            // destino: montar el NavHost con el destino equivocado
                            // y corregirlo después haría parpadear el login en
                            // cada apertura.
                            when (val destination = start) {
                                null -> Box(Modifier.fillMaxSize()) {
                                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                                }

                                else -> BikeFinderNavHost(
                                    sessionManager = sessionManager,
                                    startDestination = destination,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Los mismos scrims que usa `enableEdgeToEdge()` sin argumentos. Están copiados
// porque androidx los declara privados, y pasar transparente en su lugar dejaría
// la barra de navegación por gestos sin contraste sobre contenido claro.
private val NavigationBarLightScrim = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NavigationBarDarkScrim = Color.argb(0x80, 0x1b, 0x1b, 0x1b)

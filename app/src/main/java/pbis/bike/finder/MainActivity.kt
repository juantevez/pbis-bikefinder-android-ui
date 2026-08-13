package pbis.bike.finder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.ui.navigation.BikeFinderNavHost
import pbis.bike.finder.ui.theme.BikeFinderTheme
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
            BikeFinderTheme {
                val start by viewModel.startDestination.collectAsStateWithLifecycle()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Mientras no se sabe si hay sesión no se pinta ningún destino:
                    // montar el NavHost con el destino equivocado y corregirlo
                    // después haría parpadear el login en cada apertura.
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

package pbis.bike.finder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.ui.navigation.Route
import javax.inject.Inject

/**
 * Decide dónde arranca la app.
 *
 * `null` mientras se lee el almacenamiento: leer los tokens es I/O, y pintar el
 * login antes de saber si hay sesión produce un parpadeo del formulario en cada
 * apertura de la app para un usuario que ya entró.
 *
 * Tener refresh token guardado no garantiza que la sesión sirva — puede estar
 * vencido. No se valida acá a propósito: eso costaría una request antes de la
 * primera pantalla. Si no vale, la primera llamada da 401, el `TokenAuthenticator`
 * intenta renovar y, si el servidor lo rechaza, `SessionManager` emite el evento
 * que devuelve al login. El camino ya existe.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    authRepository: AuthRepository,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<Route?>(null)
    val startDestination: StateFlow<Route?> = _startDestination.asStateFlow()

    init {
        viewModelScope.launch {
            val hasSession = authRepository.hasSession.first()
            // Con sesión se entra por el dashboard, no por el listado de bicis.
            //
            // Arrancar en `MyBikes` dejaba el dashboard **inalcanzable**: es la
            // raíz del back stack y desde el listado no se navega hacia él, así
            // que "atrás" cerraba la app. Con el dashboard fuera de alcance
            // quedaban fuera también las cuatro acciones que sólo viven ahí
            // —denunciar, componentes, mis denuncias— hasta cerrar sesión.
            //
            // El dashboard es el hub en el front web (`login → dashboard.html`)
            // y el listado se abre desde una de sus tarjetas.
            _startDestination.value = if (hasSession) Route.Dashboard else Route.Landing
        }
    }
}

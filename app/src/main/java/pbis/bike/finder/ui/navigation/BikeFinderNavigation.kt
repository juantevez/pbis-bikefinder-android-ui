package pbis.bike.finder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import pbis.bike.finder.data.remote.SessionEvent
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.ui.bikes.BikesScreen
import pbis.bike.finder.ui.login.LoginScreen

/**
 * Rutas de la app, tipadas.
 *
 * Cada una corresponde a una página del front web. Las que reciben un token
 * (`TipForm`, `Conversation`) llegan por deep link y no navegando: son links que
 * alguien compartió con un tercero que no tiene cuenta.
 *
 * **Fuera de alcance**: `admin-reviews` y `dashboard-admin`. Son tablas densas de
 * revisión para uso interno; siguen siendo web.
 */
sealed interface Route {

    @Serializable
    data object Landing : Route

    @Serializable
    data object Dashboard : Route

    @Serializable
    data object MyBikes : Route

    @Serializable
    data class BikeDetail(val bikeId: String) : Route

    @Serializable
    data object AddBike : Route

    @Serializable
    data class UpdateComponents(val bikeId: String) : Route

    @Serializable
    data class Subscription(val bikeId: String) : Route

    @Serializable
    data class ReportTheft(val bikeId: String, val plan: String? = null) : Route

    @Serializable
    data class TipsList(val reportId: String) : Route

    @Serializable
    data class TipDetail(val reportId: String, val tipId: String) : Route

    @Serializable
    data object Profile : Route

    /** Deep link público: "¿viste esta bicicleta?". Sin login. */
    @Serializable
    data class TipForm(val token: String) : Route

    /** Deep link público: hilo entre el dueño y quien reportó la pista. */
    @Serializable
    data class Conversation(val token: String) : Route
}

/**
 * Grafo de navegación.
 *
 * Las pantallas todavía no existen: esto fija el mapa de rutas y el punto donde
 * se engancha el cierre de sesión. Cada fase siguiente reemplaza un
 * `PlaceholderScreen` por la pantalla real.
 */
@Composable
fun BikeFinderNavHost(
    sessionManager: SessionManager,
    navController: NavHostController = rememberNavController(),
    startDestination: Route = Route.Landing,
) {
    // La red no navega: emite un evento y la navegación decide. En el front web
    // el módulo de sesión hacía `window.location.href = '/index.html'`, que
    // funciona pero ata una capa a la otra.
    LaunchedEffect(sessionManager) {
        sessionManager.events.collect { event ->
            when (event) {
                SessionEvent.Expired -> navController.navigate(Route.Landing) {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable<Route.Landing> {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(Route.MyBikes) {
                        // Se saca el login del back stack: volver atrás después de
                        // entrar tiene que salir de la app, no mostrar de nuevo el
                        // formulario de una sesión que ya está abierta.
                        popUpTo(Route.Landing) { inclusive = true }
                    }
                },
            )
        }
        composable<Route.Dashboard> { PlaceholderScreen("Dashboard") }
        composable<Route.MyBikes> {
            BikesScreen(onBikeClick = { navController.navigate(Route.BikeDetail(it)) })
        }
        composable<Route.BikeDetail> { PlaceholderScreen("Detalle de bicicleta") }
        composable<Route.AddBike> { PlaceholderScreen("Cargar bicicleta") }
        composable<Route.UpdateComponents> { PlaceholderScreen("Actualizar componentes") }
        composable<Route.Subscription> { PlaceholderScreen("Plan de búsqueda") }
        composable<Route.ReportTheft> { PlaceholderScreen("Reportar robo") }
        composable<Route.TipsList> { PlaceholderScreen("Pistas recibidas") }
        composable<Route.TipDetail> { PlaceholderScreen("Detalle de pista") }
        composable<Route.Profile> { PlaceholderScreen("Perfil") }
        composable<Route.TipForm> { PlaceholderScreen("Reportar avistamiento") }
        composable<Route.Conversation> { PlaceholderScreen("Conversación") }
    }
}

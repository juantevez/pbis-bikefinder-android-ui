package pbis.bike.finder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import pbis.bike.finder.data.remote.SessionEvent
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.ui.addbike.AddBikeScreen
import pbis.bike.finder.ui.bikes.BikesScreen
import pbis.bike.finder.ui.dashboard.DashboardScreen
import pbis.bike.finder.ui.login.LoginScreen
import pbis.bike.finder.ui.reporttheft.ReportTheftScreen
import pbis.bike.finder.ui.subscription.SubscriptionScreen

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
                    navController.navigate(Route.Dashboard) {
                        // Se saca el login del back stack: volver atrás después de
                        // entrar tiene que salir de la app, no mostrar de nuevo el
                        // formulario de una sesión que ya está abierta.
                        popUpTo(Route.Landing) { inclusive = true }
                    }
                },
            )
        }
        composable<Route.Dashboard> {
            DashboardScreen(
                onAddBike = { navController.navigate(Route.AddBike) },
                onMyBikes = { navController.navigate(Route.MyBikes) },
                onUpdateComponents = { navController.navigate(Route.UpdateComponents(it)) },
                // El robo entra por el plan de búsqueda, que es pago, y recién
                // después por la denuncia — igual que el front web, donde
                // `dashboard.js` manda a `suscripcion.html` y nunca al
                // formulario.
                onReportTheft = { navController.navigate(Route.Subscription(it)) },
            )
        }
        composable<Route.MyBikes> {
            BikesScreen(
                onBikeClick = { navController.navigate(Route.BikeDetail(it)) },
                onAddBike = { navController.navigate(Route.AddBike) },
            )
        }
        composable<Route.BikeDetail> { PlaceholderScreen("Detalle de bicicleta") }
        composable<Route.AddBike> {
            AddBikeScreen(
                // Se vuelve a quien abrió el alta —el listado o el dashboard—
                // sacando el formulario del back stack: después de registrar,
                // "atrás" no puede volver al alta de una bici que ya se creó.
                //
                // Antes esto era `popBackStack(Route.MyBikes, ...)`, y desde que
                // el alta también se abre desde el dashboard eso dejaba de
                // funcionar sin avisar: si `MyBikes` no está en el back stack,
                // `popBackStack` con destino devuelve false y no hace nada — el
                // usuario se quedaba mirando el formulario recién enviado.
                onCreated = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.UpdateComponents> { PlaceholderScreen("Actualizar componentes") }
        composable<Route.Subscription> { entry ->
            val route = entry.toRoute<Route.Subscription>()
            SubscriptionScreen(
                bikeId = route.bikeId,
                // El plan sale del back stack al pagar: "atrás" desde la
                // denuncia no puede volver a una pantalla de pago cuyo cobro ya
                // ocurrió. El plan viaja a la denuncia porque el front web lo
                // arrastra en la query.
                onPaid = { plan ->
                    navController.navigate(Route.ReportTheft(route.bikeId, plan.name)) {
                        popUpTo<Route.Subscription> { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.ReportTheft> { entry ->
            val route = entry.toRoute<Route.ReportTheft>()
            ReportTheftScreen(
                bikeId = route.bikeId,
                // Igual que el alta: la denuncia sale del back stack al
                // terminar. Volver atrás sobre un formulario ya enviado sólo
                // puede llevar a intentar denunciar dos veces la misma bici.
                onReported = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.TipsList> { PlaceholderScreen("Pistas recibidas") }
        composable<Route.TipDetail> { PlaceholderScreen("Detalle de pista") }
        composable<Route.Profile> { PlaceholderScreen("Perfil") }
        composable<Route.TipForm> { PlaceholderScreen("Reportar avistamiento") }
        composable<Route.Conversation> { PlaceholderScreen("Conversación") }
    }
}

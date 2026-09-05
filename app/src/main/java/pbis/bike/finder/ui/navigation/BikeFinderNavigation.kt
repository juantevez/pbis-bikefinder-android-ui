package pbis.bike.finder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import pbis.bike.finder.data.remote.SessionEvent
import pbis.bike.finder.data.remote.SessionManager
import pbis.bike.finder.ui.addbike.AddBikeScreen
import pbis.bike.finder.ui.bikedetail.BikeDetailScreen
import pbis.bike.finder.ui.bikes.BikesScreen
import pbis.bike.finder.ui.conversation.ConversationScreen
import pbis.bike.finder.ui.dashboard.DashboardScreen
import pbis.bike.finder.ui.login.LoginScreen
import pbis.bike.finder.ui.profile.ProfileScreen
import pbis.bike.finder.ui.reports.MyReportsScreen
import pbis.bike.finder.ui.reporttheft.ReportTheftScreen
import pbis.bike.finder.ui.subscription.SubscriptionScreen
import pbis.bike.finder.ui.tipform.TipFormScreen
import pbis.bike.finder.ui.tips.TipDetailScreen
import pbis.bike.finder.ui.tips.TipsListScreen
import pbis.bike.finder.ui.updatecomponents.UpdateComponentsScreen

/**
 * De dónde salen los links que abre el informante.
 *
 * Es el mismo valor que theft-report tiene en `app.tip.base-url`, y hoy es una
 * IP de la LAN de desarrollo. Cuando haya dominio real, esto y aquello cambian
 * juntos — y recién ahí el App Link se puede verificar para que abra sin
 * preguntar.
 */
private const val WEB_FRONT_BASE = "http://192.168.0.2:5173"

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

    /**
     * Corregir una denuncia ya presentada. Es la misma pantalla que
     * [ReportTheft]: cambia de dónde salen los valores iniciales y que al
     * guardar manda PATCH en vez de POST.
     */
    @Serializable
    data class EditReport(val reportId: String) : Route

    /** Las denuncias ya hechas: sus dos PDF y sus pistas. */
    @Serializable
    data object MyReports : Route

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
 * Ya no queda ninguna pantalla sin implementar: con la conversación del
 * informante se fue el último `PlaceholderScreen`, y el archivo que lo definía
 * también.
 *
 * Las dos rutas de abajo del todo —`TipForm` y `Conversation`— son las únicas
 * que se abren **sin sesión**, por deep link y no navegando. Su credencial es el
 * token de la URL.
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
                onMyReports = { navController.navigate(Route.MyReports) },
                onProfile = { navController.navigate(Route.Profile) },
            )
        }
        composable<Route.MyBikes> {
            BikesScreen(
                onBikeClick = { navController.navigate(Route.BikeDetail(it)) },
                onAddBike = { navController.navigate(Route.AddBike) },
                onProfile = { navController.navigate(Route.Profile) },
            )
        }
        composable<Route.BikeDetail> { entry ->
            val route = entry.toRoute<Route.BikeDetail>()
            BikeDetailScreen(
                bikeId = route.bikeId,
                onUpdateComponents = { navController.navigate(Route.UpdateComponents(it)) },
                // Igual que desde el dashboard: el robo entra por el plan de
                // búsqueda, no por el formulario de denuncia.
                onReportTheft = { navController.navigate(Route.Subscription(it)) },
                onBack = { navController.popBackStack() },
            )
        }
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
        composable<Route.UpdateComponents> { entry ->
            val route = entry.toRoute<Route.UpdateComponents>()
            UpdateComponentsScreen(
                bikeId = route.bikeId,
                // Se vuelve con `popBackStack` y no a un destino fijo: la
                // pantalla se abre hoy desde el dashboard, pero es la misma que
                // va a colgar del detalle de la bici. Volver a quien la abrió es
                // lo correcto en los dos casos — y es la lección que ya dejó el
                // alta, donde apuntar a `MyBikes` fallaba en silencio si esa
                // pantalla no estaba en el back stack.
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
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
                reportId = null,
                // Igual que el alta: la denuncia sale del back stack al
                // terminar. Volver atrás sobre un formulario ya enviado sólo
                // puede llevar a intentar denunciar dos veces la misma bici.
                onReported = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.EditReport> { entry ->
            val route = entry.toRoute<Route.EditReport>()
            ReportTheftScreen(
                bikeId = null,
                reportId = route.reportId,
                // Vuelve al listado, que es de donde se entra a corregir. La
                // denuncia corregida se relee al recargarlo.
                onReported = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.MyReports> {
            MyReportsScreen(
                onViewTips = { navController.navigate(Route.TipsList(it)) },
                onEditReport = { navController.navigate(Route.EditReport(it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.TipsList> { entry ->
            val route = entry.toRoute<Route.TipsList>()
            TipsListScreen(
                reportId = route.reportId,
                onTipClick = { navController.navigate(Route.TipDetail(route.reportId, it)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.TipDetail> { entry ->
            val route = entry.toRoute<Route.TipDetail>()
            TipDetailScreen(
                reportId = route.reportId,
                tipId = route.tipId,
                onBack = { navController.popBackStack() },
            )
        }
        composable<Route.Profile> {
            ProfileScreen(onBack = { navController.popBackStack() })
        }
        // Deep links: el informante llega por un link que alguien compartió, no
        // navegando. `bikefinder://tip?token=…` es el que se puede disparar a
        // mano; el `http` es el que hoy genera el backend para el QR del cartel,
        // y sin dominio verificado Android va a preguntar si abrir el navegador
        // o la app. Ver el intent-filter del manifest.
        composable<Route.TipForm>(
            deepLinks = listOf(
                navDeepLink { uriPattern = "bikefinder://tip?token={token}" },
                navDeepLink { uriPattern = "$WEB_FRONT_BASE/tip-form.html?token={token}" },
            ),
        ) { entry ->
            val route = entry.toRoute<Route.TipForm>()
            TipFormScreen(
                token = route.token,
                // Al enviar, el informante recibe el link de su hilo. El backend
                // lo emitia en la misma respuesta desde siempre y nadie lo
                // usaba: la conversacion existia entera y no la alcanzaba nadie.
                onOpenConversation = { navController.navigate(Route.Conversation(it)) },
                // "Cerrar" tiene que funcionar tambien cuando la pantalla es lo
                // unico que hay en el back stack, que es el caso normal: se
                // entro por un link, no navegando. Ahi `popBackStack` devuelve
                // false y no pasa nada, asi que se cae a la pantalla inicial.
                onClose = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Route.Landing) { popUpTo(0) { inclusive = true } }
                    }
                },
            )
        }
        composable<Route.Conversation>(
            deepLinks = listOf(
                navDeepLink { uriPattern = "bikefinder://conversation?token={token}" },
                navDeepLink { uriPattern = "$WEB_FRONT_BASE/conversation.html?token={token}" },
            ),
        ) { entry ->
            val route = entry.toRoute<Route.Conversation>()
            ConversationScreen(
                token = route.token,
                onClose = {
                    if (!navController.popBackStack()) {
                        navController.navigate(Route.Landing) { popUpTo(0) { inclusive = true } }
                    }
                },
            )
        }
    }
}

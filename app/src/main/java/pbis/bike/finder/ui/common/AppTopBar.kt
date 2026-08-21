package pbis.bike.finder.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable

/**
 * La barra de las pantallas de sesión iniciada.
 *
 * Es siempre la misma —marca a la izquierda, avatar a la derecha— y no el
 * título de la pantalla: el título pasó al contenido, donde puede ir grande y
 * en la serif de la marca. Que la barra no cambie entre el dashboard y el
 * listado le da al avatar una posición fija, que es lo que lo vuelve un lugar
 * y no un botón más.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BikeFinderTopBar(userName: String?, userEmail: String?, onProfile: () -> Unit) {
    TopAppBar(
        title = { Text("BikeFinder") },
        actions = {
            UserAvatarButton(fullName = userName, email = userEmail, onClick = onProfile)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

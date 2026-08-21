package pbis.bike.finder.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Hasta dos iniciales del usuario.
 *
 * Cae al mail porque el nombre es opcional en el backend, y al "?" porque
 * ninguno de los dos está garantizado mientras el perfil todavía carga.
 */
fun initialsOf(fullName: String?, email: String?): String {
    val fromName = fullName?.trim().orEmpty()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")

    if (fromName.isNotBlank()) return fromName
    return email?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/**
 * El avatar de la barra superior, que es también la puerta al perfil.
 *
 * Reemplaza a los botones "Perfil" y "Salir" que había antes. Dos palabras en
 * fila ocupaban el ancho de la barra para ofrecer algo que se usa poco, y
 * "Salir" pegado al borde es un blanco fácil de tocar sin querer para una
 * acción que tira la sesión abajo. El cierre de sesión pasó adentro del perfil,
 * que es donde se administra la cuenta.
 *
 * Va con `contentDescription` porque un círculo con dos letras no se lee solo:
 * para un lector de pantalla "JB" no dice nada, "Tu perfil" sí.
 */
@Composable
fun UserAvatarButton(
    fullName: String?,
    email: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initials = remember(fullName, email) { initialsOf(fullName, email) }

    Box(
        modifier = modifier
            .padding(end = 8.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Tu perfil" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * El nombre del usuario, alineado a la derecha bajo la barra.
 *
 * Salió de adentro de la barra cuando el avatar tomó su lugar: un `TopAppBar`
 * tiene alto fijo y meterle dos líneas a la derecha lo desborda. Acá abajo
 * queda a la altura del avatar, que es lo que lo explica — sin el nombre, dos
 * iniciales sobre un círculo son un acertijo.
 *
 * No se muestra nada mientras el perfil carga: un espacio reservado que después
 * se llena mueve todo lo de abajo justo cuando el usuario ya empezó a leer.
 */
@Composable
fun UserNameLine(userName: String?, modifier: Modifier = Modifier) {
    userName ?: return

    Text(
        text = userName,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = modifier.fillMaxWidth(),
    )
}

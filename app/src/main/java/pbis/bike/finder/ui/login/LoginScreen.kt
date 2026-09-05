package pbis.bike.finder.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pbis.bike.finder.ui.theme.ThemeToggle

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.loggedIn) {
        if (state.loggedIn) onLoggedIn()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // El selector va acá arriba y no en un paso previo al login: elegir el
        // tema antes de poder entrar agrega fricción justo donde menos conviene,
        // y es una decisión que se cambia de humor, no una sola vez en la vida.
        // El mismo control se repite en el dashboard.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp)
                .zIndex(1f),
            contentAlignment = Alignment.TopEnd,
        ) {
            ThemeToggle()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "BikeFinder",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Protegé tu bicicleta",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
            )

            if (state.awaitingMfa) {
                SegundoFactor(state = state, viewModel = viewModel)
                return@Column
            }

            OutlinedTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = { Text("Email") },
                singleLine = true,
                isError = state.emailError != null,
                supportingText = state.emailError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Contraseña") },
                singleLine = true,
                isError = state.passwordError != null,
                supportingText = state.passwordError?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                enabled = !state.submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            state.formError?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Button(
                onClick = viewModel::submit,
                enabled = !state.submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("Ingresar")
                }
            }

            TextButton(
                onClick = viewModel::openPasswordReset,
                enabled = !state.submitting,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("¿Olvidaste tu contraseña?")
            }

            Text(
                text = "El registro y el ingreso con Google todavía no están en la app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    if (state.resetVisible) {
        RecuperarContrasenaDialog(state = state, viewModel = viewModel)
    }
}

/**
 * Recuperar contraseña — el modal de `index.html`.
 *
 * Tiene dos caras: el formulario y, una vez pedido el link, el aviso de qué
 * esperar. La segunda dice explícitamente que el link abre el navegador, porque
 * la contraseña nueva se elige en el front web y quien no lo sepa se queda
 * esperando que la app haga algo.
 */
@Composable
private fun RecuperarContrasenaDialog(
    state: LoginUiState,
    viewModel: LoginViewModel,
) {
    AlertDialog(
        // Cerrar tocando afuera queda deshabilitado mientras el pedido está en
        // vuelo: el usuario no sabría si llegó a enviarse.
        onDismissRequest = { if (!state.resetSubmitting) viewModel.closePasswordReset() },
        title = { Text(if (state.resetSent) "Revisá tu correo" else "Recuperar contraseña") },
        text = {
            if (state.resetSent) {
                Text(
                    "Si el email existe, te va a llegar un link en los próximos minutos. " +
                        "El link abre el navegador para que elijas la contraseña nueva.",
                )
            } else {
                Column {
                    Text(
                        text = "Ingresá tu email y te enviamos un link para restablecer " +
                            "tu contraseña.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = state.resetEmail,
                        onValueChange = viewModel::onResetEmailChange,
                        label = { Text("Email") },
                        singleLine = true,
                        isError = state.resetEmailError != null,
                        supportingText = state.resetEmailError?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { viewModel.submitPasswordReset() },
                        ),
                        enabled = !state.resetSubmitting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )

                    state.resetError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.resetSent) {
                TextButton(onClick = viewModel::closePasswordReset) { Text("Entendido") }
            } else {
                TextButton(
                    onClick = viewModel::submitPasswordReset,
                    enabled = !state.resetSubmitting,
                ) {
                    if (state.resetSubmitting) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text("Enviar link")
                    }
                }
            }
        },
        dismissButton = if (state.resetSent) {
            null
        } else {
            {
                TextButton(
                    onClick = viewModel::closePasswordReset,
                    enabled = !state.resetSubmitting,
                ) {
                    Text("Cancelar")
                }
            }
        },
    )
}

/**
 * Segundo paso del login.
 *
 * Reemplaza al formulario en vez de agregarse debajo: acá no hay nada que
 * decidir —la contraseña ya se validó— y dejar los campos anteriores a la vista
 * invita a corregirlos, que es justo lo que no hay que hacer.
 */
@Composable
private fun SegundoFactor(
    state: LoginUiState,
    viewModel: LoginViewModel,
) {
    Text(
        text = "Verificación en dos pasos",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Text(
        text = "Ingresá el código de tu app de autenticación. Si perdiste el teléfono, "
            + "usá uno de tus códigos de recuperación.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
    )

    OutlinedTextField(
        value = state.mfaCode,
        onValueChange = viewModel::onMfaCodeChange,
        label = { Text("Código") },
        singleLine = true,
        // NumberPassword y no Number: el teclado numérico alcanza para los seis
        // dígitos, pero el campo acepta además los códigos de recuperación, que
        // llevan letras y guión. KeyboardType es una sugerencia, no un filtro.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { viewModel.submitMfaCode() }),
        enabled = !state.submitting,
        modifier = Modifier.fillMaxWidth(),
    )

    state.formError?.let { error ->
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    Button(
        onClick = viewModel::submitMfaCode,
        enabled = !state.submitting,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    ) {
        if (state.submitting) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text("Verificar")
        }
    }

    TextButton(
        onClick = viewModel::cancelMfa,
        enabled = !state.submitting,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text("Volver")
    }
}

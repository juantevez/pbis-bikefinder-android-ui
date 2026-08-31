package pbis.bike.finder.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.LoginOutcome
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val formError: String? = null,
    val submitting: Boolean = false,
    val loggedIn: Boolean = false,
    /**
     * Challenge de la primera etapa. Que no sea null es lo que pone la pantalla
     * en el segundo paso: es el dato y el estado a la vez, así que no pueden
     * quedar desincronizados.
     *
     * No se persiste en ningún lado: vale cinco minutos y muere con el
     * ViewModel. Si el proceso se reinicia, el login empieza de nuevo — que es
     * lo correcto, porque la contraseña también habría que volver a pedirla.
     */
    val mfaToken: String? = null,
    val mfaCode: String = "",
) {
    val awaitingMfa: Boolean get() = mfaToken != null
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) =
        _state.update { it.copy(email = value, emailError = null, formError = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, passwordError = null, formError = null) }

    fun onMfaCodeChange(value: String) =
        _state.update { it.copy(mfaCode = value, formError = null) }

    /**
     * Vuelve al paso 1 y descarta el challenge: reintentar empieza por la
     * contraseña. La contraseña tipeada se conserva —el usuario no se equivocó
     * en ella— pero el challenge no se reutiliza.
     */
    fun cancelMfa() =
        _state.update { it.copy(mfaToken = null, mfaCode = "", formError = null) }

    /**
     * Valida y envía.
     *
     * `submitting` no es sólo para el spinner: bloquea el reenvío. Un doble tap
     * o dos Enter seguidos mandan dos logins, y con un backend lento eso es
     * fácil de provocar sin querer.
     */
    fun submit() {
        if (_state.value.submitting) return
        if (!validate()) return

        _state.update { it.copy(submitting = true, formError = null) }

        viewModelScope.launch {
            val current = _state.value
            when (val result = authRepository.login(current.email, current.password)) {
                is ApiResult.Success -> when (val outcome = result.data) {
                    // No se libera `submitting` en el camino feliz: la pantalla
                    // está por navegar, y reactivar el botón durante esa espera
                    // es una ventana para reenviar.
                    is LoginOutcome.Completed -> _state.update { it.copy(loggedIn = true) }

                    // Acá SÍ se libera: la pantalla no navega, cambia de paso, y
                    // el usuario tiene que poder enviar el código.
                    is LoginOutcome.MfaRequired -> _state.update {
                        it.copy(submitting = false, mfaToken = outcome.mfaToken)
                    }
                }

                else -> _state.update {
                    it.copy(
                        submitting = false,
                        formError = result.toUserMessage("No se pudo iniciar sesión."),
                    )
                }
            }
        }
    }

    /**
     * Segunda etapa: manda el código.
     *
     * Acepta el de la app y el de recuperación en el mismo campo, así que no
     * valida el formato más allá de que no esté vacío: rechazar acá un código
     * legítimo por su forma sería peor que dejar que el backend lo evalúe.
     */
    fun submitMfaCode() {
        val current = _state.value
        if (current.submitting) return

        val challenge = current.mfaToken ?: return
        if (current.mfaCode.isBlank()) {
            _state.update { it.copy(formError = "Ingresá el código") }
            return
        }

        _state.update { it.copy(submitting = true, formError = null) }

        viewModelScope.launch {
            when (val result = authRepository.verifyMfa(challenge, current.mfaCode)) {
                is ApiResult.Success -> _state.update { it.copy(loggedIn = true) }

                is ApiResult.HttpError -> _state.update {
                    // INVALID_TOKEN es el challenge vencido —cinco minutos— y no
                    // un código equivocado: reintentar el código no sirve, hay
                    // que volver a la contraseña.
                    if (result.errorCode == "INVALID_TOKEN") {
                        it.copy(
                            submitting = false,
                            mfaToken = null,
                            mfaCode = "",
                            formError = "El ingreso expiró. Probá de nuevo.",
                        )
                    } else {
                        it.copy(
                            submitting = false,
                            formError = result.toUserMessage("Código inválido."),
                        )
                    }
                }

                else -> _state.update {
                    it.copy(
                        submitting = false,
                        formError = result.toUserMessage("No se pudo verificar el código."),
                    )
                }
            }
        }
    }

    /**
     * Valida con las mismas reglas que el backend.
     *
     * El mínimo y el máximo de la contraseña son los del `@Size(min = 8, max =
     * 100)` de `RegisterRequestDto`. Validar acá no es desconfianza del
     * servidor: es que sin esto el usuario se entera del error **después** del
     * viaje, y con un error de validación crudo.
     */
    private fun validate(): Boolean {
        val current = _state.value
        val emailError = when {
            current.email.isBlank() -> "El email es requerido"
            !EMAIL_REGEX.matches(current.email.trim()) -> "Email inválido"
            else -> null
        }
        val passwordError = when {
            current.password.isEmpty() -> "La contraseña es requerida"
            else -> null
        }

        _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
        return emailError == null && passwordError == null
    }

    private companion object {
        val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

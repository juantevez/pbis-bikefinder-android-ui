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
)

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
                is ApiResult.Success ->
                    // No se libera `submitting` en el camino feliz: la pantalla
                    // está por navegar, y reactivar el botón durante esa espera
                    // es una ventana para reenviar.
                    _state.update { it.copy(loggedIn = true) }

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

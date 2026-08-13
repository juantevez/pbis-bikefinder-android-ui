package pbis.bike.finder.data.remote.dto

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.datetime.Instant

// ─────────────────────────────────────────────────────────────────────────────
// auth-service — com.bikefinder.auth
//   application.dto.AuthResponseDto
//   infrastructure.adapter.in.rest.dto.*
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
data class RegisterRequestDto(
    val email: String,
    /** `@Size(min = 8, max = 100)` en el backend. Validar antes de mandar. */
    val password: String,
    val fullName: String,
)

@Serializable
data class RefreshTokenRequestDto(val refreshToken: String)

@Serializable
data class LogoutRequestDto(val refreshToken: String)

@Serializable
data class VerifyEmailDto(val token: String)

@Serializable
data class ResendVerificationDto(val email: String)

@Serializable
data class RequestPasswordResetDto(val email: String)

@Serializable
data class ConfirmPasswordResetDto(
    val token: String,
    val newPassword: String,
)

/**
 * Respuesta de `/auth/login`, `/auth/register` y `/auth/refresh`.
 *
 * `expiresIn` / `expiresAt` los ignoraba el front web, que renovaba de forma
 * reactiva: mandaba la request, comía el 401 y recién ahí refrescaba. Con la
 * expiración conocida se puede renovar antes de que venza y ahorrar un
 * round-trip fallido en cada arranque de sesión.
 */
@Serializable
data class AuthResponseDto(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String? = null,
    /**
     * Vida del access token en **milisegundos**, no en segundos.
     *
     * Verificado contra el backend: viene `900000` mientras que el JWT trae
     * `exp - iat = 900`. Es contrario a la convención de OAuth 2 (RFC 6749, donde
     * `expires_in` son segundos), así que es fácil leerlo mal — tomarlo como
     * segundos daría una expiración a 10 días y la renovación anticipada nunca
     * se dispararía.
     */
    val expiresIn: Long? = null,
    val expiresAt: Instant? = null,
    val user: UserInfoDto? = null,
)

/**
 * Perfil del usuario. Es el MISMO record que devuelve `GET /auth/me` y
 * `PUT /auth/me` (`AuthResponseDto.UserInfoDto`), no un DTO aparte.
 *
 * **`role` no está acá.** El rol viaja solo como claim del JWT; hay que
 * decodificar el token para saber si la cuenta es ADMIN.
 */
@Serializable
data class UserInfoDto(
    val id: String,
    val email: String,
    val emailVerified: Boolean? = null,
    val fullName: String? = null,
    val phoneNumber: String? = null,
    val phoneVerified: Boolean? = null,
    /** El front web no lo usa: muestra avatares estáticos. */
    val avatarUrl: String? = null,
    val gender: String? = null,
    val birthDate: LocalDate? = null,
    val location: UserLocationDto? = null,
)

/**
 * Ubicación del perfil. **No** es la misma [TheftLocationDto] de robos y pistas:
 * esta no tiene calle ni coordenadas, y sus nombres vienen desnormalizados.
 */
@Serializable
data class UserLocationDto(
    val localityId: Int? = null,
    val localityName: String? = null,
    val departmentName: String? = null,
    val provinceName: String? = null,
    val countryName: String? = null,
)

/**
 * `PUT /auth/me`. Todos los campos son opcionales y **null significa "no
 * tocar"** — por eso `explicitNulls = false` en [BikeFinderJson] es seguro acá.
 *
 * Manda IDs *y* nombres de la jerarquía geográfica: es denormalización
 * deliberada del backend, no un descuido del cliente.
 */
@Serializable
data class UpdateProfileRequestDto(
    val fullName: String? = null,
    /** Formato E.164, validado con regex en el backend. Ver [Gender] para el otro enum. */
    val phoneNumber: String? = null,
    val gender: String? = null,
    val birthDate: LocalDate? = null,
    val localityId: Int? = null,
    val localityName: String? = null,
    val departmentName: String? = null,
    val provinceName: String? = null,
    val countryName: String? = null,
)

/**
 * Valores admitidos por el `@Pattern` de `UpdateProfileRequestDto.gender`.
 *
 * Va como enum separado y no como tipo del campo a propósito: el backend acepta
 * exactamente estos cuatro, pero si algún día agrega uno, un enum en el DTO de
 * respuesta haría fallar la deserialización del perfil entero. Se valida al
 * construir el request y se tolera lo que venga al leerlo.
 */
enum class Gender {
    MALE, FEMALE, ALIEN, PREFER_NOT_TO_SAY;

    companion object {
        fun fromApi(raw: String?): Gender? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * Regex E.164 del backend (`UpdateProfileRequestDto`). Se replica en el cliente
 * para cortar el error antes del viaje: sin esto el usuario se entera del
 * formato mal recién después del round-trip.
 */
val E164_REGEX = Regex("^\\+[1-9]\\d{1,14}$")

/**
 * Identidad de cuenta para consumo servicio-a-servicio (`AccountProfileDto`).
 *
 * No lo expone el gateway: lo usa dashboard-aggregator para el panel de
 * administración. Está acá sólo para que quede constancia de que existe y no se
 * lo confunda con [UserInfoDto].
 */
@Serializable
data class AccountProfileDto(
    @SerialName("userId") val userId: String,
    val fullName: String? = null,
    val email: String,
    val emailVerified: Boolean = false,
)

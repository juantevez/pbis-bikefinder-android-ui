package pbis.bike.finder.ui.tipform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import pbis.bike.finder.data.local.DeviceLocationProvider
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.TipFormInfoDto
import pbis.bike.finder.data.remote.dto.SubmitTipRequestDto
import pbis.bike.finder.data.repository.GeocodingRepository
import pbis.bike.finder.data.repository.PublicTipRepository
import pbis.bike.finder.data.repository.ResolvedAddress
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

private const val TOKEN_INVALIDO =
    "Este link ya no sirve. Puede haber vencido, o la denuncia pudo cerrarse porque " +
        "la bicicleta apareció."

data class TipFormUiState(
    // ── La bici sobre la que se reporta ──────────────────────────────────
    val loading: Boolean = true,
    val info: TipFormInfoDto? = null,
    val loadError: String? = null,
    val canRetryLoad: Boolean = false,

    // ── El formulario ────────────────────────────────────────────────────
    val sightingDate: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    val sightingTime: String = "",
    val description: String = "",
    val informantEmail: String = "",
    val informantPhone: String = "",

    // ── El punto ─────────────────────────────────────────────────────────
    val latitude: Double? = null,
    val longitude: Double? = null,
    val centerOn: Pair<Double, Double>? = null,
    val locating: Boolean = false,
    val locationError: String? = null,
    val geocoding: Boolean = false,
    val geocodingError: String? = null,
    /** La dirección propuesta, todavía sin aceptar. */
    val resolvedAddress: ResolvedAddress? = null,
    /** La que el informante confirmó con "Usar esta dirección". */
    val acceptedAddress: ResolvedAddress? = null,

    // ── Envío ────────────────────────────────────────────────────────────
    val submitting: Boolean = false,
    val submitError: String? = null,
    val submitted: Boolean = false,
    /**
     * El link del informante para seguir el hilo con el dueño.
     *
     * **El front web lo tira**, aunque el backend lo devuelve en esta misma
     * respuesta: quien reporta manda la pista y nunca se entera de que puede
     * haber una conversación. Acá se guarda, y es la entrada de la pantalla de
     * conversación cuando exista — hoy `Route.Conversation` sigue siendo un
     * placeholder, así que todavía no se ofrece un botón que no llevaría a nada.
     */
    val conversationToken: String? = null,
) {
    /**
     * La descripción es lo único obligatorio además de la fecha.
     *
     * El punto del mapa no se exige: alguien que vio la bici pasar y no sabe
     * marcar la esquina exacta igual tiene algo que aportar, y rechazar esa
     * pista sería perder el dato entero por un detalle que se puede escribir en
     * la descripción.
     */
    val canSubmit: Boolean
        get() = !submitting && description.isNotBlank() &&
            description.length <= SubmitTipRequestDto.MAX_DESCRIPTION

    val descriptionTooLong: Boolean
        get() = description.length > SubmitTipRequestDto.MAX_DESCRIPTION

    val hasPoint: Boolean get() = latitude != null && longitude != null
}

/**
 * "¿Viste esta bicicleta?" — el formulario del informante, equivalente a
 * `tip-form.html`.
 *
 * Es la única pantalla de la app que funciona **sin sesión**: se llega por un
 * link con token que alguien compartió, y quien la usa es un tercero que casi
 * seguro no tiene cuenta. Por eso todo lo que pide es opcional salvo la
 * descripción: cada campo obligatorio de más es una pista que no se manda.
 */
@HiltViewModel
class TipFormViewModel @Inject constructor(
    private val repository: PublicTipRepository,
    private val geocodingRepository: GeocodingRepository,
    private val locationProvider: DeviceLocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(TipFormUiState())
    val state: StateFlow<TipFormUiState> = _state.asStateFlow()

    private var token: String? = null

    fun start(token: String) {
        if (this.token != null) return
        this.token = token
        load()
    }

    fun load() {
        val token = token ?: return
        _state.update { it.copy(loading = true, loadError = null) }

        viewModelScope.launch {
            when (val result = repository.tipFormInfo(token)) {
                is ApiResult.Success -> _state.update {
                    val lat = result.data.location?.latitude
                    val lng = result.data.location?.longitude

                    it.copy(
                        loading = false,
                        info = result.data,
                        loadError = null,
                        // El mapa arranca donde fue el robo: es el lugar más
                        // probable de un avistamiento y le ahorra al informante
                        // arrastrar el mapa media ciudad. Vienen redondeadas a
                        // ~1 km, que para centrar el mapa alcanza y sobra.
                        centerOn = if (lat != null && lng != null) lat to lng else it.centerOn,
                    )
                }

                else -> _state.update {
                    // Un 404 acá es el token: vencido, o la denuncia se cerró
                    // porque apareció la bici. Decir "no se pudo cargar" mandaría
                    // a reintentar contra algo que no va a cambiar nunca.
                    val tokenMuerto = result is ApiResult.HttpError && result.code == 404

                    it.copy(
                        loading = false,
                        loadError = if (tokenMuerto) TOKEN_INVALIDO
                        else result.toUserMessage("No se pudo cargar la bicicleta."),
                        canRetryLoad = !tokenMuerto && result.isSafeToRetry(),
                    )
                }
            }
        }
    }

    // ── Campos ───────────────────────────────────────────────────────────────

    fun setDate(value: LocalDate) = _state.update { it.copy(sightingDate = value) }

    fun setTime(value: String) =
        _state.update { it.copy(sightingTime = value.take(SubmitTipRequestDto.MAX_TIME_APPROX)) }

    fun setDescription(value: String) = _state.update { it.copy(description = value) }

    fun setEmail(value: String) =
        _state.update { it.copy(informantEmail = value.take(SubmitTipRequestDto.MAX_INFORMANT_CONTACT)) }

    fun setPhone(value: String) =
        _state.update { it.copy(informantPhone = value.take(SubmitTipRequestDto.MAX_INFORMANT_CONTACT)) }

    // ── El punto en el mapa ──────────────────────────────────────────────────

    /** Mover el punto invalida la dirección: la anterior era de otro lugar. */
    fun setPoint(latitude: Double, longitude: Double) = _state.update {
        it.copy(
            latitude = latitude,
            longitude = longitude,
            resolvedAddress = null,
            acceptedAddress = null,
            geocodingError = null,
        )
    }

    fun clearPoint() = _state.update {
        it.copy(
            latitude = null,
            longitude = null,
            resolvedAddress = null,
            acceptedAddress = null,
            geocodingError = null,
        )
    }

    fun useCurrentLocation() {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true, locationError = null) }

        viewModelScope.launch {
            val point = locationProvider.currentPoint()
            if (point == null) {
                _state.update {
                    it.copy(
                        locating = false,
                        locationError = "No pudimos obtener tu ubicación. " +
                            "Tocá el mapa en el lugar donde la viste.",
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    locating = false,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    centerOn = point.latitude to point.longitude,
                    resolvedAddress = null,
                    acceptedAddress = null,
                )
            }
        }
    }

    /**
     * Le pregunta a Nominatim qué dirección hay en el punto marcado.
     *
     * Detrás de un botón y no en cada toque, igual que en la denuncia: la
     * política de uso de OSM pide como máximo una request por segundo y
     * arrastrar el marcador genera decenas de posiciones intermedias.
     */
    fun resolveAddress() {
        val current = _state.value
        val lat = current.latitude ?: return
        val lng = current.longitude ?: return
        if (current.geocoding) return

        _state.update { it.copy(geocoding = true, geocodingError = null) }

        viewModelScope.launch {
            when (val result = geocodingRepository.reverse(lat, lng)) {
                is ApiResult.Success -> _state.update {
                    it.copy(geocoding = false, resolvedAddress = result.data)
                }

                else -> _state.update {
                    it.copy(
                        geocoding = false,
                        // El punto ya está marcado y la pista vale igual: es una
                        // comodidad que falló, no un error.
                        geocodingError = "No se pudo resolver la dirección. " +
                            "El punto quedó marcado igual.",
                    )
                }
            }
        }
    }

    /**
     * El informante acepta la calle propuesta.
     *
     * Sólo entonces viaja: el backend respeta la dirección que le mandan y no la
     * sobreescribe con su propio geocoding, así que mandarla sin que nadie la
     * haya mirado sería fijar como cierta una adivinanza de OSM.
     */
    fun acceptAddress() = _state.update {
        it.copy(acceptedAddress = it.resolvedAddress, resolvedAddress = null)
    }

    fun rejectAddress() = _state.update { it.copy(resolvedAddress = null) }

    // ── Envío ────────────────────────────────────────────────────────────────

    fun submit() {
        val token = token ?: return
        val current = _state.value
        if (!current.canSubmit) return

        _state.update { it.copy(submitting = true, submitError = null) }

        viewModelScope.launch {
            when (val result = repository.submitTip(token, current.toRequest())) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        submitting = false,
                        submitted = true,
                        conversationToken = result.data.conversationToken,
                    )
                }

                else -> _state.update {
                    it.copy(
                        submitting = false,
                        submitError = result.toUserMessage(
                            "No se pudo enviar la pista. Probá de nuevo en un momento.",
                        ),
                    )
                }
            }
        }
    }

    fun dismissSubmitError() = _state.update { it.copy(submitError = null) }
}

/**
 * Arma el cuerpo del POST.
 *
 * Es una función suelta y no un método del ViewModel para poder probarla: las
 * reglas de qué viaja y qué no —el blanco que se vuelve `null`, la calle que
 * sólo va si la aceptaron— son justo lo que se rompe sin que nadie se entere.
 */
internal fun TipFormUiState.toRequest(): SubmitTipRequestDto = SubmitTipRequestDto(
    sightingDate = sightingDate,
    sightingTimeApprox = sightingTime.trim().ifBlank { null },
    description = description.trim(),
    // Los dos por separado desde V16. En blanco viajan como null y no como "":
    // una cadena vacía en la base se lee después como "dejó un contacto".
    informantEmail = informantEmail.trim().ifBlank { null },
    informantPhone = informantPhone.trim().ifBlank { null },
    sightingLocation = locationOrNull(),
)

private fun TipFormUiState.locationOrNull() = when {
    latitude == null || longitude == null -> null

    else -> pbis.bike.finder.data.remote.dto.TheftLocationDto(
        latitude = latitude,
        longitude = longitude,
        // "EXACT" es lo que manda el front web para las pistas: el punto lo
        // marcó una persona sobre el mapa, no un geocoder.
        precision = "EXACT",
        // La calle sólo si la confirmaron. Sin eso el backend geocodifica el
        // punto por su cuenta, que es lo correcto cuando nadie miró la dirección.
        streetType = acceptedAddress?.streetType,
        streetName = acceptedAddress?.streetName,
        streetNumber = acceptedAddress?.streetNumber,
        reference = acceptedAddress?.locality,
    )
}

package pbis.bike.finder.ui.reporttheft

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
import pbis.bike.finder.data.remote.dto.AdminLevel1Dto
import pbis.bike.finder.data.remote.dto.AdminLevel2Dto
import pbis.bike.finder.data.remote.dto.CountryDto
import pbis.bike.finder.data.remote.dto.LocalityDto
import pbis.bike.finder.data.remote.dto.LocalityFullDto
import pbis.bike.finder.data.remote.dto.ReportStatus
import pbis.bike.finder.data.remote.dto.ReportTheftRequestDto
import pbis.bike.finder.data.remote.dto.TheftLocationDto
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.GeoRepository
import pbis.bike.finder.data.repository.GeocodingRepository
import pbis.bike.finder.data.repository.ResolvedAddress
import pbis.bike.finder.data.repository.TheftRepository
import pbis.bike.finder.ui.common.foldForMatch
import pbis.bike.finder.ui.common.LocationLabel
import pbis.bike.finder.ui.common.LocationLabels
import pbis.bike.finder.ui.common.textoError
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

private const val PAISES_ERROR = "No se pudieron cargar los países."

/**
 * Elige qué localidad del catálogo corresponde al nombre que devolvió OSM.
 *
 * El backend busca por coincidencia parcial, así que "Morón" puede traer también
 * "Villa Morón". Las reglas, en orden:
 *
 *  1. El nombre tiene que ser **igual**, no parecido. Un resultado parcial es
 *     otro lugar, y proponer otro lugar es peor que no proponer nada: el usuario
 *     confirma sin releer y la denuncia queda en un partido equivocado.
 *  2. Entre los homónimos —que en Argentina son muchos: hay una Belgrano por
 *     provincia— gana el que coincide en provincia con OSM.
 *  3. Si sigue habiendo empate, no se propone nada. Con los desplegables ya
 *     poblados el usuario elige, que es mejor que acertar una de dos.
 *
 * La comparación ignora mayúsculas y acentos: OSM escribe "Ramos Mejía" y el
 * catálogo "RAMOS MEJIA", y son el mismo lugar.
 */
internal fun List<LocalityFullDto>.bestMatch(
    localityName: String,
    provinceName: String?,
): LocalityFullDto? {
    val exact = filter { it.name.foldForMatch() == localityName.foldForMatch() }
    if (exact.size == 1) return exact.single()
    if (exact.isEmpty()) return null

    val province = provinceName?.foldForMatch() ?: return null
    val inProvince = exact.filter { it.adminLevel1?.name?.foldForMatch() == province }

    return inProvince.singleOrNull()
}

/**
 * Franjas horarias del robo, con el mismo código que manda el front web.
 *
 * El backend guarda un texto libre de hasta 50 caracteres, así que acá podría ir
 * cualquier cosa; se usan los códigos de la web para que las dos denuncias sean
 * el mismo dato y no dos formas de escribir "a la tarde".
 */
enum class TheftTimeSlot(val apiValue: String, val label: String) {
    EARLY_MORNING("EARLY_MORNING", "Madrugada (00:00 - 06:00)"),
    MORNING("MORNING", "Mañana (06:00 - 12:00)"),
    AFTERNOON("AFTERNOON", "Tarde (12:00 - 18:00)"),
    EVENING("EVENING", "Noche (18:00 - 00:00)"),
}

/** Tipos de vía que entiende el backend, con su etiqueta para la UI. */
enum class StreetType(val apiValue: String, val label: String) {
    CALLE("CALLE", "Calle"),
    AVENIDA("AVENIDA", "Avenida"),
    BOULEVARD("BOULEVARD", "Boulevard"),
    DIAGONAL("DIAGONAL", "Diagonal"),
    PASAJE("PASAJE", "Pasaje"),
}

data class ReportTheftUiState(
    val bikeName: String? = null,

    // Detalles
    val theftDate: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    val maxDate: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()),
    val timeSlot: TheftTimeSlot? = null,
    val description: String = "",

    // Cascada geográfica
    val countries: List<CountryDto> = emptyList(),
    val provinces: List<AdminLevel1Dto> = emptyList(),
    val departments: List<AdminLevel2Dto> = emptyList(),
    val localities: List<LocalityDto> = emptyList(),
    val countryId: Int? = null,
    val provinceId: Int? = null,
    val departmentId: Int? = null,
    val localityId: Int? = null,

    /**
     * Cómo se llama cada nivel **en el país elegido**. Sale del `type` que trae
     * cada lista, no de un `if` por país. Ver [LocationLabels].
     */
    val etiquetaNivel1: LocationLabel = LocationLabels.NIVEL1_DEFAULT,
    val etiquetaNivel2: LocationLabel = LocationLabels.NIVEL2_DEFAULT,
    val etiquetaLocalidad: LocationLabel = LocationLabels.LOCALIDAD_DEFAULT,
    val loadingGeo: Boolean = false,
    /**
     * Falla de location-service.
     *
     * Existe porque no tenerlo fue un bug real: los desplegables se llenaban con
     * una lista vacía cuando el servicio estaba caído, y "no hay datos" se veía
     * exactamente igual que "no se pudo preguntar". El usuario se quedaba sin
     * forma de elegir localidad y sin ninguna pista de por qué.
     */
    val geoError: String? = null,

    // Punto en el mapa
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locating: Boolean = false,
    val locationError: String? = null,
    /** Adónde mover la cámara sin tocar el marcador. */
    val centerOn: Pair<Double, Double>? = null,

    // Dirección resuelta por Nominatim, todavía sin confirmar
    val geocoding: Boolean = false,
    val resolvedAddress: ResolvedAddress? = null,
    val geocodingError: String? = null,
    /**
     * La localidad del catálogo que corresponde al punto, propuesta junto con la
     * dirección y aplicada sólo si el usuario confirma.
     *
     * Es `null` cuando la búsqueda no encontró nada o encontró algo que no
     * convence: entonces se propone la calle sola, como antes.
     */
    val resolvedLocality: LocalityFullDto? = null,

    // Dirección
    val streetType: StreetType? = null,
    val streetName: String = "",
    val streetNumber: String = "",
    val reference: String = "",

    // Contacto
    val contactPhone: String = "",
    val contactEmail: String = "",
    val contactPublic: Boolean = false,

    /**
     * Recompensa: un sí/no y nada más.
     *
     * El monto y la moneda se sacaron en agosto de 2026 —el backend dejó de
     * guardarlos— porque el arreglo ocurre fuera del sistema: se publica que hay
     * recompensa y la cifra la acuerdan las dos personas.
     */
    val rewardOffered: Boolean = false,

    val submitting: Boolean = false,
    val formError: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),

    /** Id de la denuncia creada. Deja de ser null una sola vez. */
    val createdReportId: String? = null,

    val generatingPdf: Boolean = false,
    /** URL prefirmada lista para abrir; la pantalla la consume y la limpia. */
    val pdfUrl: String? = null,
    val pdfError: String? = null,
) {
    /**
     * La localidad es obligatoria, y esta app es **más estricta que el backend**.
     *
     * El servidor acepta una denuncia con la localidad **o** con el par de
     * coordenadas; las dos son formas válidas de decir dónde, y este campo
     * replicaba esa regla tal cual. El problema aparece después, en los PDF.
     *
     * Los dos documentos derivan provincia y localidad de `localityId`: no hay
     * otra fuente. Una denuncia con el punto marcado y sin localidad genera un
     * PDF **privado** —el que se lleva a la policía— con `Provincia: -` y
     * `Localidad: -`, y un cartel público sin ninguna zona, porque ése además
     * omite la calle a propósito por ser dato sensible. Medido en un e2e real:
     * un robo en Colegiales salió con la calle correcta y sin jurisdicción
     * ninguna. Una denuncia policial sin provincia ni localidad es casi
     * inservible — es el dato que define qué comisaría interviene.
     *
     * Que el backend lo acepte no lo hace suficiente. El único momento en que
     * alguien puede elegir la localidad es mientras carga el formulario; una vez
     * presentada, el dato falta para siempre y ningún reintento lo repone.
     */
    val hasLocation: Boolean
        get() = localityId != null

    /**
     * Hay punto pero todavía no hay localidad.
     *
     * Es el estado que deja el mapa cuando el geocoding inverso resolvió la
     * calle pero no encontró la localidad en el catálogo —pasa con los barrios
     * de CABA, que no matchean por nombre—. Ya no describe una denuncia válida
     * con un cartel pobre: describe un formulario al que le falta algo. Lo usa
     * la pantalla para explicar **por qué** hace falta la localidad, antes de
     * que el usuario choque contra el error al enviar.
     */
    val faltaLocalidadConPunto: Boolean
        get() = localityId == null && latitude != null && longitude != null
}

/**
 * La denuncia.
 *
 * Es la pantalla más crítica de la app: lo que se carga acá dispara la búsqueda
 * por imagen, alimenta el mapa público de robos y termina en el PDF que el
 * usuario lleva a la policía.
 *
 * Dos cosas la separan del resto de los formularios:
 *
 *  - **La ubicación es obligatoria**, y se valida acá además de en el backend.
 *  - **El envío no es reintentable a ciegas.** El backend commitea la denuncia
 *    antes de los pasos best-effort, así que un error después de ese punto puede
 *    convivir con una denuncia ya creada.
 */
@HiltViewModel
class ReportTheftViewModel @Inject constructor(
    private val theftRepository: TheftRepository,
    private val bicycleRepository: BicycleRepository,
    private val geoRepository: GeoRepository,
    private val geocodingRepository: GeocodingRepository,
    private val authRepository: AuthRepository,
    private val locationProvider: DeviceLocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ReportTheftUiState())
    val state: StateFlow<ReportTheftUiState> = _state.asStateFlow()

    private var bicycleId: String? = null

    /**
     * La pantalla pasa el id de la bici una sola vez.
     *
     * Va acá y no en el constructor por `SavedStateHandle` para que el ViewModel
     * se pueda construir en un test sin armar el back stack de navegación.
     */
    fun start(bicycleId: String) {
        if (this.bicycleId != null) return
        this.bicycleId = bicycleId

        loadBike(bicycleId)
        loadProfile()
        loadCountries()
    }

    private fun loadBike(id: String) {
        viewModelScope.launch {
            val result = bicycleRepository.bicycle(id)
            if (result is ApiResult.Success) {
                val frame = result.data.frame
                val name = listOfNotNull(frame?.brandName, frame?.model)
                    .joinToString(" ")
                    .ifBlank { null }
                _state.update { it.copy(bikeName = name) }
            }
        }
    }

    /**
     * Precarga el contacto con lo que ya sabemos del usuario.
     *
     * En el front web, un perfil sin teléfono **ni** mail corta el flujo con un
     * diálogo. Acá no se corta: el mail es el identificador de la cuenta, así que
     * esa rama es prácticamente inalcanzable, y los dos campos son editables —si
     * llegaran vacíos, el usuario los completa a mano y la denuncia sigue.
     */
    private fun loadProfile() {
        viewModelScope.launch {
            val result = authRepository.profile()
            if (result is ApiResult.Success) {
                _state.update {
                    it.copy(
                        contactPhone = it.contactPhone.ifBlank { result.data.phoneNumber ?: "" },
                        contactEmail = it.contactEmail.ifBlank { result.data.email },
                    )
                }
            }
        }
    }

    // ── Cascada geográfica ───────────────────────────────────────────────────

    fun loadCountries() {
        _state.update { it.copy(loadingGeo = true, geoError = null) }

        viewModelScope.launch {
            when (val result = geoRepository.countries()) {
                is ApiResult.Success -> {
                    _state.update { it.copy(loadingGeo = false, countries = result.data) }
                    // Argentina preseleccionada, igual que el front web: es el
                    // único país con datos cargados, y ahorra un desplegable.
                    result.data.firstOrNull { it.isoCode2 == "AR" }?.let { selectCountry(it.id) }
                }

                else -> _state.update {
                    it.copy(loadingGeo = false, geoError = result.toUserMessage(PAISES_ERROR))
                }
            }
        }
    }

    fun selectCountry(countryId: Int?) {
        // Cada nivel invalida los de abajo. Es el mismo reseteo en cascada del
        // alta: dejar colgada una localidad de otra provincia manda a la denuncia
        // una ubicación que no existe.
        // Los rótulos de abajo vuelven al genérico junto con las listas: cómo se
        // llaman los niveles del país nuevo recién se sabe cuando llegue su lista.
        _state.update {
            it.copy(
                countryId = countryId,
                provinceId = null,
                departmentId = null,
                localityId = null,
                provinces = emptyList(),
                departments = emptyList(),
                localities = emptyList(),
                etiquetaNivel1 = LocationLabels.NIVEL1_DEFAULT,
                etiquetaNivel2 = LocationLabels.NIVEL2_DEFAULT,
                etiquetaLocalidad = LocationLabels.LOCALIDAD_DEFAULT,
                geoError = null,
            )
        }
        countryId ?: return

        viewModelScope.launch {
            _state.update { it.copy(loadingGeo = true) }
            when (val result = geoRepository.provinces(countryId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loadingGeo = false,
                        provinces = result.data,
                        etiquetaNivel1 = LocationLabels.nivel1(
                            LocationLabels.tipoComun(result.data) { p -> p.type },
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(
                        loadingGeo = false,
                        geoError = result.toUserMessage(it.etiquetaNivel1.textoError()),
                    )
                }
            }
        }
    }

    fun selectProvince(provinceId: Int?) {
        _state.update {
            it.copy(
                provinceId = provinceId,
                departmentId = null,
                localityId = null,
                departments = emptyList(),
                localities = emptyList(),
                etiquetaNivel2 = LocationLabels.NIVEL2_DEFAULT,
                etiquetaLocalidad = LocationLabels.LOCALIDAD_DEFAULT,
                geoError = null,
            )
        }
        provinceId ?: return

        viewModelScope.launch {
            _state.update { it.copy(loadingGeo = true) }
            when (val result = geoRepository.departments(provinceId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loadingGeo = false,
                        departments = result.data,
                        etiquetaNivel2 = LocationLabels.nivel2(
                            LocationLabels.tipoComun(result.data) { d -> d.type },
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(
                        loadingGeo = false,
                        geoError = result.toUserMessage(it.etiquetaNivel2.textoError()),
                    )
                }
            }
        }
    }

    fun selectDepartment(departmentId: Int?) {
        _state.update {
            it.copy(
                departmentId = departmentId,
                localityId = null,
                localities = emptyList(),
                etiquetaLocalidad = LocationLabels.LOCALIDAD_DEFAULT,
                geoError = null,
            )
        }
        departmentId ?: return

        viewModelScope.launch {
            _state.update { it.copy(loadingGeo = true) }
            when (val result = geoRepository.localities(departmentId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loadingGeo = false,
                        localities = result.data,
                        etiquetaLocalidad = LocationLabels.localidad(
                            LocationLabels.tipoComun(result.data) { l -> l.type },
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(
                        loadingGeo = false,
                        geoError = result.toUserMessage(it.etiquetaLocalidad.textoError()),
                    )
                }
            }
        }
    }

    /**
     * Elegir localidad **centra el mapa**, que es el atajo para no buscar a mano.
     *
     * Mover la cámara no es marcar el punto: la localidad ya alcanza como
     * ubicación, y el marcador sigue siendo del usuario. La localidad trae sus
     * coordenadas en la misma respuesta, así que el centrado no cuesta ninguna
     * request extra — algo que el front web tiene disponible y no aprovecha.
     */
    fun selectLocality(localityId: Int?) {
        val locality = _state.value.localities.firstOrNull { it.id == localityId }
        val center = locality?.let { l ->
            l.latitude?.let { lat -> l.longitude?.let { lng -> lat to lng } }
        }

        _state.update {
            it.copy(
                localityId = localityId,
                centerOn = center ?: it.centerOn,
                fieldErrors = it.fieldErrors - "ubicacion",
                formError = null,
            )
        }
    }

    // ── Punto del teléfono ───────────────────────────────────────────────────

    /**
     * Llena lat/lng con la posición actual.
     *
     * La pantalla llama a esto **después** de que el permiso fue concedido. Un
     * `null` acá no es un error del usuario: puede ser el GPS apagado o un
     * primer fix que no llegó, y el formulario sigue siendo válido eligiendo la
     * localidad a mano.
     */
    fun useCurrentLocation() {
        if (_state.value.locating) return
        _state.update { it.copy(locating = true, locationError = null) }

        viewModelScope.launch {
            val point = locationProvider.currentPoint()
            _state.update {
                if (point == null) {
                    it.copy(
                        locating = false,
                        locationError = "No se pudo obtener tu ubicación. " +
                            "Revisá que el GPS esté encendido, o elegí la localidad a mano.",
                    )
                } else {
                    it.copy(
                        locating = false,
                        latitude = point.latitude,
                        longitude = point.longitude,
                        // Además de marcar el punto, lleva la cámara: si el mapa
                        // quedara en Buenos Aires, el usuario no vería el
                        // marcador que acaba de aparecer.
                        centerOn = point.latitude to point.longitude,
                        resolvedAddress = null,
                        geocodingError = null,
                        fieldErrors = it.fieldErrors - "ubicacion",
                        formError = null,
                    )
                }
            }
        }
    }

    fun onLocationPermissionDenied() {
        _state.update {
            it.copy(
                locating = false,
                locationError = "Sin permiso de ubicación no podemos marcar el punto. " +
                    "Podés elegir la localidad a mano.",
            )
        }
    }

    /** Toque o arrastre del marcador en el mapa. */
    fun setPoint(latitude: Double, longitude: Double) {
        _state.update {
            it.copy(
                latitude = latitude,
                longitude = longitude,
                locationError = null,
                // La dirección que se había resuelto era de otro punto: dejarla
                // en pantalla invitaría a confirmar una calle que ya no
                // corresponde al marcador.
                resolvedAddress = null,
                geocodingError = null,
                fieldErrors = it.fieldErrors - "ubicacion",
                formError = null,
            )
        }
    }

    fun clearPoint() {
        _state.update {
            it.copy(
                latitude = null,
                longitude = null,
                locationError = null,
                resolvedAddress = null,
                geocodingError = null,
            )
        }
    }

    /**
     * Le pregunta a Nominatim qué dirección hay en el punto marcado.
     *
     * Va detrás de un botón y no automáticamente en cada toque: la política de
     * uso de OSM pide como máximo una request por segundo, y arrastrar el
     * marcador genera decenas de posiciones intermedias. El front web lo llama
     * en cada `dragend` sin debounce, que es la forma conocida de ganarse un
     * bloqueo por IP.
     */
    fun resolveAddress() {
        val current = _state.value
        val lat = current.latitude ?: return
        val lng = current.longitude ?: return
        if (current.geocoding) return

        _state.update { it.copy(geocoding = true, geocodingError = null) }

        viewModelScope.launch {
            when (val result = geocodingRepository.reverse(lat, lng)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(geocoding = false, resolvedAddress = result.data) }
                    matchLocality(result.data)
                }

                else -> _state.update {
                    it.copy(
                        geocoding = false,
                        // El punto ya está marcado y la denuncia es válida sin
                        // esto: es una comodidad que falló, no un error.
                        geocodingError = "No se pudo resolver la dirección. " +
                            "El punto quedó marcado igual; podés escribir la calle a mano.",
                    )
                }
            }
        }
    }

    /**
     * Traduce el nombre de localidad de OSM al `localityId` del catálogo.
     *
     * Es la pieza que faltaba para que el punto del mapa sirva de algo en el PDF
     * público: ese reporte no muestra la calle —es dato sensible y el backend lo
     * omite a propósito—, así que sin `localityId` sale literalmente sin
     * ubicación. Marcar el punto en el mapa producía exactamente eso.
     *
     * El match no se aplica solo: alimenta la misma tarjeta de confirmación que
     * ya usaba la calle. La preocupación de no adivinar sigue en pie; lo que
     * cambia es que ahora hay a quién preguntarle.
     */
    private suspend fun matchLocality(address: ResolvedAddress) {
        val name = address.locality ?: return
        val countryId = _state.value.countryId

        val results = when (val r = geoRepository.searchLocalities(name, countryId)) {
            is ApiResult.Success -> r.data
            // Silencioso a propósito: la calle ya se propuso y esto es una
            // mejora encima. Un cartel de error acá haría ruido sobre algo que
            // el usuario todavía puede resolver con los desplegables.
            else -> return
        }

        _state.update { it.copy(resolvedLocality = results.bestMatch(name, address.province)) }
    }

    /**
     * Confirma la dirección propuesta y la copia a los campos.
     *
     * Las coordenadas y la dirección son cosas distintas: el punto ya viaja
     * desde que se marcó, y la calle **sólo** si el usuario acepta. Descartar la
     * propuesta y quedarse con el punto pelado es un estado válido.
     */
    fun applyResolvedAddress() {
        val address = _state.value.resolvedAddress ?: return
        val locality = _state.value.resolvedLocality

        _state.update {
            it.copy(
                streetType = StreetType.entries.firstOrNull { t -> t.apiValue == address.streetType }
                    ?: it.streetType,
                streetName = address.streetName ?: it.streetName,
                streetNumber = address.streetNumber ?: it.streetNumber,
                resolvedAddress = null,
                resolvedLocality = null,
            )
        }

        locality?.let { applyMatchedLocality(it) }
    }

    /**
     * Deja la cascada entera coherente con la localidad que se acaba de aceptar.
     *
     * No alcanza con escribir `localityId`: los desplegables se llenan por
     * nivel, así que sin cargar los de arriba el usuario vería la localidad
     * elegida sobre una provincia en blanco, y tocar cualquiera de los otros
     * niveles la borraría. El resultado de la búsqueda ya trae la jerarquía, así
     * que las dos requests son sólo para poblar las listas.
     */
    private fun applyMatchedLocality(locality: LocalityFullDto) {
        val provinceId = locality.adminLevel1?.id
        val departmentId = locality.adminLevel2?.id

        _state.update {
            it.copy(
                provinceId = provinceId ?: it.provinceId,
                departmentId = departmentId ?: it.departmentId,
                localityId = locality.id,
                fieldErrors = it.fieldErrors - "ubicacion",
                formError = null,
            )
        }

        viewModelScope.launch {
            if (provinceId != null) {
                (geoRepository.departments(provinceId) as? ApiResult.Success)?.let { r ->
                    _state.update {
                        it.copy(
                            departments = r.data,
                            etiquetaNivel2 = LocationLabels.nivel2(
                                LocationLabels.tipoComun(r.data) { d -> d.type },
                            ),
                        )
                    }
                }
            }
            if (departmentId != null) {
                (geoRepository.localities(departmentId) as? ApiResult.Success)?.let { r ->
                    _state.update {
                        it.copy(
                            localities = r.data,
                            etiquetaLocalidad = LocationLabels.localidad(
                                LocationLabels.tipoComun(r.data) { l -> l.type },
                            ),
                        )
                    }
                }
            }
        }
    }

    fun discardResolvedAddress() =
        _state.update { it.copy(resolvedAddress = null, resolvedLocality = null) }

    // ── Campos ───────────────────────────────────────────────────────────────

    fun setDate(date: LocalDate) = _state.update { it.copy(theftDate = date) }
    fun setTimeSlot(value: TheftTimeSlot?) = _state.update { it.copy(timeSlot = value) }
    fun setDescription(value: String) = _state.update { it.copy(description = value) }
    fun setStreetType(value: StreetType?) = _state.update { it.copy(streetType = value) }
    fun setStreetName(value: String) = _state.update { it.copy(streetName = value) }
    fun setStreetNumber(value: String) = _state.update { it.copy(streetNumber = value) }
    fun setReference(value: String) = _state.update { it.copy(reference = value) }
    fun setContactPhone(value: String) = _state.update { it.copy(contactPhone = value) }
    fun setContactEmail(value: String) = _state.update { it.copy(contactEmail = value) }
    fun setContactPublic(value: Boolean) = _state.update { it.copy(contactPublic = value) }
    fun setRewardOffered(value: Boolean) = _state.update { it.copy(rewardOffered = value) }

    // ── Envío ────────────────────────────────────────────────────────────────

    fun submit() {
        val current = _state.value
        if (current.submitting || current.createdReportId != null) return

        val errors = validate(current)
        if (errors.isNotEmpty()) {
            // El resumen junto al botón no es redundante con los errores de cada
            // campo: el botón está al final de un formulario largo, y el error
            // de ubicación se pinta media pantalla más arriba. Sin esto, apretar
            // "Presentar la denuncia" se siente como que la app no hizo nada.
            _state.update {
                it.copy(
                    fieldErrors = errors,
                    formError = if (errors.size == 1) errors.values.first()
                    else "Revisá los campos marcados: ${errors.keys.joinToString(", ")}.",
                )
            }
            return
        }

        val id = bicycleId ?: return
        _state.update { it.copy(submitting = true, formError = null, fieldErrors = emptyMap()) }

        viewModelScope.launch {
            when (val result = theftRepository.reportTheft(id, current.toRequest())) {
                is ApiResult.Success -> _state.update {
                    it.copy(submitting = false, createdReportId = result.data.id)
                }

                // Antes de dar el envío por fallido hay que ir a ver si la
                // denuncia quedó hecha igual. Ver [buscarDenunciaExistente].
                else -> {
                    val yaExistente = buscarDenunciaExistente(id)
                    if (yaExistente != null) {
                        _state.update { it.copy(submitting = false, createdReportId = yaExistente) }
                    } else {
                        _state.update {
                            it.copy(
                                submitting = false,
                                formError = result.toUserMessage(
                                    "No se pudo registrar la denuncia.",
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Busca una denuncia activa de esta bici, para resolver un envío ambiguo.
     *
     * Existe por un caso real y reproducible. El backend commitea la denuncia
     * antes de sus pasos best-effort, y uno de ellos —registrar la ubicación en
     * geoposicion— puede tardar más que el techo de espera del gateway. Cuando
     * eso pasa, el gateway devuelve 503 y corta, **con la denuncia ya creada**.
     * El usuario veía "puede haberse completado igual: verificá antes de
     * repetirla", y al reintentar —que es lo que hace cualquiera— se comía un
     * "An active theft report already exists for this bicycle": en inglés, en
     * rojo, y describiendo como una falla algo que en realidad fue un éxito.
     *
     * Pedirle al usuario que verifique era hacerle hacer a mano algo que la app
     * puede hacer sola, y en el peor momento posible: acaban de robarle la bici.
     *
     * Cubre los dos caminos con una sola consulta, porque el sintoma es el
     * mismo: el 503 con la denuncia creada, y el rechazo por duplicado de un
     * reintento anterior.
     *
     * @return el id de la denuncia activa, o null si de verdad no se creó nada.
     */
    private suspend fun buscarDenunciaExistente(bicycleId: String): String? {
        val reportes = theftRepository.myReports()
        if (reportes !is ApiResult.Success) return null

        // ACTIVE y no "la última": una bici puede tener denuncias viejas ya
        // cerradas o con la bici recuperada, y ésas no bloquean una nueva.
        return reportes.data
            .firstOrNull { it.bicycleId == bicycleId && it.status == ReportStatus.ACTIVE }
            ?.id
    }

    /**
     * Genera el PDF de una denuncia **ya creada**.
     *
     * Que falle no toca la denuncia, y por eso el error lo dice explícitamente:
     * confundir "no salió el PDF" con "no se hizo la denuncia" es el peor
     * malentendido posible en esta pantalla.
     */
    fun downloadPdf() {
        val reportId = _state.value.createdReportId ?: return
        if (_state.value.generatingPdf) return

        _state.update { it.copy(generatingPdf = true, pdfError = null) }

        viewModelScope.launch {
            when (val result = theftRepository.generatePdf(reportId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(generatingPdf = false, pdfUrl = result.data.presignedUrl)
                }

                else -> _state.update {
                    it.copy(
                        generatingPdf = false,
                        pdfError = "No se pudo generar el PDF. La denuncia ya quedó hecha; " +
                            "podés descargarlo más tarde.",
                    )
                }
            }
        }
    }

    /** La pantalla avisa que ya abrió la URL, para no volver a abrirla en cada recomposición. */
    fun onPdfOpened() = _state.update { it.copy(pdfUrl = null) }

    private fun validate(s: ReportTheftUiState): Map<String, String> = buildMap {
        if (s.theftDate > s.maxDate) put("fecha", "La fecha no puede ser futura.")

        if (!s.hasLocation) {
            put(
                "ubicacion",
                if (s.latitude != null) {
                    // Ya marcó el punto: el pedido tiene que decir qué falta y por qué,
                    // o se lee como que la app no reconoce lo que ya hizo.
                    "Elegí la localidad. El punto del mapa no alcanza: la provincia y la " +
                        "localidad del PDF salen de ahí, y sin ellas la denuncia va a la " +
                        "policía sin jurisdicción."
                } else {
                    "Decinos dónde fue: elegí la localidad, o marcá el punto en el mapa y " +
                        "confirmá la dirección."
                },
            )
        }

        if (s.description.length > ReportTheftRequestDto.MAX_DESCRIPTION) {
            put("descripcion", "Máximo ${ReportTheftRequestDto.MAX_DESCRIPTION} caracteres.")
        }
        if (s.streetName.length > TheftLocationDto.MAX_STREET_NAME) {
            put("calle", "Máximo ${TheftLocationDto.MAX_STREET_NAME} caracteres.")
        }
        if (s.streetNumber.length > TheftLocationDto.MAX_STREET_NUMBER) {
            put("altura", "Máximo ${TheftLocationDto.MAX_STREET_NUMBER} caracteres.")
        }
        if (s.reference.length > TheftLocationDto.MAX_REFERENCE) {
            put("referencia", "Máximo ${TheftLocationDto.MAX_REFERENCE} caracteres.")
        }
        if (s.contactPhone.length > ReportTheftRequestDto.MAX_CONTACT_PHONE) {
            put("telefono", "Máximo ${ReportTheftRequestDto.MAX_CONTACT_PHONE} caracteres.")
        }
        if (s.contactEmail.length > ReportTheftRequestDto.MAX_CONTACT_EMAIL) {
            put("email", "Máximo ${ReportTheftRequestDto.MAX_CONTACT_EMAIL} caracteres.")
        }

        // La recompensa ya no valida nada: es un booleano, y un booleano no puede
        // estar mal escrito.
    }

    private fun ReportTheftUiState.toRequest() = ReportTheftRequestDto(
        theftDate = theftDate,
        theftTimeApprox = timeSlot?.apiValue,
        theftLocation = buildLocation(),
        theftDescription = description.trim().ifBlank { null },
        contactPhone = contactPhone.trim().ifBlank { null },
        contactEmail = contactEmail.trim().ifBlank { null },
        contactPublic = contactPublic,
        rewardOffered = rewardOffered,
    )

    /**
     * Arma la ubicación, o `null` si no hay nada que mandar.
     *
     * Ese `null` **no** puede llegar al backend en una denuncia válida —lo
     * impide [ReportTheftUiState.hasLocation]— y por eso existe igual: durante
     * mucho tiempo el servidor respondía 500 con la denuncia ya creada cuando
     * llegaba nulo. Está arreglado del lado del servidor, pero la forma sigue
     * siendo la misma que el front web y no conviene inventar otra.
     */
    private fun ReportTheftUiState.buildLocation(): TheftLocationDto? {
        val hasStreet = streetType != null || streetName.isNotBlank()
        if (localityId == null && !hasStreet && latitude == null && longitude == null) return null

        return TheftLocationDto(
            localityId = localityId,
            streetType = streetType?.apiValue,
            streetName = streetName.trim().ifBlank { null },
            streetNumber = streetNumber.trim().ifBlank { null },
            reference = reference.trim().ifBlank { null },
            latitude = latitude,
            longitude = longitude,
            // "EXACT" está reservado a las pistas, donde el informante marca el
            // punto donde vio la bici. Acá el punto es del teléfono de quien
            // denuncia, que no necesariamente estaba ahí cuando se la robaron.
            precision = if (latitude != null) "APPROXIMATE" else null,
        )
    }
}

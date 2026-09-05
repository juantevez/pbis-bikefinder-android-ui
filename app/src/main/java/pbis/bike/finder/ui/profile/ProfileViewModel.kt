package pbis.bike.finder.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.AdminLevel1Dto
import pbis.bike.finder.data.remote.dto.AdminLevel2Dto
import pbis.bike.finder.data.remote.dto.CountryDto
import pbis.bike.finder.data.remote.dto.E164_REGEX
import pbis.bike.finder.data.remote.dto.Gender
import pbis.bike.finder.data.remote.dto.LocalityDto
import pbis.bike.finder.data.remote.dto.NotificationPreferencesDto
import pbis.bike.finder.data.remote.dto.UpdateProfileRequestDto
import pbis.bike.finder.data.remote.dto.UserInfoDto
import pbis.bike.finder.data.repository.AuthRepository
import pbis.bike.finder.data.repository.GeoRepository
import pbis.bike.finder.data.repository.NotificationRepository
import pbis.bike.finder.ui.common.LocationLabel
import pbis.bike.finder.ui.common.LocationLabels
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.matchesName
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

private const val PROFILE_ERROR = "No pudimos cargar tu perfil."
private const val SAVE_ERROR = "No pudimos guardar los cambios."
private const val GEO_ERROR =
    "No pudimos cargar la lista de ubicaciones. Tu ubicación queda como está."
private const val NOTIF_LOAD_ERROR =
    "No pudimos cargar tus preferencias de aviso. Reintentá en un momento."
private const val NOTIF_SAVE_ERROR =
    "No pudimos guardar tu preferencia de avisos. Probá de nuevo en unos minutos."

data class ProfileUiState(
    val loading: Boolean = true,
    val profile: UserInfoDto? = null,
    val loadError: String? = null,
    val canRetryLoad: Boolean = false,

    // ── Modo edición ─────────────────────────────────────────────────────────
    val editing: Boolean = false,
    val fullName: String = "",
    val phoneNumber: String = "",
    val gender: Gender? = null,
    val birthDate: LocalDate? = null,
    val saving: Boolean = false,
    val formError: String? = null,
    val phoneError: String? = null,

    // ── Cascada geográfica ───────────────────────────────────────────────────
    val countries: List<CountryDto> = emptyList(),
    val provinces: List<AdminLevel1Dto> = emptyList(),
    val departments: List<AdminLevel2Dto> = emptyList(),
    val localities: List<LocalityDto> = emptyList(),
    val countryId: Int? = null,
    val provinceId: Int? = null,
    val departmentId: Int? = null,
    val localityId: Int? = null,
    val loadingGeo: Boolean = false,
    val geoError: String? = null,

    /**
     * Cómo se llama cada nivel en el país de esta ubicación. Sale del `type` que
     * trae el catálogo, no de un `if` por país. Ver [LocationLabels].
     *
     * Sirve para los dos modos: en edición sale de la lista de cada nivel; en
     * lectura, de la cadena tipada de la localidad guardada —el perfil guarda los
     * **nombres** y la columna se llama `department_name` aunque adentro tenga
     * una provincia chilena—.
     */
    val etiquetaNivel1: LocationLabel = LocationLabels.NIVEL1_DEFAULT,
    val etiquetaNivel2: LocationLabel = LocationLabels.NIVEL2_DEFAULT,
    val etiquetaLocalidad: LocationLabel = LocationLabels.LOCALIDAD_DEFAULT,

    // ── Notificaciones ───────────────────────────────────────────────────────
    val notifications: NotificationPreferencesDto? = null,
    val savingNotifications: Boolean = false,
    val notificationsError: String? = null,

    /** Confirmación efímera tras guardar. La pantalla la muestra y la limpia. */
    val message: String? = null,
) {
    /**
     * El switch de avisos sólo se toca cuando se sabe qué hay guardado.
     *
     * Un control habilitado sobre un estado desconocido miente: se vería apagado
     * —el default del DTO— y el usuario creería que ya eligió eso.
     */
    val notificationsReady: Boolean get() = notifications != null && !savingNotifications
}

/**
 * Mi perfil, equivalente a `perfil.html` del front web.
 *
 * Junta tres orígenes que no se conocen entre sí: el perfil de auth-service, la
 * jerarquía de location-service y las preferencias de notification-service. Los
 * tres se cargan en paralelo y **fallan por separado**: que se caiga
 * notification-service no puede dejar sin ver ni editar los datos personales,
 * que es a lo que el usuario vino.
 *
 * La geografía se pide recién al entrar en edición y no al abrir la pantalla:
 * son hasta cuatro requests encadenadas para llenar unos desplegables que sólo
 * existen en el formulario. En modo lectura los nombres ya vienen
 * desnormalizados dentro del perfil.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val geoRepository: GeoRepository,
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        loadProfile()
        loadNotifications()
    }

    /**
     * Trae el perfil salteando el cache.
     *
     * `forceRefresh` porque el cache de [AuthRepository] lo llenó el login y esta
     * es justamente la pantalla donde el dato puede haber cambiado —desde otro
     * dispositivo, o desde la web.
     */
    fun loadProfile() {
        _state.update { it.copy(loading = true, loadError = null) }

        viewModelScope.launch {
            when (val result = authRepository.profile(forceRefresh = true)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(loading = false, profile = result.data) }
                    rotularUbicacionGuardada(result.data.location?.localityId)
                }

                else -> _state.update {
                    it.copy(
                        loading = false,
                        loadError = result.toUserMessage(PROFILE_ERROR),
                        canRetryLoad = result.isSafeToRetry(),
                    )
                }
            }
        }
    }

    // ── Modo edición ─────────────────────────────────────────────────────────

    /**
     * Rotula la ubicación guardada según el país al que pertenece.
     *
     * El perfil guarda los **nombres** ya elegidos, no los tipos: la columna se
     * llama `department_name` aunque lo que tenga adentro sea una provincia
     * chilena. Para saber cómo se llama cada nivel hay que preguntarle al
     * catálogo por la localidad guardada, que en una sola llamada devuelve la
     * cadena entera tipada (comuna → provincia → región).
     *
     * Si falla, los rótulos quedan en el default argentino y listo: es una
     * etiqueta, no un dato, y no justifica romper la vista del perfil.
     */
    private suspend fun rotularUbicacionGuardada(localityId: Int?) {
        if (localityId == null) return
        val result = geoRepository.locality(localityId)
        if (result !is ApiResult.Success) return

        val loc = result.data
        _state.update {
            it.copy(
                etiquetaNivel1 = LocationLabels.nivel1(loc.adminLevel1?.type),
                etiquetaNivel2 = LocationLabels.nivel2(loc.adminLevel2?.type),
                etiquetaLocalidad = LocationLabels.localidad(loc.type),
            )
        }
    }

    /**
     * Copia el perfil a los campos editables.
     *
     * El formulario arranca de una copia y no apunta al perfil: cancelar tiene
     * que devolver lo que había, y eso sólo funciona si lo que había quedó
     * intacto en otro lado.
     */
    fun enterEditMode() {
        val profile = _state.value.profile ?: return

        _state.update {
            it.copy(
                editing = true,
                fullName = profile.fullName.orEmpty(),
                phoneNumber = profile.phoneNumber.orEmpty(),
                gender = Gender.fromApi(profile.gender),
                birthDate = profile.birthDate,
                formError = null,
                phoneError = null,
            )
        }

        loadCountries()
    }

    fun cancelEdit() {
        _state.update { it.copy(editing = false, formError = null, phoneError = null) }
    }

    fun onFullNameChange(value: String) {
        _state.update { it.copy(fullName = value, formError = null) }
    }

    fun onPhoneChange(value: String) {
        _state.update { it.copy(phoneNumber = value, phoneError = null, formError = null) }
    }

    fun onGenderChange(value: Gender?) {
        _state.update { it.copy(gender = value, formError = null) }
    }

    fun onBirthDateChange(value: LocalDate?) {
        _state.update { it.copy(birthDate = value, formError = null) }
    }

    // ── Cascada geográfica ───────────────────────────────────────────────────

    /**
     * Países, y detrás la jerarquía ya guardada del usuario.
     *
     * Reconstruirla cuesta las cuatro requests encadenadas porque el perfil
     * guarda los **nombres** de provincia, partido y país pero sólo el *id* de la
     * localidad: no hay forma de resolver los ids intermedios sin recorrer los
     * niveles. El front web hace lo mismo y además busca por texto del `<option>`.
     * Acá se compara normalizado, que es lo mismo pero tolera un acento distinto.
     */
    fun loadCountries() {
        if (_state.value.countries.isNotEmpty()) return
        _state.update { it.copy(loadingGeo = true, geoError = null) }

        viewModelScope.launch {
            when (val result = geoRepository.countries()) {
                is ApiResult.Success -> {
                    _state.update { it.copy(loadingGeo = false, countries = result.data) }
                    restoreSavedLocation(result.data)
                }

                else -> _state.update {
                    it.copy(loadingGeo = false, geoError = result.toUserMessage(GEO_ERROR))
                }
            }
        }
    }

    private suspend fun restoreSavedLocation(countries: List<CountryDto>) {
        val saved = _state.value.profile?.location ?: return

        // **Sin país guardado no se preselecciona nada.** Había un fallback a
        // Argentina y hacía dos cosas mal, las dos en silencio:
        //
        //   1. Con el país vacío en el perfil, la vista decía "No especificado" y
        //      el formulario mostraba Argentina: un valor que no está guardado y
        //      que el usuario no tiene cómo distinguir de uno que sí.
        //   2. Con un país que no fuera Argentina era peor. El fallback corre
        //      cuando el nombre guardado no matchea —un acento raro, un renombre
        //      del catálogo— y ahí a alguien con el país en Chile le mostraba
        //      Argentina y se lo pisaba al guardar.
        //
        // Sin país guardado la cadena de abajo queda vacía, que es lo que
        // corresponde: no hay de dónde deducir el resto.
        val country = countries.firstOrNull { it.name.matchesName(saved.countryName) }
            ?: return
        val provinces = fetchProvinces(country.id) ?: return
        _state.update {
            it.copy(
                countryId = country.id,
                provinces = provinces,
                etiquetaNivel1 = LocationLabels.nivel1(
                    LocationLabels.tipoComun(provinces) { p -> p.type },
                ),
            )
        }

        val province = provinces.firstOrNull { it.name.matchesName(saved.provinceName) } ?: return
        val departments = fetchDepartments(province.id) ?: return
        _state.update {
            it.copy(
                provinceId = province.id,
                departments = departments,
                etiquetaNivel2 = LocationLabels.nivel2(
                    LocationLabels.tipoComun(departments) { d -> d.type },
                ),
            )
        }

        val department = departments.firstOrNull { it.name.matchesName(saved.departmentName) } ?: return
        val localities = fetchLocalities(department.id) ?: return
        _state.update {
            it.copy(
                departmentId = department.id,
                localities = localities,
                etiquetaLocalidad = LocationLabels.localidad(
                    LocationLabels.tipoComun(localities) { l -> l.type },
                ),
                // El id guardado se usa tal cual: es el dato exacto, y compararlo
                // por nombre contra la lista sería degradarlo a una coincidencia
                // de texto teniendo la identidad a mano.
                localityId = saved.localityId?.takeIf { id -> localities.any { l -> l.id == id } },
            )
        }
    }

    fun selectCountry(countryId: Int?) {
        // Cada nivel invalida los de abajo. Dejar colgada una localidad de otra
        // provincia guarda una ubicación que no existe.
        _state.update {
            it.copy(
                countryId = countryId,
                provinceId = null,
                departmentId = null,
                localityId = null,
                provinces = emptyList(),
                departments = emptyList(),
                localities = emptyList(),
                // Los rótulos de abajo vuelven al genérico junto con las listas:
                // cómo se llaman los niveles del país nuevo recién se sabe cuando
                // llegue su lista.
                etiquetaNivel1 = LocationLabels.NIVEL1_DEFAULT,
                etiquetaNivel2 = LocationLabels.NIVEL2_DEFAULT,
                etiquetaLocalidad = LocationLabels.LOCALIDAD_DEFAULT,
                geoError = null,
            )
        }
        countryId ?: return

        viewModelScope.launch {
            fetchProvinces(countryId)?.let { list ->
                _state.update {
                    it.copy(
                        provinces = list,
                        etiquetaNivel1 = LocationLabels.nivel1(
                            LocationLabels.tipoComun(list) { p -> p.type },
                        ),
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
            fetchDepartments(provinceId)?.let { list ->
                _state.update {
                    it.copy(
                        departments = list,
                        etiquetaNivel2 = LocationLabels.nivel2(
                            LocationLabels.tipoComun(list) { d -> d.type },
                        ),
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
            fetchLocalities(departmentId)?.let { list ->
                _state.update {
                    it.copy(
                        localities = list,
                        etiquetaLocalidad = LocationLabels.localidad(
                            LocationLabels.tipoComun(list) { l -> l.type },
                        ),
                    )
                }
            }
        }
    }

    fun selectLocality(localityId: Int?) {
        _state.update { it.copy(localityId = localityId, formError = null) }
    }

    private suspend fun fetchProvinces(countryId: Int) =
        fetchGeoLevel { geoRepository.provinces(countryId) }

    private suspend fun fetchDepartments(provinceId: Int) =
        fetchGeoLevel { geoRepository.departments(provinceId) }

    private suspend fun fetchLocalities(departmentId: Int) =
        fetchGeoLevel { geoRepository.localities(departmentId) }

    /** Un nivel de la cascada. `null` si falló — el error ya quedó en el estado. */
    private suspend fun <T> fetchGeoLevel(request: suspend () -> ApiResult<List<T>>): List<T>? {
        _state.update { it.copy(loadingGeo = true) }

        return when (val result = request()) {
            is ApiResult.Success -> {
                _state.update { it.copy(loadingGeo = false) }
                result.data
            }

            else -> {
                _state.update {
                    it.copy(loadingGeo = false, geoError = result.toUserMessage(GEO_ERROR))
                }
                null
            }
        }
    }

    // ── Guardado ─────────────────────────────────────────────────────────────

    /**
     * Guarda los datos personales y la ubicación.
     *
     * El teléfono se valida contra el mismo regex E.164 del backend antes de
     * salir: sin eso el usuario se entera del formato mal recién después del
     * round-trip, y con un error genérico.
     *
     * Los nombres de la jerarquía viajan junto con el `localityId` porque el
     * backend los guarda desnormalizados. Se toman de las listas cargadas y no de
     * lo que ya tenía el perfil: si cambió de localidad, arrastrar los nombres
     * viejos guardaría una ubicación mitad nueva y mitad vieja.
     */
    fun save() {
        val current = _state.value
        if (current.saving) return

        val phone = current.phoneNumber.trim()
        if (phone.isNotBlank() && !E164_REGEX.matches(phone)) {
            _state.update {
                it.copy(
                    phoneError = "Usá el formato internacional, con el + adelante. " +
                        "Ej: +5491122334455",
                )
            }
            return
        }

        _state.update { it.copy(saving = true, formError = null) }

        viewModelScope.launch {
            val guardada = current.profile?.location
            // "La lista está vacía" no alcanza para decidir: también queda vacía
            // cuando el usuario limpió el nivel de arriba a propósito. Lo que
            // habilita el fallback es que además haya fallado el catálogo.
            val falloElCatalogo = current.geoError != null

            val request = UpdateProfileRequestDto(
                fullName = current.fullName.trim().ifBlank { null },
                phoneNumber = phone.ifBlank { null },
                gender = current.gender?.name,
                birthDate = current.birthDate,
                localityId = current.localityId
                    ?: guardada?.localityId
                        ?.takeIf { falloElCatalogo && current.localities.isEmpty() },
                localityName = nombreElegido(
                    noSePudoCargar = falloElCatalogo && current.localities.isEmpty(),
                    elegido = current.localities.firstOrNull { it.id == current.localityId }?.name,
                    guardado = guardada?.localityName,
                ),
                departmentName = nombreElegido(
                    noSePudoCargar = falloElCatalogo && current.departments.isEmpty(),
                    elegido = current.departments
                        .firstOrNull { it.id == current.departmentId }?.name,
                    guardado = guardada?.departmentName,
                ),
                provinceName = nombreElegido(
                    noSePudoCargar = falloElCatalogo && current.provinces.isEmpty(),
                    elegido = current.provinces.firstOrNull { it.id == current.provinceId }?.name,
                    guardado = guardada?.provinceName,
                ),
                countryName = nombreElegido(
                    noSePudoCargar = falloElCatalogo && current.countries.isEmpty(),
                    elegido = current.countries.firstOrNull { it.id == current.countryId }?.name,
                    guardado = guardada?.countryName,
                ),
            )

            when (val result = authRepository.updateProfile(request)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        saving = false,
                        editing = false,
                        profile = result.data,
                        message = "Perfil actualizado",
                    )
                }

                else -> _state.update {
                    it.copy(saving = false, formError = result.toUserMessage(SAVE_ERROR))
                }
            }
        }
    }

    /**
     * El nombre elegido en un nivel de la cascada, con una salvedad importante:
     * si la lista **no se pudo cargar** se devuelve lo que ya estaba guardado en
     * vez de null.
     *
     * No alcanza con mirar si la lista está vacía —también lo está cuando el
     * usuario limpió el nivel de arriba a propósito, y ahí resucitar el valor
     * viejo sería ignorarlo—: hace falta que además el catálogo haya fallado.
     *
     * `PUT /auth/me` **no es un parche para la ubicación**: `fullName`, teléfono,
     * género y fecha van con un `if (x != null)`, pero los cuatro campos de la
     * ubicación se asignan siempre. Un null —que con `explicitNulls = false` ni
     * siquiera viaja— los borra. Sin esta guarda, location-service caído al abrir
     * el formulario alcanzaba para perder la ubicación al guardar, sin un solo
     * error a la vista.
     *
     * Si la lista **sí** se cargó y el usuario no eligió nada, ahí el null es la
     * respuesta correcta: pudo elegir y decidió dejarlo vacío.
     */
    private fun nombreElegido(
        noSePudoCargar: Boolean,
        elegido: String?,
        guardado: String?,
    ): String? = if (noSePudoCargar) guardado else elegido

    // ── Notificaciones ───────────────────────────────────────────────────────

    fun loadNotifications() {
        _state.update { it.copy(notificationsError = null) }

        viewModelScope.launch {
            when (val result = notificationRepository.preferences()) {
                is ApiResult.Success -> _state.update { it.copy(notifications = result.data) }

                else -> _state.update {
                    it.copy(notificationsError = result.toUserMessage(NOTIF_LOAD_ERROR))
                }
            }
        }
    }

    /**
     * Prende o apaga los avisos por email.
     *
     * El switch **no se mueve por el click**: se mueve cuando el backend confirma.
     * Un switch optimista que después falla deja al usuario creyendo que apagó
     * unos avisos que van a seguir llegando —o peor, que prendió unos que no.
     *
     * Se toma el estado que devolvió el servidor y no el que se pidió: si validó
     * y terminó en otra cosa, el control tiene que mostrar la verdad.
     */
    fun setEmailNotifications(enabled: Boolean) {
        val current = _state.value.notifications ?: return
        if (current.emailEnabled == enabled) return

        _state.update { it.copy(savingNotifications = true, notificationsError = null) }

        viewModelScope.launch {
            when (val result = notificationRepository.setEmailEnabled(current, enabled)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        savingNotifications = false,
                        notifications = result.data,
                        message = if (result.data.emailEnabled) {
                            "Vas a recibir los avisos por email"
                        } else {
                            "Ya no vas a recibir avisos por email"
                        },
                    )
                }

                else -> _state.update {
                    it.copy(
                        savingNotifications = false,
                        notificationsError = result.toUserMessage(NOTIF_SAVE_ERROR),
                    )
                }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * Cierra la sesión.
     *
     * `AuthRepository.logout()` avisa al backend, limpia los tokens y cierra la
     * sesión en el `SessionManager`; la navegación escucha ese evento y vuelve al
     * login. Por eso acá no hay callback: la pantalla no tiene que saber adónde
     * ir, igual que cuando la sesión se vence sola.
     */
    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }
}

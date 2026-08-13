package pbis.bike.finder.ui.addbike

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.BikeTypeDto
import pbis.bike.finder.data.remote.dto.BrandDto
import pbis.bike.finder.data.remote.dto.CatalogBikeDto
import pbis.bike.finder.data.remote.dto.ColorDto
import pbis.bike.finder.data.remote.dto.ColorwayDto
import pbis.bike.finder.data.remote.dto.FrameSizeDto
import pbis.bike.finder.data.remote.dto.RegisterFromCatalogRequestDto
import pbis.bike.finder.data.remote.dto.RegisterManuallyRequestDto
import pbis.bike.finder.data.remote.dto.PhotoType
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.PendingPhoto
import pbis.bike.finder.data.repository.PhotoUploader
import pbis.bike.finder.data.repository.CatalogRepository
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

/** Las dos formas de dar de alta una bici. */
enum class AddBikeMode { CATALOG, MANUAL }

data class AddBikeUiState(
    val mode: AddBikeMode = AddBikeMode.CATALOG,

    // Catálogo de referencia
    val loadingCatalog: Boolean = true,
    val catalogError: String? = null,
    val brands: List<BrandDto> = emptyList(),
    val bikeTypes: List<BikeTypeDto> = emptyList(),
    val colors: List<ColorDto> = emptyList(),

    // Alta desde catálogo
    val brandId: Long? = null,
    val bikeTypeId: Long? = null,
    val models: List<CatalogBikeDto> = emptyList(),
    val loadingModels: Boolean = false,
    val catalogBikeId: Long? = null,
    val colorways: List<ColorwayDto> = emptyList(),
    val colorwayId: Long? = null,
    val availableSizes: List<FrameSizeDto> = emptyList(),
    val loadingDetails: Boolean = false,

    // Alta manual
    val manualModel: String = "",
    val manualYear: String = "",
    val primaryColorId: Long? = null,
    val primaryColorCustom: String = "",
    val manualSizes: List<FrameSizeDto> = emptyList(),

    // Comunes
    val frameSize: String? = null,
    val serialNumber: String = "",
    val notes: String = "",

    // Fotos
    val photos: List<PendingPhoto> = emptyList(),
    val gpsAnalysisConsent: Boolean = false,
    val uploadingPhotos: Boolean = false,

    val submitting: Boolean = false,
    val formError: String? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val createdBikeId: String? = null,

    /**
     * Aviso de fotos que no entraron. La bici **ya está creada** cuando esto
     * aparece, así que no es un error del alta: es información sobre algo que
     * se puede reintentar después sin volver a registrar nada.
     */
    val photoWarning: String? = null,
)

/**
 * Wizard de alta, en sus dos modos.
 *
 * Es la pantalla más grande del front web (766 líneas de JS) y casi toda esa
 * complejidad es encadenamiento: elegir una cosa invalida la siguiente. La
 * lógica está portada, no reinventada — incluidos los reseteos, que son lo que
 * hace la diferencia entre guardar bien y guardar mal.
 */
@HiltViewModel
class AddBikeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val bicycleRepository: BicycleRepository,
    private val photoUploader: PhotoUploader,
) : ViewModel() {

    private val _state = MutableStateFlow(AddBikeUiState())
    val state: StateFlow<AddBikeUiState> = _state.asStateFlow()

    init {
        loadCatalog()
    }

    fun loadCatalog() {
        _state.update { it.copy(loadingCatalog = true, catalogError = null) }

        viewModelScope.launch {
            when (val result = catalogRepository.formData()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        loadingCatalog = false,
                        brands = result.data.frameBrands,
                        bikeTypes = result.data.bikeTypes,
                        colors = result.data.colors,
                    )
                }

                else -> _state.update {
                    it.copy(
                        loadingCatalog = false,
                        catalogError = result.toUserMessage("No se pudo cargar el catálogo."),
                    )
                }
            }
        }
    }

    fun setMode(mode: AddBikeMode) = _state.update { it.copy(mode = mode, formError = null) }

    // ── Alta desde catálogo ──────────────────────────────────────────────────

    /**
     * Cambiar de marca invalida todo lo que colgaba de ella: los modelos son de
     * una marca, y los colorways y los talles son de un modelo.
     */
    fun onBrandSelected(brandId: Long?) {
        _state.update {
            it.copy(
                brandId = brandId,
                catalogBikeId = null,
                models = emptyList(),
                colorways = emptyList(),
                colorwayId = null,
                availableSizes = emptyList(),
                frameSize = null,
                formError = null,
            )
        }
        if (brandId != null) loadModels()
    }

    /**
     * El tipo filtra los modelos en el modo catálogo, y determina el sistema de
     * talles en el manual. Son dos usos distintos del mismo campo.
     */
    fun onBikeTypeSelected(bikeTypeId: Long?) {
        _state.update {
            it.copy(
                bikeTypeId = bikeTypeId,
                catalogBikeId = null,
                models = emptyList(),
                colorways = emptyList(),
                colorwayId = null,
                availableSizes = emptyList(),
                manualSizes = emptyList(),
                frameSize = null,
            )
        }
        if (_state.value.mode == AddBikeMode.CATALOG) {
            if (_state.value.brandId != null) loadModels()
        } else {
            loadManualSizes(bikeTypeId)
        }
    }

    private fun loadModels() {
        val current = _state.value
        val brandId = current.brandId ?: return

        _state.update { it.copy(loadingModels = true) }

        viewModelScope.launch {
            when (val result = catalogRepository.bikesByBrand(brandId, current.bikeTypeId)) {
                is ApiResult.Success ->
                    _state.update { it.copy(loadingModels = false, models = result.data) }

                else -> _state.update {
                    it.copy(
                        loadingModels = false,
                        models = emptyList(),
                        formError = result.toUserMessage("No se pudieron cargar los modelos."),
                    )
                }
            }
        }
    }

    /**
     * Cambiar de modelo **resetea el colorway elegido**.
     *
     * No es prolijidad: los colorways pertenecen a un modelo. Sin este reseteo
     * queda seleccionado el del modelo anterior y se manda un `colorwayId` ajeno,
     * que el backend **descarta en silencio** — la bici termina guardada con
     * color "unknown" y nadie se entera hasta que hace falta identificarla. Es un
     * bug que el front web ya tuvo y arregló; está documentado en
     * `cargar-bici.js:170-176`.
     */
    fun onModelSelected(catalogBikeId: Long?) {
        _state.update {
            it.copy(
                catalogBikeId = catalogBikeId,
                colorwayId = null,
                colorways = emptyList(),
                availableSizes = emptyList(),
                frameSize = null,
                formError = null,
            )
        }
        if (catalogBikeId != null) loadBikeDetails(catalogBikeId)
    }

    private fun loadBikeDetails(catalogBikeId: Long) {
        _state.update { it.copy(loadingDetails = true) }

        viewModelScope.launch {
            when (val result = catalogRepository.bikeDetails(catalogBikeId)) {
                is ApiResult.Success -> _state.update {
                    val colorways = result.data.colorways
                    it.copy(
                        loadingDetails = false,
                        colorways = colorways,
                        // Preselección: el marcado como default, o el primero.
                        // Sólo si no hay ninguno elegido, para no pisar al usuario.
                        colorwayId = it.colorwayId
                            ?: colorways.firstOrNull { cw -> cw.isDefault }?.id
                            ?: colorways.firstOrNull()?.id,
                        // En el alta desde catálogo los talles salen del modelo,
                        // no del sistema de talles del tipo: son los que ese
                        // modelo realmente tuvo.
                        availableSizes = result.data.availableSizes,
                    )
                }

                else -> _state.update {
                    it.copy(
                        loadingDetails = false,
                        formError = result.toUserMessage("No se pudo cargar el modelo."),
                    )
                }
            }
        }
    }

    fun onColorwaySelected(colorwayId: Long?) = _state.update { it.copy(colorwayId = colorwayId) }

    // ── Alta manual ──────────────────────────────────────────────────────────

    private fun loadManualSizes(bikeTypeId: Long?) {
        val sizeSystemId = _state.value.bikeTypes
            .firstOrNull { it.id == bikeTypeId }
            ?.sizeSystemId

        if (sizeSystemId == null) {
            _state.update { it.copy(manualSizes = emptyList()) }
            return
        }

        viewModelScope.launch {
            val result = catalogRepository.sizesForSystem(sizeSystemId)
            if (result is ApiResult.Success) {
                _state.update { it.copy(manualSizes = result.data) }
            }
        }
    }

    fun onManualModelChange(value: String) = _state.update { it.copy(manualModel = value) }

    fun onManualYearChange(value: String) =
        _state.update { it.copy(manualYear = value.filter(Char::isDigit).take(4)) }

    /** Elegir un color de la lista descarta el personalizado, y viceversa. */
    fun onPrimaryColorSelected(colorId: Long?) = _state.update {
        it.copy(primaryColorId = colorId, primaryColorCustom = "", formError = null)
    }

    fun onPrimaryColorCustomChange(value: String) = _state.update {
        it.copy(primaryColorCustom = value, primaryColorId = null, formError = null)
    }

    // ── Comunes ──────────────────────────────────────────────────────────────

    fun onFrameSizeSelected(sizeCode: String?) = _state.update { it.copy(frameSize = sizeCode) }

    fun onSerialNumberChange(value: String) = _state.update { it.copy(serialNumber = value) }

    fun onNotesChange(value: String) = _state.update { it.copy(notes = value) }

    // ── Fotos ────────────────────────────────────────────────────────────────

    /**
     * Agrega las fotos elegidas.
     *
     * La primera de la primera tanda queda como principal: es la que se muestra
     * en el listado y en la denuncia, y obligar a elegirla antes de tener ninguna
     * foto cargada sería un paso de más.
     */
    fun onPhotosPicked(uris: List<String>) {
        if (uris.isEmpty()) return
        _state.update { current ->
            val yaHabia = current.photos.isNotEmpty()
            val nuevas = uris.mapIndexed { index, uri ->
                PendingPhoto(
                    uri = uri,
                    photoType = PhotoType.GENERAL,
                    isPrimary = !yaHabia && index == 0,
                )
            }
            current.copy(photos = current.photos + nuevas)
        }
    }

    fun onPhotoRemoved(uri: String) = _state.update { current ->
        val restantes = current.photos.filterNot { it.uri == uri }
        // Si se sacó la principal, la primera que quede toma su lugar: quedarse
        // sin foto principal deja la bici sin imagen en el listado.
        val hayPrincipal = restantes.any { it.isPrimary }
        current.copy(
            photos = if (hayPrincipal || restantes.isEmpty()) {
                restantes
            } else {
                restantes.mapIndexed { i, p -> p.copy(isPrimary = i == 0) }
            },
        )
    }

    fun onPhotoTypeChanged(uri: String, type: PhotoType) = _state.update { current ->
        current.copy(photos = current.photos.map { if (it.uri == uri) it.copy(photoType = type) else it })
    }

    fun onGpsConsentChanged(granted: Boolean) =
        _state.update { it.copy(gpsAnalysisConsent = granted) }

    // ── Envío ────────────────────────────────────────────────────────────────

    fun submit() {
        val current = _state.value
        if (current.submitting) return

        val errors = validate(current)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors) }
            return
        }

        _state.update { it.copy(submitting = true, formError = null, fieldErrors = emptyMap()) }

        viewModelScope.launch {
            val result = when (current.mode) {
                AddBikeMode.CATALOG -> bicycleRepository.registerFromCatalog(
                    RegisterFromCatalogRequestDto(
                        catalogBikeId = current.catalogBikeId!!,
                        colorwayId = current.colorwayId,
                        frameSize = current.frameSize,
                        serialNumber = current.serialNumber.trim().ifBlank { null },
                        notes = current.notes.trim().ifBlank { null },
                    )
                )

                AddBikeMode.MANUAL -> bicycleRepository.registerManually(
                    RegisterManuallyRequestDto(
                        brandId = current.brandId!!,
                        model = current.manualModel.trim().ifBlank { null },
                        year = current.manualYear.toIntOrNull(),
                        bikeTypeId = current.bikeTypeId,
                        frameSize = current.frameSize,
                        serialNumber = current.serialNumber.trim().ifBlank { null },
                        primaryColorId = current.primaryColorId,
                        primaryColorCustom = current.primaryColorCustom.trim().ifBlank { null },
                        notes = current.notes.trim().ifBlank { null },
                    )
                )
            }

            when (result) {
                is ApiResult.Success -> subirFotos(result.data.id, current)

                else -> _state.update {
                    it.copy(
                        submitting = false,
                        formError = result.toUserMessage("No se pudo registrar la bicicleta."),
                    )
                }
            }
        }
    }

    /**
     * Sube las fotos de una bici ya creada.
     *
     * Corre **después** del alta y no antes: el endpoint de fotos cuelga de
     * `/api/v1/bicycles/{id}/photos`, así que no hay dónde ponerlas hasta que la
     * bici exista. Una falla acá no revierte nada — la bicicleta quedó
     * registrada— y por eso se informa como aviso y no como error.
     */
    private suspend fun subirFotos(bikeId: String, current: AddBikeUiState) {
        if (current.photos.isEmpty()) {
            _state.update { it.copy(createdBikeId = bikeId) }
            return
        }

        _state.update { it.copy(uploadingPhotos = true) }

        val outcome = photoUploader.uploadAll(
            bicycleId = bikeId,
            photos = current.photos,
            gpsAnalysisConsent = current.gpsAnalysisConsent,
        )

        _state.update {
            it.copy(
                uploadingPhotos = false,
                createdBikeId = bikeId,
                photoWarning = if (outcome.failed > 0) {
                    "La bicicleta quedó registrada, pero ${outcome.failed} de " +
                        "${current.photos.size} fotos no se pudieron subir. " +
                        "Podés agregarlas después desde el detalle."
                } else {
                    null
                },
            )
        }
    }

    /**
     * Validación previa al envío, con las reglas del backend.
     *
     * En el alta manual el backend exige `brandId` y acepta color por id **o**
     * personalizado. Que la UI lo corte antes evita un viaje que termina en un
     * error de validación crudo, que es lo que el usuario vería si no.
     */
    private fun validate(state: AddBikeUiState): Map<String, String> {
        val errors = mutableMapOf<String, String>()

        when (state.mode) {
            AddBikeMode.CATALOG -> {
                if (state.brandId == null) errors[FIELD_BRAND] = "Seleccioná una marca"
                if (state.catalogBikeId == null) {
                    errors[FIELD_MODEL] = "Seleccioná un modelo del catálogo"
                }
            }

            AddBikeMode.MANUAL -> {
                if (state.brandId == null) errors[FIELD_BRAND] = "Seleccioná una marca"
                if (state.primaryColorId == null && state.primaryColorCustom.isBlank()) {
                    errors[FIELD_COLOR] =
                        "Elegí un color principal o escribí uno personalizado"
                }
            }
        }

        return errors
    }

    companion object {
        const val FIELD_BRAND = "brand"
        const val FIELD_MODEL = "model"
        const val FIELD_COLOR = "color"
    }
}

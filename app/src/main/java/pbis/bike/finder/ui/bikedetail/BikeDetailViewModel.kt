package pbis.bike.finder.ui.bikedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.BicycleDto
import pbis.bike.finder.data.remote.dto.BicycleStatus
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.CatalogRepository
import pbis.bike.finder.data.repository.photoDownloadUrl
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

/** Una foto ya resuelta a algo que el ImageLoader puede pedir. */
data class BikePhoto(
    val id: String,
    val url: String,
    val isPrimary: Boolean,
    val description: String?,
)

data class BikeDetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val canRetry: Boolean = false,

    val bike: BicycleDto? = null,
    /** Nombre del tipo de bici, resuelto contra el catálogo. */
    val bikeTypeName: String? = null,

    val photos: List<BikePhoto> = emptyList(),
    /**
     * Que las fotos no hayan cargado **no** es un error de la pantalla.
     *
     * media-service es un servicio aparte y en el entorno de desarrollo mínimo
     * ni siquiera está levantado. El resto del detalle —marca, serie, colores—
     * es lo que importa para identificar una bici, y no puede desaparecer
     * porque falló la galería.
     */
    val photosFailed: Boolean = false,

    /** La foto abierta a pantalla completa, o null. */
    val lightbox: BikePhoto? = null,

    /** La baja en curso, para no mandarla dos veces desde el mismo tap. */
    val deregistering: Boolean = false,
    val deregisterError: String? = null,
    /** La baja salió bien: la pantalla se cierra y vuelve al listado. */
    val deregistered: Boolean = false,
) {
    val title: String
        get() = listOfNotNull(bike?.frame?.brandName, bike?.frame?.model)
            .joinToString(" ")
            .ifBlank { "Bicicleta" }

    /**
     * Sólo una bici activa se puede denunciar.
     *
     * Es la misma regla que el backend (`BicycleStatus`): ofrecer el botón sobre
     * una que ya está denunciada lleva al usuario a pagar un plan para una
     * denuncia que el servidor va a rechazar.
     */
    val canReportTheft: Boolean get() = bike?.status == BicycleStatus.ACTIVE

    /**
     * La baja admite más estados que la denuncia.
     *
     * `deactivate()` es la única transición permitida desde cualquier estado,
     * justamente para que a alguien a quien le robaron la bici no le quede el
     * registro colgado — `STOLEN` no acepta ninguna otra edición. Es la misma
     * regla que `puedeDarseDeBaja` aplica sobre el resumen del listado; acá se
     * evalúa sobre el detalle, que trae el estado ya tipado.
     */
    val canDeregister: Boolean
        get() = bike?.status == BicycleStatus.ACTIVE || bike?.status == BicycleStatus.STOLEN

    /** El nombre que se le muestra al usuario en la confirmación. */
    val bikeName: String get() = title
}

/**
 * El detalle de una bici — el modal de `ver-bici.html`, acá pantalla completa.
 *
 * En la web el detalle vive dentro de la grilla; en la app la grilla ya es
 * `BikesScreen`, así que esto es sólo lo que el modal mostraba, más las dos
 * acciones que ofrecía: editar componentes y denunciar el robo.
 */
@HiltViewModel
class BikeDetailViewModel @Inject constructor(
    private val bicycleRepository: BicycleRepository,
    private val catalogRepository: CatalogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BikeDetailUiState())
    val state: StateFlow<BikeDetailUiState> = _state.asStateFlow()

    private var bicycleId: String? = null

    fun start(bicycleId: String) {
        if (this.bicycleId != null) return
        this.bicycleId = bicycleId
        load()
    }

    fun load() {
        val id = bicycleId ?: return
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            when (val result = bicycleRepository.bicycle(id)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(loading = false, error = null, bike = result.data) }
                    loadPhotos(id)
                    resolveBikeType(result.data.bikeTypeId)
                }

                else -> _state.update {
                    it.copy(
                        loading = false,
                        error = result.toUserMessage("No se pudo cargar la bicicleta."),
                        canRetry = result.isSafeToRetry(),
                    )
                }
            }
        }
    }

    /**
     * Las fotos van aparte y su falla es local a la galería.
     *
     * El `downloadUrl` que devuelve media-service es la clave del archivo, no una
     * URL navegable: la traducción a algo pedible la hace [photoDownloadUrl].
     */
    private fun loadPhotos(id: String) {
        viewModelScope.launch {
            when (val result = bicycleRepository.photos(id)) {
                is ApiResult.Success -> {
                    val photos = result.data.mapNotNull { photo ->
                        val key = photo.downloadUrl ?: return@mapNotNull null
                        BikePhoto(
                            id = photo.id,
                            url = photoDownloadUrl(key),
                            isPrimary = photo.isPrimary,
                            description = photo.description ?: photo.photoType?.displayName,
                        )
                    }
                    // La principal primero: es la que identifica a la bici y la
                    // que se usa en la denuncia.
                    _state.update {
                        it.copy(
                            photos = photos.sortedByDescending { p -> p.isPrimary },
                            photosFailed = false,
                        )
                    }
                }

                else -> _state.update { it.copy(photosFailed = true) }
            }
        }
    }

    /** El nombre del tipo no viene en el detalle; hay que resolverlo por id. */
    private fun resolveBikeType(bikeTypeId: Long?) {
        if (bikeTypeId == null) return

        viewModelScope.launch {
            val result = catalogRepository.formData()
            if (result !is ApiResult.Success) return@launch

            val name = result.data.bikeTypes.firstOrNull { it.id == bikeTypeId }?.name
            _state.update { it.copy(bikeTypeName = name) }
        }
    }

    /**
     * Da de baja la bici y avisa que la pantalla ya no tiene sentido.
     *
     * No recarga el detalle al terminar: lo que se estaba mirando dejó de estar
     * en el registro del usuario. La pantalla se cierra y el listado, que
     * recarga en cada `onResume`, muestra el estado nuevo.
     *
     * Este botón existe además del gesto de deslizar del listado, y no en su
     * lugar. Un deslizamiento es invisible hasta que alguien lo descubre y no
     * existe para un lector de pantalla: dejar la única forma de dar de baja
     * detrás de un gesto la vuelve inalcanzable para parte de los usuarios.
     */
    fun deregister() {
        val id = bicycleId ?: return
        if (_state.value.deregistering) return

        _state.update { it.copy(deregistering = true, deregisterError = null) }

        viewModelScope.launch {
            when (val result = bicycleRepository.deregister(id)) {
                is ApiResult.Success -> _state.update {
                    it.copy(deregistering = false, deregistered = true)
                }

                else -> _state.update {
                    it.copy(
                        deregistering = false,
                        deregisterError = result.toUserMessage(
                            "No se pudo dar de baja la bicicleta.",
                        ),
                    )
                }
            }
        }
    }

    fun dismissDeregisterError() = _state.update { it.copy(deregisterError = null) }

    fun openLightbox(photo: BikePhoto) = _state.update { it.copy(lightbox = photo) }

    fun closeLightbox() = _state.update { it.copy(lightbox = null) }
}

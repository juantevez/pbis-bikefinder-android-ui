package pbis.bike.finder.ui.updatecomponents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import pbis.bike.finder.data.remote.ApiResult
import pbis.bike.finder.data.remote.dto.PhotoType
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.CatalogRepository
import pbis.bike.finder.data.repository.PendingPhoto
import pbis.bike.finder.data.repository.PhotoUploadResult
import pbis.bike.finder.data.repository.PhotoUploader
import pbis.bike.finder.data.repository.photoDownloadUrl
import pbis.bike.finder.ui.addbike.AddBikeViewModel
import pbis.bike.finder.ui.common.isSafeToRetry
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

data class UpdateComponentsUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val canRetryLoad: Boolean = false,

    val bikeName: String? = null,
    /** Tipo y año, la línea de abajo del encabezado. */
    val bikeSubtitle: String? = null,

    val entries: Map<String, ComponentEntry> = emptyMap(),
    /** Qué secciones están desplegadas. */
    val expanded: Set<String> = emptySet(),
    /** Las que ya venían cargadas: se marcan para distinguirlas de las vacías. */
    val prefilled: Set<String> = emptySet(),

    val saving: Boolean = false,
    val saveError: String? = null,
    val canRetrySave: Boolean = false,
    /** Se levanta una sola vez, cuando el PATCH salió bien. */
    val saved: Boolean = false,

    // ── Fotos ────────────────────────────────────────────────────────────────
    // Viven en el mismo estado pero no en el mismo formulario: agregar y
    // eliminar impactan al toque y no esperan a "Guardar".

    val photosLoading: Boolean = true,
    val photos: List<BikePhotoItem> = emptyList(),
    val photosError: String? = null,
    /** Subiendo o borrando: bloquea las dos acciones, no el formulario. */
    val photosBusy: Boolean = false,
    val photoType: PhotoType = PhotoType.GENERAL,
    val gpsConsent: Boolean = false,
    /** Resultado de la última operación sobre fotos, para el snackbar. */
    val photoMessage: String? = null,
    /** La foto que el usuario pidió borrar y todavía no confirmó. */
    val confirmingDelete: BikePhotoItem? = null,
) {
    fun entry(key: String): ComponentEntry = entries[key] ?: ComponentEntry()

    val photosLleno: Boolean get() = photos.size >= MAX_FOTOS
}

/**
 * Una foto ya cargada, resuelta a algo que el ImageLoader puede pedir.
 *
 * Sólo la miniatura: la grilla de esta pantalla no agranda ninguna foto —para
 * mirarlas está el detalle de la bici—, así que bajar el original sería traer
 * veinte veces más bytes para pintar lo mismo.
 */
data class BikePhotoItem(
    val id: String,
    val miniaturaUrl: String,
    val isPrimary: Boolean,
    val fileName: String?,
)

/**
 * Techo de fotos por bici.
 *
 * Es el mismo número del alta, y se toma de ahí en vez de copiarlo: si los dos
 * no coinciden, una bici puede nacer por encima del tope que esta pantalla hace
 * cumplir y el usuario ve "llegaste al máximo" sin haber agregado nada.
 */
internal val MAX_FOTOS = AddBikeViewModel.MAX_FOTOS

/**
 * Actualizar los componentes de una bici. Equivale a `actualizar-componentes.html`.
 *
 * Lo que hace distinta a esta pantalla del resto de los formularios es que
 * **manda el mapa completo, no un delta**: el PATCH reemplaza `components`
 * entero. Por eso el estado anterior se guarda tal como llegó y el payload se
 * arma contra él —ver [buildComponentsPayload]—; sin ese original a mano,
 * guardar borraría todo lo que la pantalla no muestra.
 */
@HiltViewModel
class UpdateComponentsViewModel @Inject constructor(
    private val bicycleRepository: BicycleRepository,
    private val catalogRepository: CatalogRepository,
    private val photoUploader: PhotoUploader,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateComponentsUiState())
    val state: StateFlow<UpdateComponentsUiState> = _state.asStateFlow()

    private var bicycleId: String? = null

    /**
     * El mapa de componentes como lo devolvió el backend.
     *
     * Es la base del diff y la única copia de las claves que el formulario no
     * edita. No vive en el `UiState` porque no se dibuja: es estado de trabajo.
     */
    private var originalComponents: JsonObject? = null

    /** Igual que el resto de las pantallas: el id entra una sola vez. */
    fun start(bicycleId: String) {
        if (this.bicycleId != null) return
        this.bicycleId = bicycleId
        load()
    }

    fun load() {
        val id = bicycleId ?: return
        _state.update { it.copy(loading = true, loadError = null) }

        // Las fotos se piden aparte y sin esperarlas: son varias descargas y no
        // tienen por qué demorar el formulario, que es a lo que el usuario vino.
        loadPhotos()

        viewModelScope.launch {
            when (val result = bicycleRepository.bicycle(id)) {
                is ApiResult.Success -> {
                    val bike = result.data
                    originalComponents = bike.components

                    val entries = bike.components.toComponentEntries()
                    val frame = bike.frame
                    val name = listOfNotNull(frame?.brandName, frame?.model)
                        .joinToString(" ")
                        .ifBlank { null }

                    _state.update {
                        it.copy(
                            loading = false,
                            loadError = null,
                            bikeName = name ?: "Bicicleta sin marca",
                            bikeSubtitle = frame?.year?.toString(),
                            entries = entries,
                            // Las cargadas arrancan abiertas, como en la web: son
                            // las que el usuario probablemente viene a corregir, y
                            // dejarlas plegadas esconde que ya había datos.
                            expanded = entries.keys,
                            prefilled = entries.keys,
                        )
                    }

                    resolveBikeType(bike.bikeTypeId, frame?.year)
                }

                else -> _state.update {
                    it.copy(
                        loading = false,
                        loadError = result.toUserMessage("No se pudieron cargar los componentes."),
                        canRetryLoad = result.isSafeToRetry(),
                    )
                }
            }
        }
    }

    /**
     * Completa el subtítulo con el nombre del tipo de bici.
     *
     * El front web lee `frame.bikeTypeName`, un campo que `FrameInfoResponse`
     * **no tiene**, así que en la web el tipo sale siempre vacío y nadie lo notó
     * porque al lado va el año. Acá se resuelve como corresponde: por
     * `bikeTypeId` contra el catálogo, que además ya está cacheado en memoria.
     *
     * Es decorativo, así que un catálogo caído no se reporta como error ni
     * bloquea nada: el subtítulo se queda con el año, que es lo que la web
     * muestra hoy.
     */
    private fun resolveBikeType(bikeTypeId: Long?, year: Int?) {
        if (bikeTypeId == null) return

        viewModelScope.launch {
            val result = catalogRepository.formData()
            if (result !is ApiResult.Success) return@launch

            val typeName = result.data.bikeTypes.firstOrNull { it.id == bikeTypeId }?.name ?: return@launch
            val subtitle = listOfNotNull(typeName, year?.toString()).joinToString(" · ")
            _state.update { it.copy(bikeSubtitle = subtitle.ifBlank { null }) }
        }
    }

    fun toggleSection(key: String) = _state.update {
        it.copy(expanded = if (key in it.expanded) it.expanded - key else it.expanded + key)
    }

    fun onBrandChange(key: String, value: String) = updateEntry(key) { it.copy(brand = value) }

    fun onModelChange(key: String, value: String) = updateEntry(key) { it.copy(model = value) }

    fun onNotesChange(key: String, value: String) = updateEntry(key) { it.copy(notes = value) }

    private fun updateEntry(key: String, transform: (ComponentEntry) -> ComponentEntry) =
        _state.update { state ->
            val updated = transform(state.entry(key))
            state.copy(
                entries = state.entries + (key to updated),
                // Un error de guardado deja de tener sentido en cuanto el
                // formulario cambia: lo que falló ya no es lo que hay en pantalla.
                saveError = null,
            )
        }

    fun save() {
        val id = bicycleId ?: return
        val current = _state.value
        if (current.saving || current.loading) return

        _state.update { it.copy(saving = true, saveError = null) }

        viewModelScope.launch {
            val payload = buildComponentsPayload(
                original = originalComponents,
                edited = current.entries,
                now = Clock.System.now(),
            )

            when (val result = bicycleRepository.updateComponents(id, payload)) {
                is ApiResult.Success -> {
                    // El servidor aceptó el mapa: ahora ese es el estado anterior.
                    // Sin esto, un segundo guardado en la misma pantalla volvería
                    // a comparar contra lo que había al abrirla y marcaría como
                    // "modificado" algo que ya estaba guardado así.
                    originalComponents = payload
                    _state.update { it.copy(saving = false, saved = true) }
                }

                else -> _state.update {
                    it.copy(
                        saving = false,
                        saveError = result.toUserMessage("No se pudieron guardar los componentes."),
                        canRetrySave = result.isSafeToRetry(),
                    )
                }
            }
        }
    }

    /** La pantalla avisa que ya navegó, para no volver a hacerlo en cada recomposición. */
    fun onSavedHandled() = _state.update { it.copy(saved = false) }

    // ── Fotos ────────────────────────────────────────────────────────────────
    //
    // Toda esta mitad está **fuera del formulario**: agregar y eliminar
    // impactan al toque. Si colgaran de "Guardar", el usuario esperaría que las
    // fotos se guarden junto con los componentes —y que "Cancelar" las
    // deshaga, que es justamente lo que un DELETE ya hecho no puede—.

    fun loadPhotos() {
        val id = bicycleId ?: return
        _state.update { it.copy(photosLoading = true, photosError = null) }

        viewModelScope.launch {
            when (val result = bicycleRepository.photos(id)) {
                is ApiResult.Success -> _state.update { current ->
                    current.copy(
                        photosLoading = false,
                        photosError = null,
                        photos = result.data.mapNotNull { photo ->
                            // Sin clave no hay nada que pedirle a media-service, y
                            // una tarjeta rota no le sirve a nadie.
                            val key = photo.thumbnailUrl ?: photo.downloadUrl ?: return@mapNotNull null
                            BikePhotoItem(
                                id = photo.id,
                                miniaturaUrl = photoDownloadUrl(key),
                                isPrimary = photo.isPrimary,
                                fileName = photo.fileName,
                            )
                        },
                    )
                }

                else -> _state.update {
                    it.copy(
                        photosLoading = false,
                        photosError = result.toUserMessage("No se pudieron cargar las fotos."),
                    )
                }
            }
        }
    }

    fun onPhotoTypeChanged(type: PhotoType) = _state.update { it.copy(photoType = type) }

    fun onGpsConsentChanged(granted: Boolean) = _state.update { it.copy(gpsConsent = granted) }

    fun onPhotoMessageShown() = _state.update { it.copy(photoMessage = null) }

    /**
     * Sube las fotos elegidas.
     *
     * La lista se corta **antes** de subir nada: subir y después avisar que
     * algunas no entraron deja al usuario sin saber cuáles quedaron.
     *
     * De a una y en serie, a diferencia del alta, que manda tres en paralelo:
     * ahí la pantalla ya terminó y el usuario está esperando el resultado del
     * registro; acá está mirando la grilla, son archivos de varios MB desde un
     * teléfono, y en una conexión mala el paralelo las hace caer todas juntas.
     */
    fun onPhotosPicked(uris: List<String>) {
        val id = bicycleId ?: return
        if (uris.isEmpty()) return

        val current = _state.value
        if (current.photosBusy) return

        val lugar = MAX_FOTOS - current.photos.size
        if (lugar <= 0) {
            _state.update {
                it.copy(
                    photoMessage = "Ya tenés $MAX_FOTOS fotos. Eliminá una para agregar otra.",
                )
            }
            return
        }

        val entran = uris.take(lugar)
        val sobran = uris.size - entran.size

        _state.update { it.copy(photosBusy = true) }

        viewModelScope.launch {
            var subidas = 0
            var rechazadas = 0
            var inciertas = 0

            for (uri in entran) {
                val resultado = photoUploader.uploadOne(
                    bicycleId = id,
                    photo = PendingPhoto(
                        uri = uri,
                        photoType = current.photoType,
                        // Nunca se manda como principal: la bici ya tiene fotos y
                        // cuál es la principal no se decide agregando otra.
                        isPrimary = false,
                    ),
                    gpsAnalysisConsent = current.gpsConsent,
                )
                when (resultado) {
                    PhotoUploadResult.OK -> subidas++
                    PhotoUploadResult.RECHAZADA -> rechazadas++
                    PhotoUploadResult.INCIERTA -> inciertas++
                }
            }

            _state.update {
                it.copy(
                    photosBusy = false,
                    photoMessage = uploadMessage(subidas, rechazadas, inciertas, sobran),
                )
            }

            // La lista la manda el backend, no la aritmética del cliente: es lo
            // único que dice qué quedó realmente cuando una subida se cortó.
            loadPhotos()
        }
    }

    fun confirmDelete(photo: BikePhotoItem) = _state.update { it.copy(confirmingDelete = photo) }

    fun dismissDelete() = _state.update { it.copy(confirmingDelete = null) }

    /** Borrado permanente: el backend no tiene papelera. Lo confirma la pantalla. */
    fun deleteConfirmedPhoto() {
        val photo = _state.value.confirmingDelete ?: return
        if (_state.value.photosBusy) return

        _state.update { it.copy(photosBusy = true, confirmingDelete = null) }

        viewModelScope.launch {
            val result = bicycleRepository.deletePhoto(photo.id)
            _state.update {
                it.copy(
                    photosBusy = false,
                    photoMessage = if (result is ApiResult.Success) {
                        "Foto eliminada"
                    } else {
                        result.toUserMessage("No se pudo eliminar la foto.")
                    },
                )
            }
            // Se repide igual cuando falló: un DELETE que se cortó pudo haber
            // borrado la foto y perdido la respuesta, y la grilla es lo que
            // decide si hace falta reintentar.
            loadPhotos()
        }
    }
}

/**
 * El aviso de una tanda de subidas.
 *
 * Está afuera del ViewModel para poder probarlo sin armar uno: es el único lugar
 * donde se decide qué se le dice al usuario sobre una foto que puede o no haber
 * quedado guardada.
 */
internal fun uploadMessage(
    subidas: Int,
    rechazadas: Int,
    inciertas: Int,
    sobran: Int,
): String? {
    val partes = buildList {
        if (subidas > 0) add("$subidas foto(s) agregada(s).")
        if (rechazadas > 0) add("$rechazadas no se pudo(ieron) subir.")
        // Una subida cortada NO es una subida fallida: el servidor puede haberla
        // guardado y haberse perdido sólo la respuesta. La grilla ya se refrescó,
        // así que alcanza con mandar a mirarla — decir "no se pudo" invita a
        // reintentar y a terminar con la foto repetida.
        if (inciertas > 0) {
            add(
                "$inciertas se cortó(aron) a mitad de camino: fijate en la lista si " +
                    "quedó(aron) igual antes de reintentar.",
            )
        }
        if (sobran > 0) add("$sobran no entró(aron): el máximo es $MAX_FOTOS.")
    }
    return partes.joinToString(" ").ifBlank { null }
}

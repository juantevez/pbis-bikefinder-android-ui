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
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.CatalogRepository
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
) {
    fun entry(key: String): ComponentEntry = entries[key] ?: ComponentEntry()
}

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
}

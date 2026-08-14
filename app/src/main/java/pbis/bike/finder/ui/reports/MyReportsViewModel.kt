package pbis.bike.finder.ui.reports

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
import pbis.bike.finder.data.remote.dto.ReportStatus
import pbis.bike.finder.data.repository.BicycleRepository
import pbis.bike.finder.data.repository.TheftRepository
import pbis.bike.finder.ui.common.toUserMessage
import javax.inject.Inject

/** Las dos pestañas. Misma lista de denuncias; cambia qué se ofrece hacer con ellas. */
enum class ReportsTab(val label: String) {
    REPORTS("Mis reportes"),
    TIPS("Pistas"),
}

/** Una denuncia lista para mostrar: ya cruzada con la bici a la que pertenece. */
data class ReportRow(
    val id: String,
    val bikeLabel: String,
    val theftDate: LocalDate?,
    val status: ReportStatus?,
    val unreadTips: Int = 0,
)

data class MyReportsUiState(
    val tab: ReportsTab = ReportsTab.REPORTS,
    val reports: List<ReportRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,

    /** Qué denuncia está generando un PDF, para deshabilitar sólo ese botón. */
    val generatingFor: String? = null,
    val pdfError: String? = null,
    /** URL prefirmada lista para abrir; la pantalla la consume y la limpia. */
    val pdfUrl: String? = null,
) {
    val isEmpty: Boolean get() = !loading && error == null && reports.isEmpty()
}

/**
 * Las denuncias ya hechas: sus dos PDFs y sus pistas.
 *
 * Existe porque el PDF era inalcanzable. El botón vivía sólo en la pantalla de
 * éxito de la denuncia, que sale del back stack al terminar: quien la cerraba
 * perdía el acceso al documento que va a la policía. Y el PDF **público** no lo
 * generaba nadie: el endpoint estaba en la capa de red y ninguna pantalla lo
 * llamaba.
 *
 * El reparto en dos pestañas es el del front web, donde las dos listan las
 * mismas denuncias y sólo cambian los botones. Se mantiene así a propósito: una
 * lista de pistas sueltas, sin la bici a la que corresponden, no se entiende en
 * una pantalla de teléfono.
 */
@HiltViewModel
class MyReportsViewModel @Inject constructor(
    private val theftRepository: TheftRepository,
    private val bicycleRepository: BicycleRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(MyReportsUiState())
    val state: StateFlow<MyReportsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun selectTab(tab: ReportsTab) = _state.update { it.copy(tab = tab) }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }

        viewModelScope.launch {
            val reports = when (val result = theftRepository.myReports()) {
                is ApiResult.Success -> result.data
                else -> {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = result.toUserMessage("No se pudieron cargar tus denuncias."),
                        )
                    }
                    return@launch
                }
            }

            // La denuncia trae `bicycleId` pero no marca ni modelo: sin esto la
            // lista serían UUIDs. El listado de bicis ya está cacheado del
            // dashboard, así que cruzarlo no cuesta una request por fila.
            val bikes = (bicycleRepository.myBicycles() as? ApiResult.Success)?.data.orEmpty()
                .associateBy { it.id }

            // Los badges son best-effort: que falle el contador no puede dejar
            // sin PDF a alguien que vino a buscar justamente eso.
            val unread = (theftRepository.unreadTipCounts() as? ApiResult.Success)?.data.orEmpty()

            _state.update {
                it.copy(
                    loading = false,
                    reports = reports.map { report ->
                        val bike = report.bicycleId?.let(bikes::get)
                        ReportRow(
                            id = report.id,
                            bikeLabel = listOfNotNull(bike?.brandName, bike?.model)
                                .joinToString(" ")
                                .ifBlank { "Bicicleta" },
                            theftDate = report.theftDate,
                            status = report.status,
                            unreadTips = unread[report.id] ?: 0,
                        )
                    },
                )
            }
        }
    }

    /**
     * Pide la URL prefirmada del PDF y la deja lista para abrir.
     *
     * [publicVersion] elige entre los dos documentos: el privado —con calle,
     * hora, descripción y contacto— y el cartel público, que omite todo eso.
     */
    fun downloadPdf(reportId: String, publicVersion: Boolean) {
        if (_state.value.generatingFor != null) return
        _state.update { it.copy(generatingFor = reportId, pdfError = null) }

        viewModelScope.launch {
            val result = if (publicVersion) {
                theftRepository.generatePublicPdf(reportId)
            } else {
                theftRepository.generatePdf(reportId)
            }

            _state.update {
                when (result) {
                    is ApiResult.Success -> it.copy(
                        generatingFor = null,
                        pdfUrl = result.data.presignedUrl,
                    )

                    else -> it.copy(
                        generatingFor = null,
                        // Que falle el PDF no toca la denuncia, y el texto lo
                        // dice: confundir "no salió el documento" con "no hay
                        // denuncia" es el peor malentendido posible acá.
                        pdfError = result.toUserMessage(
                            "No se pudo generar el PDF. La denuncia sigue registrada.",
                        ),
                    )
                }
            }
        }
    }

    fun onPdfOpened() = _state.update { it.copy(pdfUrl = null) }
}

package pbis.bike.finder.ui.common

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import pbis.bike.finder.data.remote.dto.BackendTimeZone

private val MESES = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)

/** "3 de marzo de 1990". Es el formato del front web (`toLocaleDateString('es-AR')`). */
fun formatLongDate(date: LocalDate): String =
    "${date.dayOfMonth} de ${MESES[date.monthNumber - 1]} de ${date.year}"

/**
 * Lo mismo para un instante.
 *
 * Se pasa por [BackendTimeZone] y no por la del teléfono: las fechas que muestra
 * esta app son las que el backend considera del día —cuándo se registró una
 * bici, cuándo entró una denuncia—, y con un teléfono en otro huso la misma
 * operación se vería un día corrida respecto de lo que dice el servidor.
 */
fun formatLongDate(instant: Instant): String =
    formatLongDate(instant.toLocalDateTime(BackendTimeZone).date)

package pbis.bike.finder.ui.subscription

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pbis.bike.finder.data.remote.dto.SearchPlan

/**
 * Plan de búsqueda: el paso pago que va antes de la denuncia.
 *
 * Portada de `suscripcion.html`. La grilla de tres columnas de la web se vuelve
 * una columna —en un teléfono, tres tarjetas al lado no entran— y el modal de
 * pago pasa a ser un diálogo.
 *
 * Lo que **no** se porta es el `hover` que levanta la tarjeta: en una pantalla
 * táctil no hay estado intermedio entre no tocar y tocar, así que el destaque es
 * el del plan recomendado y nada más.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionScreen(
    bikeId: String,
    onPaid: (SearchPlan) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SubscriptionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(bikeId) { viewModel.start(bikeId) }

    // El pago habilita la denuncia una sola vez; navegar es un efecto del
    // estado, no algo que decida el botón.
    LaunchedEffect(state.paidPlan) {
        state.paidPlan?.let(onPaid)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Plan de búsqueda") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()

            SectionDivider("Planes disponibles")

            SearchPlan.entries.forEach { plan ->
                PlanCard(
                    plan = plan,
                    featured = plan == SearchPlan.SABUESO,
                    selected = plan == state.selectedPlan,
                    onSelect = { viewModel.selectPlan(plan) },
                )
            }

            Text(
                text = "El pago habilita la denuncia de robo y el plan de rastreo. " +
                    "Podés cancelar la búsqueda en cualquier momento desde tu perfil.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }

    if (state.paying) {
        PaymentDialog(state = state, viewModel = viewModel)
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "ANTES DE DENUNCIAR",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.5.sp,
        )
        Text(
            text = "Activá un plan de búsqueda para tu bicicleta.",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Al denunciar el robo, la red planifica rastreos periódicos comparando " +
                "avistamientos y coincidencias de imagen. Elegí la intensidad de búsqueda " +
                "que mejor se adapte a tu caso.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** El `.section-divider` de la web: etiqueta chica y una línea que ocupa el resto. */
@Composable
private fun SectionDivider(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 3.sp,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PlanCard(
    plan: SearchPlan,
    featured: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val border = when {
        selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        featured -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (featured) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = border,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text(
                        text = plan.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = plan.tagline.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (featured) {
                    Text(
                        text = "MÁS ELEGIDO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(20.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 18.dp),
            ) {
                Text(
                    text = plan.priceUsd,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "USD",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                )
            }
            Text(
                text = "Pago único · cobertura de ${plan.months} meses",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 20.dp),
            ) {
                plan.features.forEach { Feature(it) }
            }

            Button(
                onClick = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                Text("Elegir ${plan.displayName}")
            }
        }
    }
}

@Composable
private fun Feature(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        // El check de la web es un SVG; acá alcanza un punto, que no depende de
        // cargar un icon pack para una viñeta.
        Box(
            modifier = Modifier
                .padding(top = 6.dp, end = 10.dp)
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** El `#pay-modal` de la web. */
@Composable
private fun PaymentDialog(
    state: SubscriptionUiState,
    viewModel: SubscriptionViewModel,
) {
    val plan = state.selectedPlan ?: return

    AlertDialog(
        onDismissRequest = { if (!state.submitting) viewModel.dismissPayment() },
        title = { Text("Plan ${plan.displayName}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${plan.frequency} · ${plan.months} meses",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${plan.priceUsd} USD",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = state.cardName,
                    onValueChange = viewModel::setCardName,
                    label = { Text("Nombre en la tarjeta") },
                    isError = state.cardErrors.containsKey("nombre"),
                    supportingText = { state.cardErrors["nombre"]?.let { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = state.cardNumber,
                    onValueChange = viewModel::setCardNumber,
                    label = { Text("Número de tarjeta") },
                    placeholder = { Text("0000 0000 0000 0000") },
                    isError = state.cardErrors.containsKey("numero"),
                    supportingText = { state.cardErrors["numero"]?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.cardExpiry,
                        onValueChange = viewModel::setCardExpiry,
                        label = { Text("Vencimiento") },
                        placeholder = { Text("MM/AA") },
                        isError = state.cardErrors.containsKey("vencimiento"),
                        supportingText = { state.cardErrors["vencimiento"]?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.cardCvc,
                        onValueChange = viewModel::setCardCvc,
                        label = { Text("CVC") },
                        isError = state.cardErrors.containsKey("cvc"),
                        supportingText = { state.cardErrors["cvc"]?.let { Text(it) } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                state.paymentError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.uncertain) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }

                Text(
                    text = "El número de tarjeta no sale del teléfono: se envía un token.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        },
        confirmButton = {
            Button(onClick = viewModel::pay, enabled = !state.submitting) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    // Tras un final incierto el botón lo dice: si no, el usuario
                    // cree que apretar de nuevo le cobra dos veces y abandona.
                    Text(if (state.uncertain) "Reintentar sin cobrar de nuevo" else "Pagar y denunciar")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissPayment, enabled = !state.submitting) {
                Text("Cancelar")
            }
        },
    )
}

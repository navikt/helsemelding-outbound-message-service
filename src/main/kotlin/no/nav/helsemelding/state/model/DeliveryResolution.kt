package no.nav.helsemelding.state.model

data class DeliveryResolution(
    val state: MessageDeliveryState,
    val pendingReason: PendingReason? = null
)

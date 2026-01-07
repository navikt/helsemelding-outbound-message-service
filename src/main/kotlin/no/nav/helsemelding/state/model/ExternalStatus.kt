package no.nav.helsemelding.state.model

data class ExternalStatus(
    val deliveryState: ExternalDeliveryState,
    val appRecStatus: AppRecStatus?
)

package no.nav.emottak.state.model

data class ExternalStatus(
    val deliveryState: ExternalDeliveryState,
    val appRecStatus: AppRecStatus?
)

package no.nav.helsemelding.outbound.model

data class ExternalStatus(
    val deliveryState: ExternalDeliveryState,
    val appRecStatus: AppRecStatus?
)

package no.nav.helsemelding.outbound.model

enum class ExternalDeliveryState {
    ACKNOWLEDGED,
    UNCONFIRMED,
    REJECTED,
    ABANDONED
}

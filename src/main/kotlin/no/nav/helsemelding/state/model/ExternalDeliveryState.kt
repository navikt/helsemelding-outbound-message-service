package no.nav.helsemelding.state.model

import no.nav.helsemelding.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.state.model.ExternalDeliveryState.REJECTED
import no.nav.helsemelding.state.model.ExternalDeliveryState.UNCONFIRMED

enum class ExternalDeliveryState {
    ACKNOWLEDGED,
    UNCONFIRMED,
    REJECTED
}

fun ExternalDeliveryState?.isAcknowledged(): Boolean = this == ACKNOWLEDGED

fun ExternalDeliveryState?.isUnconfirmed(): Boolean = this == UNCONFIRMED

fun ExternalDeliveryState?.isRejected(): Boolean = this == REJECTED

fun ExternalDeliveryState?.isNull(): Boolean = this == null

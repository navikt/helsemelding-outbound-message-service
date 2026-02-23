package no.nav.helsemelding.outbound.model

import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.REJECTED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.UNCONFIRMED

enum class ExternalDeliveryState {
    ACKNOWLEDGED,
    UNCONFIRMED,
    REJECTED
}

fun ExternalDeliveryState?.isAcknowledged(): Boolean = this == ACKNOWLEDGED

fun ExternalDeliveryState?.isUnconfirmed(): Boolean = this == UNCONFIRMED

fun ExternalDeliveryState?.isRejected(): Boolean = this == REJECTED

fun ExternalDeliveryState?.isNull(): Boolean = this == null

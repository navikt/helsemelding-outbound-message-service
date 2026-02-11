package no.nav.helsemelding.state.model

import no.nav.helsemelding.state.model.TransportStatus.ACKNOWLEDGED
import no.nav.helsemelding.state.model.TransportStatus.NEW
import no.nav.helsemelding.state.model.TransportStatus.PENDING
import no.nav.helsemelding.state.model.TransportStatus.REJECTED

enum class TransportStatus {
    NEW,
    PENDING,
    ACKNOWLEDGED,
    REJECTED,
    INVALID
}

fun TransportStatus.isNew(): Boolean = this == NEW

fun TransportStatus.isPending(): Boolean = this == PENDING

fun TransportStatus.isAcknowledged(): Boolean = this == ACKNOWLEDGED

fun TransportStatus.isNotAcknowledged(): Boolean = this != ACKNOWLEDGED

fun TransportStatus.isRejected(): Boolean = this == REJECTED

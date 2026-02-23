package no.nav.helsemelding.outbound.model

import no.nav.helsemelding.outbound.model.TransportStatus.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.TransportStatus.NEW
import no.nav.helsemelding.outbound.model.TransportStatus.PENDING
import no.nav.helsemelding.outbound.model.TransportStatus.REJECTED

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

package no.nav.helsemelding.state.model

import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.INVALID
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED

enum class MessageDeliveryState {
    NEW,
    PENDING,
    COMPLETED,
    REJECTED,
    INVALID
}

fun MessageDeliveryState.isNew(): Boolean = this == NEW

fun MessageDeliveryState.isPending(): Boolean = this == PENDING

fun MessageDeliveryState.isNotCompleted(): Boolean = this != COMPLETED

fun MessageDeliveryState.isRejected(): Boolean = this == REJECTED

fun MessageDeliveryState.isNotRejected(): Boolean = this != REJECTED

fun MessageDeliveryState.isNotInvalid(): Boolean = this != INVALID

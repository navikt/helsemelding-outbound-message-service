package no.nav.emottak.state.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class MessageStateChange(
    val id: Uuid,
    val messageId: Uuid,
    val oldState: MessageDeliveryState?,
    val newState: MessageDeliveryState,
    val changedAt: Instant
)

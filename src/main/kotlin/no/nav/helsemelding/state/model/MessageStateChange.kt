package no.nav.helsemelding.state.model

import kotlin.time.Instant
import kotlin.uuid.Uuid

data class MessageStateChange(
    val id: Uuid,
    val messageId: Uuid,
    val oldDeliveryState: ExternalDeliveryState?,
    val newDeliveryState: ExternalDeliveryState?,
    val oldAppRecStatus: AppRecStatus?,
    val newAppRecStatus: AppRecStatus?,
    val changedAt: Instant
)

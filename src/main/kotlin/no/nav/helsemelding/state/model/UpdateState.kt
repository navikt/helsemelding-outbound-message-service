package no.nav.helsemelding.state.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class UpdateState(
    val messageType: MessageType,
    val externalRefId: Uuid,
    val oldDeliveryState: ExternalDeliveryState?,
    val newDeliveryState: ExternalDeliveryState?,
    val oldAppRecStatus: AppRecStatus?,
    val newAppRecStatus: AppRecStatus?,
    val occurredAt: Instant = Clock.System.now()
)

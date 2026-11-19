package no.nav.helsemelding.outbound.model

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class CreateState(
    val id: Uuid,
    val externalRefId: Uuid,
    val messageType: MessageType,
    val occurredAt: Instant = Clock.System.now(),
    val transport: MessageTransport = MessageTransport.EDI_2_0
)

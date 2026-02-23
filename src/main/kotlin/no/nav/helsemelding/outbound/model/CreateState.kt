package no.nav.helsemelding.outbound.model

import java.net.URL
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class CreateState(
    val id: Uuid,
    val externalRefId: Uuid,
    val messageType: MessageType,
    val externalMessageUrl: URL,
    val occurredAt: Instant = Clock.System.now()
)

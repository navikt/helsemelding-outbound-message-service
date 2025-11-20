package no.nav.emottak.state.model

import java.net.URL
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class CreateState(
    val messageType: MessageType,
    val externalRefId: Uuid,
    val externalMessageUrl: URL,
    val occurredAt: Instant = Clock.System.now()
)

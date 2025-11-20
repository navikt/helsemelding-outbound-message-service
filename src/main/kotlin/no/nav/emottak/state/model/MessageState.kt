package no.nav.emottak.state.model

import java.net.URL
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class MessageState(
    val id: Uuid,
    val messageType: MessageType,
    val externalRefId: Uuid,
    val externalMessageUrl: URL,
    val externalDeliveryState: ExternalDeliveryState?,
    val appRecStatus: AppRecStatus?,
    val lastStateChange: Instant,
    val lastPolledAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant
)

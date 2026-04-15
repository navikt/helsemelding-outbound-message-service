package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class MessageStatusEvent(
    val messageId: Uuid,
    val timestamp: Instant,
    val status: MessageStatus,
    val apprec: AppRecPayload? = null,
    val error: ErrorPayload? = null
)

fun MessageStatusEvent.toJson(): String = Json.encodeToString(MessageStatusEvent.serializer(), this)

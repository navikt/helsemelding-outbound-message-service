package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class MessageErrorEvent(
    val timestamp: Instant,
    val error: ErrorInfo,
    val originalMessage: OriginalMessage
)

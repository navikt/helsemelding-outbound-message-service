package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class OriginalMessage(
    val createdAt: Instant,
    val key: String?,
    val payload: String?
)

package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
data class OriginalMessage(
    val createdAt: Long,
    val key: String?,
    val payload: String?
)

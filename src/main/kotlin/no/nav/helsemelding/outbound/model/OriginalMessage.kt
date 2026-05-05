package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
data class OriginalMessage(
    val key: String?,
    val payload: String?
)

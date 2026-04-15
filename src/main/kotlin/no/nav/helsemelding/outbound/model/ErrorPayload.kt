package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorPayload(
    val code: String,
    val details: String
)

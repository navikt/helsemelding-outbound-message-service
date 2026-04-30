package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
data class ErrorInfo(
    val category: ErrorCategory,
    val code: ErrorCode,
    val message: String
)

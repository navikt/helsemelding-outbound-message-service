package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
data class AppRecErrorMessage(
    val code: String? = null,
    val text: String? = null
)

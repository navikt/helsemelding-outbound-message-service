package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
data class AppRecErrorMessage(
    val code: String? = null,
    val description: String? = null,
    val oid: String? = null
)

package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
data class AppRecPayload(
    val receiverHerId: Int? = null,
    val appRecStatus: String? = null,
    val appRecErrorList: List<AppRecErrorMessage> = emptyList()
)

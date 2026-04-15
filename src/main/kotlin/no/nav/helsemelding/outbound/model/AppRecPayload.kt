package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
data class AppRecPayload(
    val receiverHerId: Int? = null,
    val status: String? = null,
    val errorList: List<AppRecErrorMessage> = emptyList()
)

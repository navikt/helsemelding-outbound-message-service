package no.nav.helsemelding.outbound.model

import kotlinx.serialization.Serializable

@Serializable
data class EdiStatusEvent(
    val status: EdiStatus,
    val receiverHerId: Int? = null,
    val errorList: List<AppRecErrorMessage> = emptyList()
)

@Serializable
enum class EdiStatus {
    ACKNOWLEDGED,
    REJECTED,
    REJECTED_TRANSPORT
}

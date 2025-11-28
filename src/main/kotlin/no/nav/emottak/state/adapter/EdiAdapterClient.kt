package no.nav.emottak.state.adapter

import no.nav.emottak.state.model.AppRecStatus
import no.nav.emottak.state.model.ExternalDeliveryState
import no.nav.emottak.state.model.ExternalStatus
import kotlin.uuid.Uuid

interface EdiAdapterClient {
    fun setStatus(
        referenceId: Uuid,
        transport: ExternalDeliveryState,
        appRecStatus: AppRecStatus? = null
    )

    fun setError(referenceId: Uuid, error: ErrorMessage)

    fun getMessageStatus(id: Uuid): Pair<List<ExternalStatus>?, ErrorMessage?>
}

class FakeEdiAdapterClient : EdiAdapterClient {
    private val states = mutableMapOf<Uuid, List<ExternalStatus>>()
    private val errors = mutableMapOf<Uuid, ErrorMessage?>()

    override fun setStatus(
        referenceId: Uuid,
        transport: ExternalDeliveryState,
        appRecStatus: AppRecStatus?
    ) {
        states[referenceId] = listOf(
            ExternalStatus(
                deliveryState = transport,
                appRecStatus = appRecStatus
            )
        )
        errors[referenceId] = null
    }

    override fun setError(referenceId: Uuid, error: ErrorMessage) {
        states.remove(referenceId)
        errors[referenceId] = error
    }

    override fun getMessageStatus(id: Uuid): Pair<List<ExternalStatus>?, ErrorMessage?> {
        val status = states[id]
        val error = errors[id]

        return status to error
    }
}

// data class StatusInfo(
//     val receiverHerId: Int,
//     val transportDeliveryState: DeliveryState,
//     val appRecStatus: AppRecStatus? = null
// )
//
// enum class DeliveryState(val value: String, val description: String) {
//     UNCONFIRMED("Unconfirmed", "Transport is not confirmed"),
//     ACKNOWLEDGED("Acknowledged", "Transport is confirmed"),
//     REJECTED("Rejected", "Transport was rejected"),
//     UNKNOWN("Unknown", "Unsupported state")
// }
//
// enum class AppRecStatus(val value: String, val description: String) {
//     OK("Ok", "Ok"),
//     REJECTED("Rejected", "Rejected"),
//     OK_ERROR_IN_MESSAGE_PART("OkErrorInMessagePart", "Ok with partial error"),
//     UNKNOWN("Unknown", "Unsupported status")
// }

data class ErrorMessage(
    val message: String,
    val details: String? = null
)

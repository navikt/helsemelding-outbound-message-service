package no.nav.emottak.state

import no.nav.emottak.state.model.AppRecStatus
import no.nav.emottak.state.model.ExternalDeliveryState
import no.nav.emottak.state.model.MessageDeliveryState
import no.nav.emottak.state.model.MessageState

sealed interface Error

sealed interface StateError : Error

sealed interface StateEvaluationError : StateError {
    data class UnresolvableState(
        val deliveryState: ExternalDeliveryState?,
        val appRecStatus: AppRecStatus?
    ) : StateEvaluationError
}

sealed interface StateTransitionError : StateError {
    data class IllegalTransition(
        val from: MessageDeliveryState,
        val to: MessageDeliveryState
    ) : StateTransitionError
}

fun StateError.withMessageContext(message: MessageState): String =
    "Message ${message.externalRefId}: ${formatForLog()}"

private fun StateError.formatForLog(): String = when (this) {
    is StateEvaluationError.UnresolvableState ->
        "UnresolvableState(deliveryState=$deliveryState, appRecStatus=$appRecStatus)"

    is StateTransitionError.IllegalTransition ->
        "IllegalTransition(from=$from, to=$to)"
}

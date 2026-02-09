package no.nav.helsemelding.state

import no.nav.helsemelding.ediadapter.model.ErrorMessage
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.MessageDeliveryState
import no.nav.helsemelding.state.model.MessageState
import kotlin.uuid.Uuid

sealed interface Error

sealed interface StateError : Error

sealed class StateTransitionError : StateError {
    data class IllegalTransition(
        val from: MessageDeliveryState,
        val to: MessageDeliveryState
    ) : StateTransitionError()

    data class IllegalAppRecTransition(
        val from: AppRecStatus?,
        val to: AppRecStatus?
    ) : StateTransitionError()

    data class IllegalCombinedState(
        val message: String
    ) : StateTransitionError()
}

sealed interface EdiAdapterError : StateError {
    data class NoApprecReturned(
        val externalRefId: Uuid
    ) : EdiAdapterError

    data class FetchFailure(
        val externalRefId: Uuid,
        val cause: ErrorMessage
    ) : EdiAdapterError
}

sealed interface PublishError : StateError {
    data class Failure(
        val messageId: Uuid,
        val topic: String,
        val cause: Throwable
    ) : PublishError
}

fun StateError.withMessageContext(message: MessageState): String =
    "Message ${message.externalRefId}: ${formatForLog()}"

private fun StateError.formatForLog(): String = when (this) {
    is StateTransitionError.IllegalTransition ->
        "IllegalTransition(from=$from, to=$to)"

    is StateTransitionError.IllegalAppRecTransition ->
        "IllegalAppRecTransition(from=$from, to=$to)"

    is StateTransitionError.IllegalCombinedState ->
        "IllegalCombinedState(message=$message)"

    is EdiAdapterError.NoApprecReturned ->
        "NoApprecReturned(externalRefId=$externalRefId)"

    is EdiAdapterError.FetchFailure ->
        "FetchFailure(externalRefId=$externalRefId, cause=$cause)"

    is PublishError.Failure ->
        "PublishFailure(messageId=$messageId, topic=$topic, cause=$cause)"
}

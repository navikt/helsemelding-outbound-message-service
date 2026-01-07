package no.nav.helsemelding.state.service

import arrow.core.raise.Raise
import no.nav.helsemelding.state.StateEvaluationError
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.evaluator.StateEvaluator
import no.nav.helsemelding.state.evaluator.StateTransitionValidator
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.MessageDeliveryState
import no.nav.helsemelding.state.model.MessageState

class StateEvaluatorService(
    private val stateEvaluator: StateEvaluator,
    private val transitionValidator: StateTransitionValidator
) {
    fun Raise<StateEvaluationError>.evaluate(
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?
    ): MessageDeliveryState =
        with(stateEvaluator) {
            evaluate(externalDeliveryState, appRecStatus)
        }

    fun Raise<StateEvaluationError>.evaluate(
        message: MessageState
    ): MessageDeliveryState =
        evaluate(message.externalDeliveryState, message.appRecStatus)

    fun Raise<StateTransitionError>.determineNextState(
        oldState: MessageDeliveryState,
        newState: MessageDeliveryState
    ): MessageDeliveryState =
        with(transitionValidator) {
            validate(oldState, newState)
            newState
        }
}

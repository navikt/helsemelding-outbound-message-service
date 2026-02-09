package no.nav.helsemelding.state.service

import arrow.core.raise.Raise
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.evaluator.StateTransitionEvaluator
import no.nav.helsemelding.state.evaluator.TransportStatusTranslator
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.DeliveryEvaluationState
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.MessageDeliveryState
import no.nav.helsemelding.state.model.MessageDeliveryState.UNCHANGED
import no.nav.helsemelding.state.model.MessageState
import no.nav.helsemelding.state.model.resolveDelivery

class StateEvaluatorService(
    private val transportTranslator: TransportStatusTranslator,
    private val transitionValidator: StateTransitionEvaluator
) {
    fun evaluate(message: MessageState): DeliveryEvaluationState =
        evaluate(
            message.externalDeliveryState,
            message.appRecStatus
        )

    fun evaluate(
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?
    ): DeliveryEvaluationState =
        DeliveryEvaluationState(
            transport = with(transportTranslator) {
                translate(externalDeliveryState)
            },
            appRec = appRecStatus
        )

    fun Raise<StateTransitionError>.determineNextState(
        old: DeliveryEvaluationState,
        new: DeliveryEvaluationState
    ): MessageDeliveryState =
        with(transitionValidator) {
            evaluate(old, new)
            val oldResolvedState = old.resolveDelivery().state
            val newResolvedState = new.resolveDelivery().state

            if (oldResolvedState != newResolvedState) newResolvedState else UNCHANGED
        }
}

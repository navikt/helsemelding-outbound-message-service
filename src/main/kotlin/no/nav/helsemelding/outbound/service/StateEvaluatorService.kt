package no.nav.helsemelding.outbound.service

import arrow.core.raise.Raise
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.evaluator.StateTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.TransportStatusTranslator
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.DeliveryEvaluationState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.outbound.model.NextStateDecision
import no.nav.helsemelding.outbound.model.isTerminal
import no.nav.helsemelding.outbound.model.resolveDelivery
import no.nav.helsemelding.outbound.model.toDeliveryState

/**
 * Combines transport translation, transition validation and delivery state resolution.
 *
 * Builds evaluation snapshots from external values, then compares their resolved outcomes
 * to produce a transition decision. Persistence and publication are handled by the caller.
 */
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

    /**
     * Validates the transition before comparing resolved outcomes, including the pending reason.
     * Returns [NextStateDecision.Unchanged] when the outcomes match, or the new state's decision
     * otherwise. Validation failures are raised through [Raise].
     */
    fun Raise<StateTransitionError>.determineNextState(
        old: DeliveryEvaluationState,
        new: DeliveryEvaluationState
    ): NextStateDecision =
        with(transitionValidator) {
            evaluate(old, new)
            val oldResolvedDelivery = old.resolveDelivery()
            val newResolvedDelivery = new.resolveDelivery()

            if (oldResolvedDelivery != newResolvedDelivery) {
                newResolvedDelivery.decision
            } else {
                NextStateDecision.Unchanged
            }
        }

    /**
     * Identifies messages whose stored values resolve to COMPLETED or REJECTED, so their
     * notifications can be skipped. INVALID is excluded from this filter, even though the
     * transition validator only permits INVALID to transition to itself.
     */
    fun isTerminal(message: MessageState): Boolean =
        evaluate(message)
            .resolveDelivery()
            .toDeliveryState()
            .isTerminal()
}

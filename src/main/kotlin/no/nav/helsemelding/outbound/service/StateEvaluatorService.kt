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
    /**
     * Builds an evaluation snapshot from a tracked message's stored external values.
     * Does not validate transitions or modify the message.
     *
     * @param message The tracked message to evaluate.
     * @return The normalized transport status and the stored application receipt status.
     */
    fun evaluate(message: MessageState): DeliveryEvaluationState =
        evaluate(
            message.externalDeliveryState,
            message.appRecStatus
        )

    /**
     * Normalizes external transport status and retains the application receipt status.
     * An absent transport status represents a new message. Validation is performed separately
     * by [determineNextState].
     *
     * @param externalDeliveryState The external transport status, or `null` if none has been received.
     * @param appRecStatus The application receipt status, or `null` while awaiting a receipt.
     * @return A snapshot for transition validation and delivery outcome resolution.
     */
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
     * Raises [StateTransitionError] through [Raise] if the transition is invalid.
     *
     * @param old The evaluation snapshot derived from the previously stored state.
     * @param new The evaluation snapshot derived from the latest external state.
     * @return [NextStateDecision.Unchanged] when the resolved outcomes match, or the new state's
     * decision otherwise.
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
     *
     * @param message The tracked message whose stored state determines the delivery outcome.
     * @return `true` when the resolved outcome is COMPLETED or REJECTED; otherwise `false`.
     */
    fun isTerminal(message: MessageState): Boolean =
        evaluate(message)
            .resolveDelivery()
            .toDeliveryState()
            .isTerminal()
}

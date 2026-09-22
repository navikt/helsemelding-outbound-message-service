package no.nav.helsemelding.outbound.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.model.DeliveryEvaluationState
import no.nav.helsemelding.outbound.model.isNotAcknowledged
import no.nav.helsemelding.outbound.model.isNotNull
import no.nav.helsemelding.outbound.model.resolveDelivery
import no.nav.helsemelding.outbound.model.toDeliveryState

/** Validates transport and application receipt changes before a delivery decision is made. */
class StateTransitionEvaluator(
    private val transportValidator: TransportTransitionEvaluator,
    private val appRecValidator: AppRecTransitionEvaluator
) {
    /**
     * Checks AppRec immutability through [AppRecTransitionEvaluator] and requires acknowledged
     * transport whenever the new snapshot contains an AppRec. Resolves both snapshots to
     * lifecycle states and delegates their transition check to [TransportTransitionEvaluator].
     *
     * Raises [StateTransitionError] through [Raise] if any validation fails.
     * The caller determines whether a valid transition changes the resolved outcome.
     *
     * @param old The evaluation snapshot derived from the previously stored state.
     * @param new The evaluation snapshot derived from the latest external state.
     */
    fun Raise<StateTransitionError>.evaluate(old: DeliveryEvaluationState, new: DeliveryEvaluationState) {
        with(appRecValidator) { evaluate(old.appRec, new.appRec) }

        if (new.transport.isNotAcknowledged() && new.appRec.isNotNull()) {
            raise(
                StateTransitionError.IllegalCombinedState(
                    "AppRec requires transport ACKNOWLEDGED (transport = ${new.transport})"
                )
            )
        }
        val oldResolved = old.resolveDelivery()
        val newResolved = new.resolveDelivery()

        with(transportValidator) { evaluate(oldResolved.toDeliveryState(), newResolved.toDeliveryState()) }
    }
}

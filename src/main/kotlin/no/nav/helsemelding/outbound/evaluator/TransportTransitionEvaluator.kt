package no.nav.helsemelding.outbound.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.model.MessageDeliveryState
import no.nav.helsemelding.outbound.model.isNew
import no.nav.helsemelding.outbound.model.isNotCompleted
import no.nav.helsemelding.outbound.model.isNotInvalid
import no.nav.helsemelding.outbound.model.isNotRejected

/** Validates transitions between resolved [MessageDeliveryState] values. */
class TransportTransitionEvaluator {
    /**
     * Allows NEW to transition to any state and PENDING to transition to any state except NEW.
     * COMPLETED, REJECTED and INVALID may only remain unchanged.
     * These rules allow intermediate delivery stages to go unobserved.
     *
     * Raises [StateTransitionError.IllegalTransition] through [Raise] if the transition is invalid.
     * Persistence and publication are handled by the caller.
     *
     * @param old The resolved delivery state derived from the previously stored values.
     * @param new The resolved delivery state derived from the latest external values.
     */
    fun Raise<StateTransitionError>.evaluate(old: MessageDeliveryState, new: MessageDeliveryState) {
        when (old) {
            MessageDeliveryState.NEW -> Unit

            MessageDeliveryState.PENDING -> {
                if (new.isNew()) {
                    raise(
                        StateTransitionError.IllegalTransition(
                            from = old,
                            to = new
                        )
                    )
                }
            }

            MessageDeliveryState.COMPLETED -> {
                if (new.isNotCompleted()) {
                    raise(
                        StateTransitionError.IllegalTransition(
                            from = old,
                            to = new
                        )
                    )
                }
            }

            MessageDeliveryState.REJECTED -> {
                if (new.isNotRejected()) {
                    raise(
                        StateTransitionError.IllegalTransition(
                            from = old,
                            to = new
                        )
                    )
                }
            }

            MessageDeliveryState.INVALID -> {
                if (new.isNotInvalid()) {
                    raise(
                        StateTransitionError.IllegalTransition(
                            from = old,
                            to = new
                        )
                    )
                }
            }
        }
    }
}

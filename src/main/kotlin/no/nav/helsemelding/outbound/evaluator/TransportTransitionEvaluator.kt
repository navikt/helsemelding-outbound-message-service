package no.nav.helsemelding.outbound.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.model.MessageDeliveryState
import no.nav.helsemelding.outbound.model.isNew
import no.nav.helsemelding.outbound.model.isNotCompleted
import no.nav.helsemelding.outbound.model.isNotInvalid
import no.nav.helsemelding.outbound.model.isNotRejected

/**
 * Validates transitions between resolved [MessageDeliveryState] values.
 *
 * NEW may transition to any state. PENDING may progress or remain pending, but cannot
 * return to NEW. COMPLETED, REJECTED and INVALID may only transition to themselves.
 * These rules allow intermediate delivery stages to go unobserved.
 *
 * Disallowed transitions raise [StateTransitionError.IllegalTransition] through [Raise].
 * The caller decides how to handle the failure, including persistence and publication.
 */
class TransportTransitionEvaluator {
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

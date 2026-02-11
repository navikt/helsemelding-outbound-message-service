package no.nav.helsemelding.state.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.model.MessageDeliveryState
import no.nav.helsemelding.state.model.isNew
import no.nav.helsemelding.state.model.isNotCompleted
import no.nav.helsemelding.state.model.isNotInvalid
import no.nav.helsemelding.state.model.isNotRejected

/**
 * Evaluates whether a transition between two **resolved delivery lifecycle states**
 * is permitted according to the domain rules of the *transport axis*.
 *
 * This evaluator does not compute or derive states. Both `old` and `new` states
 * must already represent the **overall** resolved delivery lifecycle
 * (`MessageDeliveryState`), typically obtained by passing a
 * [DeliveryEvaluationState] through `resolveDelivery()`.
 *
 * Its sole responsibility is to enforce the allowed transitions for the
 * transport-driven portion of the lifecycle and to prevent logically
 * inconsistent or backward state progressions.
 *
 * The evaluator executes inside a [Raise] context, allowing illegal transitions
 * to be raised as [StateTransitionError.IllegalTransition] without throwing
 * exceptions and without introducing side effects.
 *
 * ## Relationship to the Domain Model
 *
 * The delivery lifecycle is now determined by two orthogonal axes:
 *
 * - **TransportStatus**
 *   (NEW → PENDING → ACKED → REJECTED → INVALID)
 *
 * - **AppRecStatus?**
 *   (null → OK | OK_ERROR_IN_MESSAGE_PART | REJECTED)
 *
 * After a [DeliveryEvaluationState] has been resolved into a unified
 * [MessageDeliveryState], the *transport evaluator* enforces the allowed
 * transition rules for that lifecycle.
 *
 * This evaluator is intentionally unaware of `TransportStatus` or
 * `AppRecStatus`; cross-field consistency (e.g., "appRec requires transport ACKED")
 * is handled separately by [StateTransitionEvaluator].
 *
 * ## Internal Control State: UNCHANGED
 *
 * `UNCHANGED` is **not** part of the lifecycle and is treated as a no-op.
 * When `new == UNCHANGED`, evaluation short-circuits immediately because
 * no transition is being attempted. `UNCHANGED` is never considered an illegal
 * transition target and must never be persisted.
 *
 * ## Allowed Transitions
 *
 * - **NEW → ANY**
 *   NEW is the initial resolved state. Any forward transition is permitted.
 *
 * - **PENDING → PENDING | COMPLETED | REJECTED**
 *   A message still in flight may:
 *   - remain pending,
 *   - complete (application receipt OK),
 *   - or be rejected (transport-level or apprec-level).
 *
 *   A regression back to NEW is *not* permitted.
 *
 * - **COMPLETED → COMPLETED**
 *   COMPLETED is terminal. After a successful application receipt, no further
 *   state changes are allowed.
 *
 * - **REJECTED → REJECTED**
 *   REJECTED is terminal. No further transitions are allowed.
 *
 * - **INVALID → INVALID**
 *   INVALID is terminal. It has no outgoing transitions.
 *
 * ## Illegal Transitions
 *
 * Any transition not covered above is illegal and results in a
 * [StateTransitionError.IllegalTransition] being raised through the [Raise] context.
 *
 * This ensures that domain invariants are preserved and that invalid progressions
 * cannot be persisted, published, or propagated.
 *
 * @receiver Raise<StateTransitionError>
 *   The Raise context used to surface illegal transitions without throwing.
 *
 * @param old
 *   The previously persisted **resolved** delivery lifecycle state.
 *
 * @param new
 *   The newly evaluated resolved state, or `UNCHANGED` when no transition
 *   is required.
 *
 * @throws StateTransitionError.IllegalTransition
 *   Raised through [Raise] when a transition from `old` to `new` is not allowed.
 */
class TransportTransitionEvaluator {
    fun Raise<StateTransitionError>.evaluate(old: MessageDeliveryState, new: MessageDeliveryState) {
        if (old == MessageDeliveryState.UNCHANGED) {
            raise(
                StateTransitionError.IllegalCombinedState(
                    "Old state can never be UNCHANGED (persisted control state)"
                )
            )
        }
        if (new == MessageDeliveryState.UNCHANGED) return

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

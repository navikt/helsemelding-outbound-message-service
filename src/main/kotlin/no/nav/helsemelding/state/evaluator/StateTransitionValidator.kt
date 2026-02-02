package no.nav.helsemelding.state.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.model.MessageDeliveryState

/**
 * Validates whether a transition between two internal delivery states is permitted
 * according to the domain rules of the message lifecycle.
 *
 * This validator does not compute or derive states; it assumes that both the
 * `old` and `new` states have already been evaluated (typically by
 * [StateEvaluator]). Its sole responsibility is to enforce the allowed
 * transitions and prevent logically inconsistent state progressions from being
 * persisted or acted upon.
 *
 * The validator operates inside a [Raise] context, allowing illegal transitions
 * to be raised as [StateTransitionError.IllegalTransition] without throwing
 * exceptions and without mixing side effects.
 *
 * ## Internal Control State: UNCHANGED
 *
 * `UNCHANGED` is **not** a domain lifecycle state. It is an internal control
 * signal used by the state machine to indicate that:
 *
 * > the evaluated `new` state is identical to the currently persisted `old` state.
 *
 * Because no transition is being attempted, `UNCHANGED` bypasses all validation
 * rules and is always permitted. It is never persisted or published — it simply
 * short-circuits further processing to avoid unnecessary history writes or
 * notifications.
 *
 * ## Allowed Transitions
 *
 * - **NEW → ANY**
 *   Any transition from NEW is allowed, as NEW represents the initial internal
 *   state before any external delivery information has been received.
 *
 * - **PENDING → PENDING | COMPLETED | REJECTED**
 *   A message that is in flight may:
 *   - remain pending,
 *   - complete successfully,
 *   - or be rejected.
 *
 *   A transition from PENDING back to NEW is **not** permitted.
 *
 * - **COMPLETED → COMPLETED**
 *   COMPLETED is terminal. Once reached, no other transitions are legal.
 *
 * - **REJECTED → REJECTED**
 *   REJECTED is also terminal. No transitions to other states are allowed.
 *
 * ## Illegal Transitions
 *
 * Any transition that violates the allowed rules results in
 * [StateTransitionError.IllegalTransition] being raised through the [Raise]
 * context. This ensures that domain invariants are preserved and that invalid
 * state changes cannot be persisted or published.
 *
 * @receiver Raise<StateTransitionError>
 *   The Raise context used for reporting illegal transitions.
 *
 * @param old
 *   The previously persisted internal delivery state.
 *
 * @param new
 *   The newly evaluated internal delivery state, or `UNCHANGED` when the state
 *   machine determines that no transition is necessary.
 *
 * @throws StateTransitionError.IllegalTransition
 *   Raised through the [Raise] context when a transition from `old` to `new`
 *   violates the domain rules.
 */
class StateTransitionValidator {
    fun Raise<StateTransitionError>.validate(old: MessageDeliveryState, new: MessageDeliveryState) {
        when (old) {
            MessageDeliveryState.NEW -> {}

            MessageDeliveryState.PENDING -> {
                if (new == MessageDeliveryState.NEW) {
                    raise(
                        StateTransitionError.IllegalTransition(
                            from = old,
                            to = new
                        )
                    )
                }
                {}
            }

            MessageDeliveryState.COMPLETED -> {
                if (new != MessageDeliveryState.COMPLETED) {
                    raise(
                        StateTransitionError.IllegalTransition(
                            from = old,
                            to = new
                        )
                    )
                }
                {}
            }

            MessageDeliveryState.REJECTED -> {
                if (new != MessageDeliveryState.REJECTED) {
                    raise(
                        StateTransitionError.IllegalTransition(
                            from = old,
                            to = new
                        )
                    )
                }
                {}
            }

            MessageDeliveryState.INVALID -> {
                raise(
                    StateTransitionError.IllegalTransition(
                        from = old,
                        to = new
                    )
                )
            }

            MessageDeliveryState.UNCHANGED -> {}
        }
    }
}

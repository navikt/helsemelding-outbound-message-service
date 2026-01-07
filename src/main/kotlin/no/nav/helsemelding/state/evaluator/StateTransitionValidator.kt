package no.nav.helsemelding.state.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.model.MessageDeliveryState

/**
 * Validates whether a transition between two internal delivery states is permitted
 * according to the domain rules of the message lifecycle.
 *
 * This function does not compute or derive states; it assumes that both the
 * `old` and `new` states have already been evaluated (typically by
 * {@link StateEvaluator}). Its sole responsibility is to enforce the
 * allowed transitions and prevent the system from persisting logically
 * inconsistent state progressions.
 *
 * The validator operates inside a [Raise] context, allowing illegal
 * transitions to be raised as {@link StateTransitionError.IllegalTransition}
 * without throwing exceptions and without mixing side effects.
 *
 * ## Allowed Transitions
 *
 * - **NEW → ANY**
 *   *Any* transition from NEW is allowed, since no external information
 *   has yet constrained the message state.
 *
 * - **PENDING → PENDING | COMPLETED | REJECTED**
 *   A message that is in-flight may:
 *   - Remain pending,
 *   - Complete successfully,
 *   - Or be rejected.
 *
 *   A transition from PENDING back to NEW is **not** allowed.
 *
 * - **COMPLETED → COMPLETED**
 *   Once a message is completed, its state is final. No further transitions
 *   are valid. Any attempt to move away from COMPLETED is illegal.
 *
 * - **REJECTED → REJECTED**
 *   Rejected messages remain rejected; transitions to other states are not
 *   permitted.
 *
 * ## Illegal Transitions
 *
 * Any transition that violates the above rules results in
 * {@link StateTransitionError.IllegalTransition} being raised.
 *
 * @receiver Raise<StateTransitionError>
 *   The Raise context used for reporting illegal state transitions.
 *
 * @param old
 *   The previously persisted internal delivery state.
 *
 * @param new
 *   The newly computed internal delivery state.
 *
 * @throws StateTransitionError.IllegalTransition
 *   Raised through the [Raise] context if the transition from `old` to `new`
 *   is not allowed.
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
        }
    }
}

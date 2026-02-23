package no.nav.helsemelding.outbound.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.model.MessageDeliveryState
import no.nav.helsemelding.outbound.model.isNew
import no.nav.helsemelding.outbound.model.isNotCompleted
import no.nav.helsemelding.outbound.model.isNotInvalid
import no.nav.helsemelding.outbound.model.isNotRejected

/**
 * Evaluates whether a transition between two **resolved delivery lifecycle states**
 * is permitted according to the domain rules governing the *transport-driven* portion
 * of the lifecycle.
 *
 * This evaluator does **not** compute or derive states. Both `old` and `new` values
 * must already be fully resolved [MessageDeliveryState] instances — typically produced
 * by resolving two [DeliveryEvaluationState] snapshots via `resolveDelivery()`.
 *
 * Its sole responsibility is to validate whether a proposed lifecycle transition is
 * logically consistent with the permitted progression of the transport axis. This
 * includes preventing regressions (e.g., `COMPLETED → PENDING`) and enforcing the
 * terminal nature of certain states.
 *
 * The evaluator runs inside a [Raise] context, allowing illegal transitions to surface
 * as [StateTransitionError.IllegalTransition] values without throwing exceptions and
 * without performing side-effects.
 *
 * ---
 *
 * ## Relationship to the Multi-Axis Domain Model
 *
 * Delivery lifecycle resolution is based on two orthogonal axes:
 *
 * - **TransportStatus**
 *   (NEW → PENDING → ACKNOWLEDGED → REJECTED → INVALID)
 *
 * - **AppRecStatus?**
 *   (null → OK | OK_ERROR_IN_MESSAGE_PART | REJECTED)
 *
 * These axes are combined to produce a unified [MessageDeliveryState], which expresses
 * the final domain-level status of a message.
 *
 * This evaluator operates **after** that resolution step. It does **not** inspect
 * `TransportStatus` or `AppRecStatus` directly — cross-axis consistency and
 * invariants (e.g., *“AppRec requires transport ACKNOWLEDGED”*) are enforced by
 * [StateTransitionEvaluator].
 *
 * ---
 *
 * ## No-Op and Control Flow
 *
 * This evaluator is intentionally unaware of “no transition” control signals such as
 * `NextStateDecision.Unchanged`. The state machine (e.g., `determineNextState`) is
 * responsible for deciding whether a transition should occur.
 *
 * This evaluator receives only **actual** resolved lifecycle states to validate.
 *
 * ---
 *
 * ## Allowed Transitions
 *
 * The following transitions are permitted:
 *
 * - **NEW → ANY**
 *   NEW is the initial lifecycle state and may transition forward freely.
 *
 * - **PENDING → PENDING | COMPLETED | REJECTED | INVALID**
 *   PENDING represents an in-flight delivery and may:
 *   - remain pending,
 *   - complete (application-level acceptance),
 *   - be rejected,
 *   - or be marked invalid.
 *
 *   Regression to NEW is not permitted.
 *
 * - **COMPLETED → COMPLETED**
 *   COMPLETED is terminal. No further transitions are allowed.
 *
 * - **REJECTED → REJECTED**
 *   REJECTED is terminal. No further transitions are allowed.
 *
 * - **INVALID → INVALID**
 *   INVALID is terminal. No further transitions are allowed.
 *
 * ---
 *
 * ## Illegal Transitions
 *
 * Any transition not explicitly permitted above is illegal and results in a
 * [StateTransitionError.IllegalTransition] raised through the [Raise] context.
 *
 * This ensures lifecycle monotonicity and prevents invalid state progressions from
 * being persisted, emitted, or propagated further in the system.
 *
 * ---
 *
 * @receiver Raise<StateTransitionError>
 *   The context used to surface illegal transitions in a typed, non-throwing manner.
 *
 * @param old
 *   The previously persisted, fully resolved [MessageDeliveryState].
 *
 * @param new
 *   The newly resolved [MessageDeliveryState] to validate.
 *
 * @throws StateTransitionError.IllegalTransition
 *   When the transition from `old` to `new` is not allowed.
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

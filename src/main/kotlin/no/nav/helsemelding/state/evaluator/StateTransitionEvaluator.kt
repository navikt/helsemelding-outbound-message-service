package no.nav.helsemelding.state.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.model.DeliveryEvaluationState
import no.nav.helsemelding.state.model.isNotAcknowledged
import no.nav.helsemelding.state.model.isNotNull
import no.nav.helsemelding.state.model.resolveDelivery

/**
 * Evaluates whether a transition between two **multi-axis delivery states**
 * is permitted. This evaluator coordinates:
 *
 *  1. **AppRec transition evaluation** — verifies whether the application-level
 *     receipt (`AppRecStatus?`) can legally change.
 *
 *  2. **Cross-field domain invariants** — enforces rules that depend on both
 *     transport and application axes (e.g., *an AppRec can only exist after
 *     transport has been ACKNOWLEDGED*).
 *
 *  3. **Transport-driven lifecycle transition evaluation** — verifies whether the
 *     resolved delivery lifecycle (`MessageDeliveryState`) can legally change,
 *     using [TransportTransitionEvaluator].
 *
 * This evaluator does **not** derive lifecycle states. Both `old` and `new`
 * arguments must be full [DeliveryEvaluationState] snapshots. Resolution into the
 * final domain lifecycle state is performed inside this evaluator by calling
 * `resolveDelivery()` on each snapshot.
 *
 * The evaluator executes inside a [Raise] context, allowing illegal transitions
 * or inconsistent multi-axis states to be surfaced as
 * [StateTransitionError] variants without throwing exceptions.
 *
 * ---
 *
 * ## Multi-Axis State Model
 *
 * Each message delivery state is composed of two orthogonal axes:
 *
 * - **TransportStatus**
 *   (NEW → PENDING → ACKNOWLEDGED → REJECTED → INVALID)
 *
 * - **AppRecStatus?**
 *   (null → OK | OK_ERROR_IN_MESSAGE_PART | REJECTED)
 *
 * These axes are interpreted together to produce a resolved
 * [MessageDeliveryState] (via `resolveDelivery(..)`).
 *
 * `StateTransitionEvaluator` ensures that transitions across the *combined*
 * multi-axis state space are valid, consistent, and monotonic.
 *
 * ---
 *
 * ## Responsibilities
 *
 * ### 1. AppRec Transition Evaluation
 * Delegates to [AppRecTransitionEvaluator] to enforce apprec-specific rules
 * (e.g., *an AppRec is immutable once received*).
 *
 * ### 2. Cross-Field Invariants
 * Evaluates domain rules that involve both axes simultaneously, including:
 *
 * - An AppRec may only exist when `transport == ACKNOWLEDGED`.
 * - Additional multi-axis invariants may be added here as business rules evolve.
 *
 * Violations raise [StateTransitionError.IllegalCombinedState].
 *
 * ### 3. Transport-Driven Lifecycle Evaluation
 * After both snapshots have been resolved to [MessageDeliveryState] using
 * `resolveDelivery()`, the evaluator delegates to [TransportTransitionEvaluator]
 * to enforce the allowed transitions for the resolved lifecycle.
 *
 * ---
 *
 * ## UNCHANGED Handling
 *
 * The resolved [MessageDeliveryState] may be `UNCHANGED`, which is a control
 * signal used to indicate "no transition necessary". This evaluator never
 * produces or requires `UNCHANGED`; that responsibility lies with the state
 * machine (typically in `determineNextState`).
 *
 * ---
 *
 * @receiver Raise<StateTransitionError>
 *   The Raise context used for surfacing illegal multi-axis transitions.
 *
 * @param old
 *   The previously persisted multi-axis delivery state.
 *
 * @param new
 *   The newly evaluated multi-axis delivery state, prior to resolving the
 *   lifecycle result.
 *
 * @throws StateTransitionError.IllegalAppRecTransition
 *   If the AppRec axis attempts an illegal change.
 *
 * @throws StateTransitionError.IllegalCombinedState
 *   If the `transport` and `appRec` axes violate cross-field domain rules.
 *
 * @throws StateTransitionError.IllegalTransition
 *   If the resolved delivery lifecycle transition is not allowed.
 */
class StateTransitionEvaluator(
    private val transportValidator: TransportTransitionEvaluator,
    private val appRecValidator: AppRecTransitionEvaluator
) {
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

        with(transportValidator) { evaluate(oldResolved.state, newResolved.state) }
    }
}

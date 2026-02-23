package no.nav.helsemelding.outbound.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.model.DeliveryEvaluationState
import no.nav.helsemelding.outbound.model.isNotAcknowledged
import no.nav.helsemelding.outbound.model.isNotNull
import no.nav.helsemelding.outbound.model.resolveDelivery
import no.nav.helsemelding.outbound.model.toDeliveryState

/**
 * Evaluates whether a transition between two **multi-axis delivery states**
 * is permitted. This evaluator coordinates validation across the transport
 * and application receipt (apprec) axes and ensures that the resolved
 * lifecycle state (`MessageDeliveryState`) progresses legally.
 *
 * This evaluator **does not derive** lifecycle states itself. The caller must
 * supply two fully evaluated [DeliveryEvaluationState] snapshots (`old` and `new`),
 * each representing the complete multi-axis input required to resolve a
 * [MessageDeliveryState] via `resolveDelivery()`.
 *
 * The evaluator runs inside a [Raise] context so that illegal transitions and
 * inconsistent intermediate states can be surfaced as [StateTransitionError]
 * values without relying on exceptions.
 *
 * ---
 *
 * ## Multi-Axis State Model
 *
 * Each message delivery state consists of two orthogonal axes:
 *
 * - **TransportStatus**
 *   (NEW → PENDING → ACKNOWLEDGED → REJECTED → INVALID)
 *
 * - **AppRecStatus?**
 *   (null → OK | OK_ERROR_IN_MESSAGE_PART | REJECTED)
 *
 * These two axes are interpreted together to produce a resolved
 * **lifecycle state** (`MessageDeliveryState`), which represents the final
 * domain-level delivery status for the message.
 *
 * ---
 *
 * ## Responsibilities
 *
 * ### 1. AppRec Transition Evaluation
 * Delegates to [AppRecTransitionEvaluator] to ensure that application-level
 * receipt changes are legal.
 *
 * For example:
 * *An AppRec, once received, cannot change again.*
 *
 * Violations raise [StateTransitionError.IllegalAppRecTransition].
 *
 * ---
 *
 * ### 2. Cross-Field Domain Invariants
 * Enforces rules that involve both axes simultaneously.
 *
 * For example:
 * *An AppRec may only exist when `transport == ACKNOWLEDGED`.*
 *
 * Violations raise [StateTransitionError.IllegalCombinedState].
 *
 * ---
 *
 * ### 3. Transport-Driven Lifecycle Evaluation
 * After both snapshots have been resolved to concrete [MessageDeliveryState] values,
 * the evaluator delegates to [TransportTransitionEvaluator] to ensure that the
 * lifecycle progression is monotonic and legally ordered.
 *
 * For example:
 * *A message may not go from COMPLETED → PENDING.*
 *
 * Violations raise [StateTransitionError.IllegalTransition].
 *
 * ---
 *
 * ## Relationship to the State Machine
 *
 * This evaluator operates only on **real lifecycle states**. It does *not* interpret
 * control-flow signals such as “no transition necessary”.
 *
 * Whether a lifecycle transition should occur is determined externally by the
 * state machine (e.g., `determineNextState`), which produces a
 * [NextStateDecision] (`Transition(...)` or `Unchanged`).
 *
 * This evaluator receives only the resolved lifecycle states for validation and
 * does not handle or evaluate “unchanged” results.
 *
 * ---
 *
 * @receiver Raise<StateTransitionError>
 *   The raise context used to signal illegal transitions in a typed manner.
 *
 * @param old
 *   The previously persisted multi-axis delivery state.
 *
 * @param new
 *   The newly evaluated multi-axis delivery state before final lifecycle
 *   resolution is decided.
 *
 * @throws StateTransitionError.IllegalAppRecTransition
 *   If the AppRec axis attempts an illegal change.
 *
 * @throws StateTransitionError.IllegalCombinedState
 *   If cross-field invariants between the axes are violated.
 *
 * @throws StateTransitionError.IllegalTransition
 *   If the resolved lifecycle transition is invalid.
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

        with(transportValidator) { evaluate(oldResolved.toDeliveryState(), newResolved.toDeliveryState()) }
    }
}

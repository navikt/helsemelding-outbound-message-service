package no.nav.helsemelding.state.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.isNotNull

/**
 * Evaluates whether a transition between two application-level receipt states
 * ([AppRecStatus?]) is permitted.
 *
 * ## Domain Rule: AppRec Immutability
 *
 * AppRec is **immutable once received**. The only allowed state change is:
 *
 * - `null → OK | OK_ERROR_IN_MESSAGE_PART | REJECTED`
 *
 * Any attempt to modify an already-present AppRec (i.e., any transition where
 * `old != null` and `old != new`) is illegal and is surfaced as
 * [StateTransitionError.IllegalAppRecTransition].
 *
 * The evaluator runs inside a [Raise] context, allowing illegal transitions to be
 * surfaced as typed [StateTransitionError] values without throwing exceptions and
 * without side effects.
 *
 * ---
 *
 * @receiver Raise<StateTransitionError>
 *   The raise context used to surface illegal AppRec transitions.
 *
 * @param old
 *   The previously persisted AppRec state.
 *
 * @param new
 *   The newly evaluated AppRec state.
 *
 * @throws StateTransitionError.IllegalAppRecTransition
 *   If an existing AppRec is changed after being set.
 */
class AppRecTransitionEvaluator {
    fun Raise<StateTransitionError>.evaluate(old: AppRecStatus?, new: AppRecStatus?) {
        if (old == new) return

        if (old.isNotNull()) {
            raise(
                StateTransitionError.IllegalAppRecTransition(
                    from = old,
                    to = new
                )
            )
        }
    }
}

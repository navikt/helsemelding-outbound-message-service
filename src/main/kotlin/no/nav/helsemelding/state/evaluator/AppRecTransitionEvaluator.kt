package no.nav.helsemelding.state.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.isNotNull

/**
 * Evaluates whether a transition between two application-level receipt states
 * is allowed.
 *
 * AppRec is immutable once received: only a transition from `null` to a
 * concrete value (`OK`, `OK_ERROR_IN_MESSAGE_PART`, or `REJECTED`) is permitted.
 * Any attempt to change an existing AppRec raises
 * [StateTransitionError.IllegalAppRecTransition].
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

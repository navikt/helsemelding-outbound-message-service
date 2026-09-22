package no.nav.helsemelding.outbound.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.isNotNull

/** Validates transitions between application receipt statuses. */
class AppRecTransitionEvaluator {
    /**
     * Allows an absent status to be set and an existing status to remain unchanged.
     *
     * Raises [StateTransitionError.IllegalAppRecTransition] through [Raise]
     * if an existing status is changed or removed.
     *
     * @param old The previously stored application receipt status.
     * @param new The latest application receipt status from the external system.
     */
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

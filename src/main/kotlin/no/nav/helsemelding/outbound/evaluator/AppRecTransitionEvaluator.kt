package no.nav.helsemelding.outbound.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.isNotNull

/**
 * Enforces application receipt status immutability.
 *
 * An absent status may be set, and unchanged values are allowed. Once present, a status
 * cannot change or disappear; either case raises [StateTransitionError.IllegalAppRecTransition]
 * through [Raise].
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

package no.nav.helsemelding.state.evaluator

import arrow.core.raise.Raise
import no.nav.helsemelding.state.StateEvaluationError
import no.nav.helsemelding.state.StateEvaluationError.UnresolvableState
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.MessageDeliveryState
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.state.model.isAcknowledged
import no.nav.helsemelding.state.model.isNotNull
import no.nav.helsemelding.state.model.isNull
import no.nav.helsemelding.state.model.isOk
import no.nav.helsemelding.state.model.isOkErrorInMessagePart
import no.nav.helsemelding.state.model.isRejected
import no.nav.helsemelding.state.model.isUnconfirmed

/**
 * Evaluates the internal {@link MessageDeliveryState} based on the raw external inputs
 * received from the remote system: {@link ExternalDeliveryState} and {@link AppRecStatus}.
 *
 * This function encapsulates the domain rules for interpreting the two external state
 * dimensions and merging them into a single internal delivery state used by the system.
 *
 * Evaluation is total for all *valid* combinations, but may fail for inputs that
 * cannot be mapped consistently. When such an inconsistency is detected, the evaluator
 * raises a {@link StateEvaluationError.UnresolvableState} through the [Raise] context,
 * allowing callers to handle the failure without exceptions.
 *
 * ## Mapping Rules
 *
 * The following rules determine the resolved internal state:
 *
 * - **NEW**
 *   When `externalDeliveryState == null` **and** `appRecStatus == null`.
 *
 * - **PENDING**
 *   When the message is acknowledged or unconfirmed by the transport layer, and no
 *   application-level receipt is yet available.
 *
 * - **COMPLETED**
 *   When the application-level receipt indicates successful processing
 *   (`OK` or `OK_ERROR_IN_MESSAGE_PART`).
 *
 * - **REJECTED**
 *   When either the transport layer (`ExternalDeliveryState.REJECTED`) or the
 *   application-level receipt (`AppRecStatus.REJECTED`) indicates failure.
 *
 * - **Unresolvable**
 *   Any remaining combination of `externalDeliveryState` and `appRecStatus` that
 *   does not correspond to a valid state transition results in a raised
 *   `UnresolvableState`.
 *
 * @receiver Raise<StateEvaluationError>
 *   The Raise context used to signal evaluation errors.
 *
 * @param externalDeliveryState
 *   The transport-level delivery status reported by the external system, or `null`
 *   if no such status is currently available.
 *
 * @param appRecStatus
 *   The application-level receipt status, or `null` if no receipt is available.
 *
 * @return The resolved internal {@link MessageDeliveryState}.
 *
 * @throws StateEvaluationError.UnresolvableState
 *   Raised through the [Raise] context if no valid internal state can be determined
 *   from the provided external inputs.
 */
class StateEvaluator {
    fun Raise<StateEvaluationError>.evaluate(
        externalDeliveryState: ExternalDeliveryState?,
        appRecStatus: AppRecStatus?
    ): MessageDeliveryState = when {
        externalDeliveryState.isNull() && appRecStatus.isNull() -> NEW
        externalDeliveryState.isRejected() -> REJECTED
        externalDeliveryState.isUnconfirmed() && appRecStatus.isNotNull() -> raise(
            UnresolvableState(
                externalDeliveryState,
                appRecStatus
            )
        )

        externalDeliveryState.isAcknowledged() &&
            (appRecStatus.isOk() || appRecStatus.isOkErrorInMessagePart()) -> COMPLETED

        externalDeliveryState.isAcknowledged() && appRecStatus.isRejected() -> REJECTED
        externalDeliveryState.isAcknowledged() && appRecStatus.isNull() -> PENDING
        externalDeliveryState.isUnconfirmed() && appRecStatus.isNull() -> PENDING
        else -> raise(UnresolvableState(externalDeliveryState, appRecStatus))
    }
}

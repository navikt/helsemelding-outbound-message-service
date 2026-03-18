package no.nav.helsemelding.outbound

import no.nav.helsemelding.ediadapter.model.ErrorMessage
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.MessageDeliveryState
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.payloadsigning.model.MessageSigningError
import java.net.URL
import kotlin.uuid.Uuid

sealed interface Error

sealed interface StateError : Error

sealed class StateTransitionError : StateError {
    data class IllegalTransition(
        val from: MessageDeliveryState,
        val to: MessageDeliveryState
    ) : StateTransitionError()

    data class IllegalAppRecTransition(
        val from: AppRecStatus?,
        val to: AppRecStatus?
    ) : StateTransitionError()

    data class IllegalCombinedState(
        val message: String
    ) : StateTransitionError()
}

sealed interface EdiAdapterError : StateError {
    data class NoApprecReturned(
        val externalRefId: Uuid
    ) : EdiAdapterError

    data class FetchFailure(
        val externalRefId: Uuid,
        val cause: ErrorMessage
    ) : EdiAdapterError

    data class SendFailure(
        val lifecycleId: Uuid,
        val cause: ErrorMessage
    ) : EdiAdapterError
}

sealed interface SigningServiceError : StateError {
    data class SignFailure(
        val lifecycleId: Uuid,
        val cause: MessageSigningError
    ) : SigningServiceError
}

sealed interface PublishError : StateError {
    data class Failure(
        val messageId: Uuid,
        val topic: String,
        val cause: Throwable
    ) : PublishError
}

sealed interface LifecycleError : StateError {

    sealed interface Conflict : LifecycleError

    data class ConflictingLifecycleId(
        val messageId: Uuid,
        val existingExternalRefId: Uuid?,
        val existingExternalUrl: URL?,
        val newExternalRefId: Uuid?,
        val newExternalUrl: URL?
    ) : Conflict

    data class ConflictingExternalReferenceId(
        val externalRefId: Uuid,
        val existingMessageId: Uuid,
        val newMessageId: Uuid
    ) : Conflict

    data class ConflictingExternalMessageUrl(
        val externalUrl: URL,
        val existingMessageId: Uuid,
        val newMessageId: Uuid
    ) : Conflict

    data class PersistenceFailure(
        val messageId: Uuid,
        val reason: String
    ) : LifecycleError

    sealed interface ExternalFailure : LifecycleError

    data class SigningFailure(
        val cause: SigningServiceError
    ) : ExternalFailure

    data class EdiFailure(
        val cause: EdiAdapterError
    ) : ExternalFailure
}

fun StateError.withMessageContext(message: MessageState): String = "Message ${message.externalRefId}: $this"

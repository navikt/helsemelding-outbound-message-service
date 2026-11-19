package no.nav.helsemelding.outbound

import no.nav.helsemelding.messageconverter.error.ConversionError
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.MessageDeliveryState
import no.nav.helsemelding.outbound.model.MessageState
import no.nav.helsemelding.outbound.model.MessageTransport
import kotlin.uuid.Uuid
import no.nav.helsemelding.ediadapter.client.EdiAdapterError as ClientEdiAdapterError

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
    data class SendFailure(
        val lifecycleId: Uuid,
        val cause: ClientEdiAdapterError
    ) : EdiAdapterError
}

sealed interface PublishError : StateError {
    data class Failure(
        val messageId: Uuid,
        val topic: String,
        val cause: Throwable
    ) : PublishError
}

sealed interface FetchStatusError : StateError {
    data class FetchFailure(val cause: ClientEdiAdapterError) : FetchStatusError

    data class UnexpectedReceiverCount(val count: Int) : FetchStatusError
}

sealed interface EdiStatusError : StateError {
    data class MessageNotFound(val messageId: Uuid) : EdiStatusError

    data class UnexpectedTransport(val messageId: Uuid, val transport: MessageTransport) : EdiStatusError
}

sealed interface MessageProcessingError : StateError {
    data class LifecycleFailure(val cause: LifecycleError) : MessageProcessingError

    data class ErrorPublicationFailure(val cause: PublishError) : MessageProcessingError
}

sealed interface LifecycleError : StateError {

    sealed interface Conflict : LifecycleError

    data class ConflictingLifecycleId(
        val messageId: Uuid,
        val existingExternalRefId: Uuid?,
        val newExternalRefId: Uuid?
    ) : Conflict

    data class ConflictingExternalReferenceId(
        val externalRefId: Uuid,
        val existingMessageId: Uuid,
        val newMessageId: Uuid
    ) : Conflict

    data class PersistenceFailure(
        val messageId: Uuid,
        val reason: String
    ) : LifecycleError

    data class MessageNotFound(
        val messageId: Uuid
    ) : LifecycleError

    data class UnexpectedPublicationTransport(val messageId: Uuid, val transport: MessageTransport) : LifecycleError

    data class MetadataExtractionFailure(val messageId: Uuid, val cause: ConversionError) : LifecycleError

    data class MissingExternalReferenceId(val messageId: Uuid) : LifecycleError

    sealed interface ExternalFailure : LifecycleError

    data class EdiFailure(
        val cause: EdiAdapterError
    ) : ExternalFailure

    data class EdiPublishFailure(val messageId: Uuid, val cause: PublishError) : ExternalFailure
}

fun StateError.withMessageContext(message: MessageState): String = "Message ${message.externalRefId}: $this"

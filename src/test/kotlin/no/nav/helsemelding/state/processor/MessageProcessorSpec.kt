package no.nav.helsemelding.state.processor

import arrow.core.Either.Left
import arrow.core.Either.Right
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import no.nav.helsemelding.ediadapter.model.ErrorMessage
import no.nav.helsemelding.ediadapter.model.Metadata
import no.nav.helsemelding.state.FakeEdiAdapterClient
import no.nav.helsemelding.state.model.DialogMessage
import no.nav.helsemelding.state.receiver.fakeMessageReceiver
import no.nav.helsemelding.state.service.FakeTransactionalMessageStateService
import kotlin.uuid.Uuid

class MessageProcessorSpec : StringSpec(
    {

        "create state if ediAdapterClient returns metadata and no error message" {
            val messageStateService = FakeTransactionalMessageStateService()
            val ediAdapterClient = FakeEdiAdapterClient()
            val messageProcessor = MessageProcessor(
                fakeMessageReceiver(),
                messageStateService,
                ediAdapterClient
            )

            val uuid = Uuid.random()
            val location = "https://example.com/messages/$uuid"
            val metadata = Metadata(
                id = uuid,
                location = location
            )
            ediAdapterClient.givenPostMessage(Right(metadata))

            messageStateService.getMessageSnapshot(uuid).shouldBeNull()

            val dialogMessage = DialogMessage(uuid, "data".toByteArray())
            messageProcessor.processAndSendMessage(this, dialogMessage).join()

            val messageSnapshot = messageStateService.getMessageSnapshot(uuid)
            messageSnapshot.shouldNotBeNull()
            messageSnapshot.messageState.externalRefId shouldBeEqual uuid
            messageSnapshot.messageState.externalMessageUrl.toString() shouldBeEqual location
        }

        "no state created if ediAdapterClient returns error message and no metadata" {
            val messageStateService = FakeTransactionalMessageStateService()
            val ediAdapterClient = FakeEdiAdapterClient()
            val messageProcessor = MessageProcessor(
                fakeMessageReceiver(),
                messageStateService,
                ediAdapterClient
            )

            val uuid = Uuid.random()

            val errorMessage500 = ErrorMessage(
                error = "Internal Server Error",
                errorCode = 1000,
                validationErrors = listOf("Example error"),
                stackTrace = "[StackTrace]",
                requestId = Uuid.random().toString()
            )

            ediAdapterClient.givenPostMessage(Left(errorMessage500))

            messageStateService.getMessageSnapshot(uuid).shouldBeNull()

            val dialogMessage = DialogMessage(uuid, "data".toByteArray())
            messageProcessor.processAndSendMessage(this, dialogMessage).join()

            messageStateService.getMessageSnapshot(uuid).shouldBeNull()
        }
    }
)

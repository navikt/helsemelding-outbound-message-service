package no.nav.helsemelding.state.processor

import arrow.core.Either.Left
import arrow.core.Either.Right
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import no.nav.helsemelding.ediadapter.model.ErrorMessage
import no.nav.helsemelding.ediadapter.model.Metadata
import no.nav.helsemelding.payloadsigning.model.MessageSigningError
import no.nav.helsemelding.payloadsigning.model.PayloadResponse
import no.nav.helsemelding.state.FakeEdiAdapterClient
import no.nav.helsemelding.state.FakePayloadSigningClient
import no.nav.helsemelding.state.metrics.FakeMetrics
import no.nav.helsemelding.state.model.DialogMessage
import no.nav.helsemelding.state.receiver.fakeMessageReceiver
import no.nav.helsemelding.state.service.FakeTransactionalMessageStateService
import kotlin.uuid.Uuid

class MessageProcessorSpec : StringSpec(
    {

        "create state if payloadSigningClient returns signed payload and ediAdapterClient returns metadata" {
            val messageStateService = FakeTransactionalMessageStateService()
            val ediAdapterClient = FakeEdiAdapterClient()
            val payloadSigningClient = FakePayloadSigningClient()
            val metrics = FakeMetrics()
            val messageProcessor = MessageProcessor(
                fakeMessageReceiver(metrics),
                messageStateService,
                ediAdapterClient,
                payloadSigningClient,
                metrics
            )

            val payload = "data".toByteArray()
            payloadSigningClient.givenSignPayload(Right(PayloadResponse(payload)))

            val uuid = Uuid.random()
            val location = "https://example.com/messages/$uuid"
            val metadata = Metadata(
                id = uuid,
                location = location
            )
            ediAdapterClient.givenPostMessage(Right(metadata))

            messageStateService.getMessageSnapshot(uuid).shouldBeNull()

            val dialogMessage = DialogMessage(uuid, payload)
            messageProcessor.processAndSendMessage(dialogMessage)

            val messageSnapshot = messageStateService.getMessageSnapshot(uuid)
            messageSnapshot.shouldNotBeNull()
            messageSnapshot.messageState.externalRefId shouldBeEqual uuid
            messageSnapshot.messageState.externalMessageUrl.toString() shouldBeEqual location
        }

        "no state created if payloadSigningClient returns signed payload and ediAdapterClient returns error message" {
            val messageStateService = FakeTransactionalMessageStateService()
            val ediAdapterClient = FakeEdiAdapterClient()
            val payloadSigningClient = FakePayloadSigningClient()
            val metrics = FakeMetrics()
            val messageProcessor = MessageProcessor(
                fakeMessageReceiver(metrics),
                messageStateService,
                ediAdapterClient,
                payloadSigningClient,
                metrics
            )

            val payload = "data".toByteArray()
            payloadSigningClient.givenSignPayload(Right(PayloadResponse(payload)))

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

            val dialogMessage = DialogMessage(uuid, payload)
            messageProcessor.processAndSendMessage(dialogMessage)

            messageStateService.getMessageSnapshot(uuid).shouldBeNull()
        }

        "no state created if payloadSigningClient returns signing error" {
            val messageStateService = FakeTransactionalMessageStateService()
            val ediAdapterClient = FakeEdiAdapterClient()
            val payloadSigningClient = FakePayloadSigningClient()
            val metrics = FakeMetrics()
            val messageProcessor = MessageProcessor(
                fakeMessageReceiver(metrics),
                messageStateService,
                ediAdapterClient,
                payloadSigningClient,
                metrics
            )

            val uuid = Uuid.random()

            payloadSigningClient.givenSignPayload(
                Left(
                    MessageSigningError(
                        InternalServerError.value,
                        "Internal Server Error"
                    )
                )
            )

            messageStateService.getMessageSnapshot(uuid).shouldBeNull()

            val dialogMessage = DialogMessage(uuid, "data".toByteArray())
            messageProcessor.processAndSendMessage(dialogMessage)

            messageStateService.getMessageSnapshot(uuid).shouldBeNull()
        }
    }
)

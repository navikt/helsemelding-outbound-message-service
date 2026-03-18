package no.nav.helsemelding.outbound.service

import arrow.core.Either.Left
import arrow.core.Either.Right
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equality.shouldBeEqualUsingFields
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import no.nav.helsemelding.ediadapter.model.ErrorMessage
import no.nav.helsemelding.ediadapter.model.Metadata
import no.nav.helsemelding.outbound.FakeEdiAdapterClient
import no.nav.helsemelding.outbound.FakePayloadSigningClient
import no.nav.helsemelding.outbound.LifecycleError.EdiAdapterFailure
import no.nav.helsemelding.outbound.LifecycleError.PersistenceFailure
import no.nav.helsemelding.outbound.LifecycleError.SigningServiceFailure
import no.nav.helsemelding.outbound.metrics.FakeMetrics
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.shouldBeLeftOfType
import no.nav.helsemelding.payloadsigning.model.MessageSigningError
import no.nav.helsemelding.payloadsigning.model.PayloadResponse
import java.net.URI
import kotlin.uuid.Uuid

class MessageLifecycleServiceSpec : StringSpec(
    {

        lateinit var messageStateService: FakeTransactionalMessageStateService
        lateinit var ediAdapterClient: FakeEdiAdapterClient
        lateinit var payloadSigningClient: FakePayloadSigningClient
        lateinit var messageLifecycleService: MessageLifecycleService

        beforeEach {
            messageStateService = FakeTransactionalMessageStateService()
            ediAdapterClient = FakeEdiAdapterClient()
            payloadSigningClient = FakePayloadSigningClient()
            messageLifecycleService = MessageLifecycleOrchestratorService(
                messageStateService,
                ediAdapterClient,
                payloadSigningClient,
                FakeMetrics()
            )
        }

        "registering with existing messageId should return existing messageStateSnapshot" {
            val messageId = Uuid.random()
            val externalRefId = Uuid.random()
            val externalMessageUrl = URI("https://example.com/messages/$externalRefId").toURL()

            messageStateService.createInitialState(
                CreateState(
                    id = messageId,
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    externalMessageUrl = externalMessageUrl
                )
            )

            messageStateService.getMessageSnapshotById(messageId).shouldNotBeNull()

            val payload = "data".toByteArray()
            val registeredMessageSnapshot =
                messageLifecycleService.registerOutgoingMessage(messageId, payload).shouldBeRight()
            val messageStateSnapshot = messageStateService.getMessageSnapshotById(messageId).shouldNotBeNull()

            registeredMessageSnapshot shouldBeEqualUsingFields messageStateSnapshot
        }

        "create state if payloadSigningClient returns signed payload and ediAdapterClient returns metadata" {
            val payload = "data".toByteArray()
            payloadSigningClient.givenSignPayload(Right(PayloadResponse(payload)))

            val messageId = Uuid.random()
            val externalRefId = Uuid.random()
            val location = "https://example.com/messages/$externalRefId"
            val metadata = Metadata(
                id = externalRefId,
                location = location
            )
            ediAdapterClient.givenPostMessage(Right(metadata))

            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()

            val registeredMessageSnapshot =
                messageLifecycleService.registerOutgoingMessage(messageId, payload).shouldBeRight()
            val messageStateSnapshot = messageStateService.getMessageSnapshotById(messageId).shouldNotBeNull()

            registeredMessageSnapshot shouldBeEqualUsingFields messageStateSnapshot
        }

        "no state created if payloadSigningClient returns signed payload and ediAdapterClient returns metadata, but creating state fails" {
            val payload = "data".toByteArray()
            payloadSigningClient.givenSignPayload(Right(PayloadResponse(payload)))

            val messageId = Uuid.random()
            val externalRefId = Uuid.random()
            val location = "https://example.com/messages/$externalRefId"
            val metadata = Metadata(
                id = externalRefId,
                location = location
            )
            ediAdapterClient.givenPostMessage(Right(metadata))
            messageStateService.givenInitialState(
                messageId,
                Left(
                    PersistenceFailure(
                        messageId,
                        "Failed to persist"
                    )
                )
            )

            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()

            messageLifecycleService.registerOutgoingMessage(messageId, payload)
                .shouldBeLeftOfType<PersistenceFailure> { lifecycleError ->
                    lifecycleError.messageId shouldBe messageId
                    lifecycleError.reason shouldBe "Failed to persist"
                }

            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()
        }

        "no state created if payloadSigningClient returns signed payload and ediAdapterClient returns error message" {
            val payload = "data".toByteArray()
            payloadSigningClient.givenSignPayload(Right(PayloadResponse(payload)))

            val messageId = Uuid.random()
            val errorMessage = "Internal Server Error"
            val errorMessage500 = ErrorMessage(
                error = errorMessage,
                errorCode = 1000,
                validationErrors = listOf("Example error"),
                stackTrace = "[StackTrace]",
                requestId = Uuid.random().toString()
            )
            ediAdapterClient.givenPostMessage(Left(errorMessage500))

            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()

            messageLifecycleService.registerOutgoingMessage(messageId, payload)
                .shouldBeLeftOfType<EdiAdapterFailure> { lifecycleError ->
                    lifecycleError.messageId shouldBe messageId
                    lifecycleError.cause shouldBeEqualUsingFields errorMessage500
                }
            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()
        }

        "no state created if payloadSigningClient returns signing error" {
            val messageId = Uuid.random()
            val errorMessage = "Internal Server Error"
            payloadSigningClient.givenSignPayload(
                Left(
                    MessageSigningError(
                        HttpStatusCode.InternalServerError.value,
                        errorMessage
                    )
                )
            )

            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()

            val payload = "data".toByteArray()
            messageLifecycleService.registerOutgoingMessage(messageId, payload)
                .shouldBeLeftOfType<SigningServiceFailure> { lifecycleError ->
                    lifecycleError.messageId shouldBe messageId
                    lifecycleError.reason shouldBe errorMessage
                }
            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()
        }
    }
)

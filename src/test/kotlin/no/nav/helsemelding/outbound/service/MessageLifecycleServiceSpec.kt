package no.nav.helsemelding.outbound.service

import arrow.core.Either.Left
import arrow.core.Either.Right
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.equality.shouldBeEqualUsingFields
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.ediadapter.client.EdiAdapterError
import no.nav.helsemelding.ediadapter.model.v3.PostMessageResponse
import no.nav.helsemelding.outbound.EdiAdapterError.SendFailure
import no.nav.helsemelding.outbound.FakeEdiAdapterClient
import no.nav.helsemelding.outbound.LifecycleError.EdiFailure
import no.nav.helsemelding.outbound.LifecycleError.InvalidExternalReferenceId
import no.nav.helsemelding.outbound.LifecycleError.MetadataExtractionFailure
import no.nav.helsemelding.outbound.LifecycleError.MissingExternalReferenceId
import no.nav.helsemelding.outbound.LifecycleError.PersistenceFailure
import no.nav.helsemelding.outbound.metrics.ErrorTypeTag
import no.nav.helsemelding.outbound.metrics.FakeMetrics
import no.nav.helsemelding.outbound.metrics.Metrics
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.shouldBeLeftOfType
import kotlin.uuid.Uuid

class MessageLifecycleServiceSpec : StringSpec(
    {
        lateinit var messageStateService: FakeTransactionalMessageStateService
        lateinit var ediAdapterClient: FakeEdiAdapterClient
        lateinit var messageLifecycleService: MessageLifecycleService
        val reportedErrors = mutableListOf<ErrorTypeTag>()

        beforeEach {
            reportedErrors.clear()
            messageStateService = FakeTransactionalMessageStateService()
            ediAdapterClient = FakeEdiAdapterClient()
            messageLifecycleService = MessageLifecycleOrchestratorService(
                messageStateService,
                ediAdapterClient,
                object : Metrics by FakeMetrics() {
                    override fun registerOutgoingMessageFailed(errorType: ErrorTypeTag) {
                        reportedErrors.add(errorType)
                    }
                }
            )
        }

        "returns existing state without sending again" {
            val messageId = Uuid.random()
            val externalRefId = Uuid.random()

            messageStateService.createInitialState(
                CreateState(
                    id = messageId,
                    externalRefId = externalRefId,
                    messageType = DIALOG
                )
            )

            messageStateService.getMessageSnapshotById(messageId).shouldNotBeNull()

            val payload = "not XML".toByteArray()
            val registeredMessageSnapshot =
                messageLifecycleService.registerOutgoingMessage(messageId, payload).shouldBeRight()
            val messageStateSnapshot = messageStateService.getMessageSnapshotById(messageId).shouldNotBeNull()

            registeredMessageSnapshot shouldBeEqualUsingFields messageStateSnapshot
            ediAdapterClient.sentMessages shouldBe emptyList()
            reportedErrors shouldBe emptyList()
        }

        "creates state when the adapter returns a valid message ID" {
            val payload = messageXml.toByteArray()

            val messageId = Uuid.random()
            val externalRefId = Uuid.random()
            val metadata = PostMessageResponse(
                id = externalRefId.toString()
            )
            ediAdapterClient.givenPostMessage(Right(metadata))

            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()

            val registeredMessageSnapshot =
                messageLifecycleService.registerOutgoingMessage(messageId, payload).shouldBeRight()
            val messageStateSnapshot = messageStateService.getMessageSnapshotById(messageId).shouldNotBeNull()

            registeredMessageSnapshot shouldBeEqualUsingFields messageStateSnapshot
        }

        "sends extracted metadata and the original payload without application identity" {
            val payload = messageXml.toByteArray()
            ediAdapterClient.givenPostMessage(Right(PostMessageResponse(Uuid.random().toString())))
            messageLifecycleService.registerOutgoingMessage(Uuid.random(), payload).shouldBeRight()
            val request = ediAdapterClient.sentMessages.single()

            request.senderHerId shouldBe 600
            request.receiverHerIds shouldBe listOf(700)
            request.messageTypeIdentificator shouldBe "DIALOG_FORESPORSEL"
            request.businessDocument shouldBe kotlin.io.encoding.Base64.encode(payload)
            request.contentType shouldBe "application/xml"
            request.contentTransferEncoding shouldBe "base64"
        }

        "does not send or create state when metadata extraction fails" {
            val messageId = Uuid.random()

            messageLifecycleService.registerOutgoingMessage(messageId, "<invalid".toByteArray())
                .shouldBeLeftOfType<MetadataExtractionFailure> { error ->
                    error.messageId shouldBe messageId
                }

            ediAdapterClient.sentMessages shouldBe emptyList()
            reportedErrors shouldBe listOf(ErrorTypeTag.METADATA_EXTRACTION_FAILED)
            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()
        }

        "returns a lifecycle error when the response has no message ID" {
            val messageId = Uuid.random()
            ediAdapterClient.givenPostMessage(Right(PostMessageResponse(null)))

            messageLifecycleService.registerOutgoingMessage(messageId, messageXml.toByteArray())
                .shouldBeLeftOfType<MissingExternalReferenceId> { error ->
                    error.messageId shouldBe messageId
                }

            ediAdapterClient.sentMessages.size shouldBe 1
            reportedErrors shouldBe listOf(ErrorTypeTag.EXTERNAL_REFERENCE_VALIDATION_FAILED)
            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()
        }

        "returns a lifecycle error when the response has an invalid message ID" {
            for (id in listOf("", "invalid-id")) {
                reportedErrors.clear()
                val messageId = Uuid.random()
                ediAdapterClient.givenPostMessage(Right(PostMessageResponse(id)))

                messageLifecycleService.registerOutgoingMessage(messageId, messageXml.toByteArray())
                    .shouldBeLeftOfType<InvalidExternalReferenceId> { error ->
                        error.messageId shouldBe messageId
                        error.externalRefId shouldBe id
                    }

                reportedErrors shouldBe listOf(ErrorTypeTag.EXTERNAL_REFERENCE_VALIDATION_FAILED)
                messageStateService.getMessageSnapshotById(messageId).shouldBeNull()
            }
        }

        "returns a persistence error when state creation fails after sending" {
            val payload = messageXml.toByteArray()

            val messageId = Uuid.random()
            val externalRefId = Uuid.random()
            val metadata = PostMessageResponse(
                id = externalRefId.toString()
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
            reportedErrors shouldBe listOf(ErrorTypeTag.STATE_INITIALIZATION_FAILED)

            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()
        }

        "does not create state when the adapter returns an error" {
            val payload = messageXml.toByteArray()

            val messageId = Uuid.random()
            val errorMessage500 = EdiAdapterError.Api(500)
            ediAdapterClient.givenPostMessage(Left(errorMessage500))

            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()

            messageLifecycleService.registerOutgoingMessage(messageId, payload)
                .shouldBeLeftOfType<EdiFailure> { lifecycleError ->
                    lifecycleError.cause shouldBeEqualUsingFields SendFailure(messageId, errorMessage500)
                }
            reportedErrors shouldBe listOf(ErrorTypeTag.SENDING_TO_EDI_ADAPTER_FAILED)
            messageStateService.getMessageSnapshotById(messageId).shouldBeNull()
        }
    }
)

private val messageXml = """
    <MsgHead xmlns="http://www.kith.no/xmlstds/msghead/2006-05-24">
        <MsgInfo>
            <Type V="DIALOG_FORESPORSEL"/>
            <Sender>
                <Organisation>
                    <Ident><Id>600</Id><TypeId V="HER"/></Ident>
                </Organisation>
            </Sender>
            <Receiver>
                <Organisation>
                    <Ident><Id>700</Id><TypeId V="HER"/></Ident>
                </Organisation>
            </Receiver>
        </MsgInfo>
    </MsgHead>
""".trimIndent()

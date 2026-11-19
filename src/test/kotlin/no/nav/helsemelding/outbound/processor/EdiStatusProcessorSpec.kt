package no.nav.helsemelding.outbound.processor

import arrow.core.left
import io.github.nomisRev.kafka.receiver.AutoOffsetReset
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.EdiStatusError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.AppRecErrorMessage
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.EdiStatus.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.EdiStatus.REJECTED
import no.nav.helsemelding.outbound.model.EdiStatus.REJECTED_TRANSPORT
import no.nav.helsemelding.outbound.model.EdiStatusEvent
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.MessageStatus
import no.nav.helsemelding.outbound.model.MessageStatus.COMPLETED
import no.nav.helsemelding.outbound.model.MessageStatus.REJECTED_APPREC
import no.nav.helsemelding.outbound.model.MessageTransport.EDI_1_0
import no.nav.helsemelding.outbound.model.MessageTransport.EDI_2_0
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.publisher.FakeStatusMessagePublisher
import no.nav.helsemelding.outbound.receiver.EdiMessageReceiver
import no.nav.helsemelding.outbound.service.FakeTransactionalMessageStateService
import no.nav.helsemelding.outbound.service.MessageStatusService
import no.nav.helsemelding.outbound.service.StatusProcessingException
import no.nav.helsemelding.outbound.service.testStateEvaluator
import kotlin.uuid.Uuid

class EdiStatusProcessorSpec : StringSpec({
    val kafka = config().kafka
    val receiver = EdiMessageReceiver(
        config().edi.statusMessageTopic,
        KafkaReceiver(kafka.toReceiverSettings(kafka, AutoOffsetReset.Latest))
    )
    lateinit var messageStateService: FakeTransactionalMessageStateService
    lateinit var statusMessagePublisher: FakeStatusMessagePublisher

    beforeEach {
        messageStateService = FakeTransactionalMessageStateService()
        statusMessagePublisher = FakeStatusMessagePublisher()
    }

    fun processor() = EdiStatusProcessor(
        receiver,
        messageStateService,
        MessageStatusService(messageStateService, testStateEvaluator(), statusMessagePublisher)
    )

    "both application outcomes publish and persist their final state once" {
        val processor = processor()
        for ((status, expected, appRec) in listOf(
            Triple(ACKNOWLEDGED, COMPLETED, AppRecStatus.OK),
            Triple(REJECTED, REJECTED_APPREC, AppRecStatus.REJECTED)
        )) {
            val id = Uuid.random()
            messageStateService.createInitialState(
                CreateState(
                    id,
                    id,
                    DIALOG,
                    transport = EDI_1_0
                )
            )
                .shouldBeRight()

            val errors = if (status == REJECTED) {
                listOf(
                    AppRecErrorMessage(
                        code = "E1",
                        details = "Invalid document"
                    )
                )
            } else {
                emptyList()
            }

            val statusEvent = EdiStatusEvent(status, 700, errors)

            processor.processStatus(id, statusEvent).shouldBeRight()
            processor.processStatus(id, statusEvent).shouldBeRight()

            statusMessagePublisher.published.count { it.messageId == id } shouldBe 1
            val publishedStatusEvent = statusMessagePublisher.published.last()
            publishedStatusEvent.status shouldBe expected
            publishedStatusEvent.apprec!!.status shouldBe appRec.name
            publishedStatusEvent.apprec.receiverHerId shouldBe 700
            publishedStatusEvent.apprec.errorList shouldBe errors
            publishedStatusEvent.apprec.id shouldBe null
            val messageStateSnapshot = messageStateService.getMessageSnapshotById(id)!!
            messageStateSnapshot.messageState.externalDeliveryState shouldBe ExternalDeliveryState.ACKNOWLEDGED
            messageStateSnapshot.messageState.appRecStatus shouldBe appRec
            messageStateSnapshot.messageStateChanges.size shouldBe 2
        }
    }

    "transport rejection remains final on duplicate or later application statuses" {
        val id = Uuid.random()
        messageStateService.createInitialState(CreateState(id, id, DIALOG, transport = EDI_1_0))
            .shouldBeRight()
        val processor = processor()

        processor.processStatus(id, EdiStatusEvent(REJECTED_TRANSPORT)).shouldBeRight()
        processor.processStatus(id, EdiStatusEvent(REJECTED_TRANSPORT)).shouldBeRight()
        processor.processStatus(id, EdiStatusEvent(ACKNOWLEDGED)).shouldBeRight()

        val publishedStatusEvent = statusMessagePublisher.published.single()
        publishedStatusEvent.messageId shouldBe id
        publishedStatusEvent.status shouldBe MessageStatus.REJECTED_TRANSPORT
        publishedStatusEvent.apprec shouldBe null
        publishedStatusEvent.error!!.code shouldBe "TRANSPORT_REJECTED"
        val messageStateSnapshot = messageStateService.getMessageSnapshotById(id)!!
        messageStateSnapshot.messageState.externalDeliveryState shouldBe ExternalDeliveryState.REJECTED
        messageStateSnapshot.messageState.appRecStatus shouldBe null
        messageStateSnapshot.messageStateChanges.size shouldBe 2
        messageStateSnapshot.messageStateChanges.last().newDeliveryState shouldBe ExternalDeliveryState.REJECTED
        messageStateSnapshot.messageStateChanges.last().newAppRecStatus shouldBe null
    }

    "unknown messages and statuses for EDI 2 messages return typed errors" {
        val unknown = Uuid.random()
        val edi2 = Uuid.random()
        messageStateService.createInitialState(CreateState(edi2, Uuid.random(), DIALOG)).shouldBeRight()
        val processor = processor()

        processor.processStatus(
            unknown,
            EdiStatusEvent(ACKNOWLEDGED)
        ) shouldBe EdiStatusError.MessageNotFound(unknown).left()

        processor.processStatus(
            edi2,
            EdiStatusEvent(ACKNOWLEDGED)
        ) shouldBe EdiStatusError.UnexpectedTransport(edi2, EDI_2_0).left()

        statusMessagePublisher.published shouldBe emptyList()
        messageStateService.getMessageSnapshotById(edi2)!!.messageState.appRecStatus shouldBe null
    }

    "failed status publication leaves the stored state unchanged" {
        val id = Uuid.random()
        val initial = messageStateService.createInitialState(CreateState(id, id, DIALOG, transport = EDI_1_0))
            .shouldBeRight()
        statusMessagePublisher.failNext = true

        shouldThrow<StatusProcessingException> {
            processor().processStatus(id, EdiStatusEvent(ACKNOWLEDGED))
        }

        messageStateService.getMessageSnapshotById(id) shouldBe initial
        statusMessagePublisher.published shouldBe emptyList()
    }
})

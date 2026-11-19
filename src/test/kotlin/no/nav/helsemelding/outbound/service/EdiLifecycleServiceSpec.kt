package no.nav.helsemelding.outbound.service

import arrow.core.left
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.FakeEdiAdapterClient
import no.nav.helsemelding.outbound.FakeEdiMessagePublisher
import no.nav.helsemelding.outbound.LifecycleError
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.evaluator.AppRecTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.StateTransitionEvaluator
import no.nav.helsemelding.outbound.evaluator.TransportStatusTranslator
import no.nav.helsemelding.outbound.evaluator.TransportTransitionEvaluator
import no.nav.helsemelding.outbound.metrics.FakeMetrics
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.ExternalStatus
import no.nav.helsemelding.outbound.model.MessageStatus
import no.nav.helsemelding.outbound.model.MessageTransport
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.model.UpdateState
import no.nav.helsemelding.outbound.publisher.FakeStatusMessagePublisher
import no.nav.helsemelding.outbound.shouldBeLeftOfType
import kotlin.uuid.Uuid

class EdiLifecycleServiceSpec : StringSpec({
    val edi = config().edi
    val ediReceiverHerId = 701
    val transportRouter = MessageTransportRouter(listOf(ediReceiverHerId))
    val adapterReceiverHerId = 700
    val xml = messageXml(ediReceiverHerId)
    val adapterXml = messageXml(adapterReceiverHerId)
    lateinit var states: FakeTransactionalMessageStateService
    lateinit var adapter: FakeEdiAdapterClient
    lateinit var publisher: FakeEdiMessagePublisher
    lateinit var lifecycle: MessageLifecycleService

    fun createLifecycleService(): MessageLifecycleService = MessageLifecycleOrchestratorService(
        messageStateService = states,
        ediAdapterClient = adapter,
        metrics = FakeMetrics(),
        transportRouter = transportRouter,
        ediPublisher = publisher
    )

    beforeEach {
        states = FakeTransactionalMessageStateService()
        adapter = FakeEdiAdapterClient()
        publisher = FakeEdiMessagePublisher()
        lifecycle = createLifecycleService()
    }

    "configured receivers use edi with the lifecycle id and original XML" {
        val id = Uuid.random()
        val payload = xml.toByteArray()
        val snapshot = lifecycle.registerOutgoingMessage(id, payload).shouldBeRight()

        snapshot.messageState.transport shouldBe MessageTransport.EDI_1_0
        snapshot.messageState.externalRefId shouldBe id
        snapshot.messageState.publishedAt.shouldNotBeNull()
        snapshot.messageState.externalDeliveryState.shouldBeNull()
        snapshot.messageState.appRecStatus.shouldBeNull()
        snapshot.messageStateChanges.size shouldBe 1
        publisher.published.single().first shouldBe id
        publisher.published.single().second.toList() shouldBe payload.toList()
        adapter.sentMessages shouldBe emptyList()
    }

    "state exists before publication and an immediate final status survives publication marking" {
        val statusPublisher = FakeStatusMessagePublisher()
        val statuses = MessageStatusService(states, testStateEvaluator(), statusPublisher)
        val id = Uuid.random()
        publisher.beforePublish = { messageId, _ ->
            val registered = states.getMessageSnapshotById(messageId).shouldNotBeNull()
            registered.messageState.publishedAt.shouldBeNull()
            registered.messageState.externalRefId shouldBe id
            statusPublisher.published shouldBe emptyList()
            statuses.processStatus(
                registered.messageState,
                ExternalStatus(ExternalDeliveryState.ACKNOWLEDGED, AppRecStatus.OK)
            )
        }

        val snapshot = lifecycle.registerOutgoingMessage(id, xml.toByteArray()).shouldBeRight()

        snapshot.messageState.externalDeliveryState shouldBe ExternalDeliveryState.ACKNOWLEDGED
        snapshot.messageState.appRecStatus shouldBe AppRecStatus.OK
        snapshot.messageState.publishedAt.shouldNotBeNull()
        snapshot.messageStateChanges.size shouldBe 2
        statusPublisher.published.map { it.status } shouldBe listOf(MessageStatus.COMPLETED)
    }

    "failed publication stays unpublished and redelivery reuses the same id" {
        val id = Uuid.random()
        val failure = PublishError.Failure(id, edi.dialogMessageOutTopic, IllegalStateException("broker unavailable"))
        publisher.result = failure.left()
        lifecycle.registerOutgoingMessage(id, xml.toByteArray()).shouldBeLeftOfType<LifecycleError.EdiPublishFailure> {
            it.cause shouldBe failure
        }
        states.getMessageSnapshotById(id)!!.messageState.publishedAt.shouldBeNull()

        publisher.result = FakeEdiMessagePublisher().result
        val restarted = createLifecycleService()
        val snapshot = restarted.registerOutgoingMessage(id, xml.toByteArray()).shouldBeRight()

        publisher.published.map { it.first } shouldBe listOf(id, id)
        snapshot.messageState.transport shouldBe MessageTransport.EDI_1_0
        snapshot.messageState.externalRefId shouldBe id
        snapshot.messageState.publishedAt.shouldNotBeNull()
        snapshot.messageStateChanges.size shouldBe 1
        adapter.sentMessages shouldBe emptyList()
    }

    "registered edi messages keep their stored transport when the receiver now routes through the adapter" {
        val id = Uuid.random()
        val payload = adapterXml.toByteArray()
        transportRouter.route(listOf(adapterReceiverHerId)) shouldBe MessageTransport.EDI_2_0
        states.createInitialState(
            CreateState(id, id, DIALOG, transport = MessageTransport.EDI_1_0)
        ).shouldBeRight()

        val snapshot = lifecycle.registerOutgoingMessage(id, payload).shouldBeRight()

        snapshot.messageState.transport shouldBe MessageTransport.EDI_1_0
        snapshot.messageState.externalRefId shouldBe id
        snapshot.messageState.publishedAt.shouldNotBeNull()
        snapshot.messageStateChanges.size shouldBe 1
        publisher.published.single().first shouldBe id
        publisher.published.single().second.toList() shouldBe payload.toList()
        adapter.sentMessages shouldBe emptyList()
    }

    "published messages are not published again on redelivery" {
        val id = Uuid.random()
        val first = lifecycle.registerOutgoingMessage(id, xml.toByteArray()).shouldBeRight()
        lifecycle.registerOutgoingMessage(id, "not XML".toByteArray()).shouldBeRight() shouldBe first
        publisher.published.size shouldBe 1
    }

    "transport rejection prevents republication when publication was not marked" {
        val id = Uuid.random()
        states.createInitialState(
            CreateState(id, id, DIALOG, transport = MessageTransport.EDI_1_0)
        ).shouldBeRight()
        val rejected = states.recordStateChange(
            UpdateState(id, DIALOG, null, ExternalDeliveryState.REJECTED, null, null)
        )
        rejected.messageState.publishedAt.shouldBeNull()
        rejected.messageState.appRecStatus.shouldBeNull()

        lifecycle.registerOutgoingMessage(id, xml.toByteArray()).shouldBeRight() shouldBe rejected

        publisher.published shouldBe emptyList()
        adapter.sentMessages shouldBe emptyList()
    }

    "failed registration does not publish XML" {
        val id = Uuid.random()
        states.givenInitialState(id, LifecycleError.PersistenceFailure(id, "database unavailable").left())
        lifecycle.registerOutgoingMessage(id, xml.toByteArray()).shouldBeLeftOfType<LifecycleError.PersistenceFailure>()
        publisher.published shouldBe emptyList()
        adapter.sentMessages shouldBe emptyList()
    }
})

internal fun testStateEvaluator(): StateEvaluatorService = StateEvaluatorService(
    TransportStatusTranslator(),
    StateTransitionEvaluator(TransportTransitionEvaluator(), AppRecTransitionEvaluator())
)

private fun messageXml(receiverHerId: Int): String = """
    <MsgHead xmlns="http://www.kith.no/xmlstds/msghead/2006-05-24">
        <MsgInfo>
            <Type V="DIALOG_FORESPORSEL"/>
            <Sender><Organisation><Ident><Id>600</Id><TypeId V="HER"/></Ident></Organisation></Sender>
            <Receiver><Organisation><Ident><Id>$receiverHerId</Id><TypeId V="HER"/></Ident></Organisation></Receiver>
        </MsgInfo>
    </MsgHead>
""".trimIndent()

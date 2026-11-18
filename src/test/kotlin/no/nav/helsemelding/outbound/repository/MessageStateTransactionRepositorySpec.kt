package no.nav.helsemelding.outbound.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.LifecycleError.MessageNotFound
import no.nav.helsemelding.outbound.container
import no.nav.helsemelding.outbound.database
import no.nav.helsemelding.outbound.model.AppRecStatus.OK
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ABANDONED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.MessageTransport
import no.nav.helsemelding.outbound.model.MessageTransport.EDI_1_0
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.model.UpdateState
import no.nav.helsemelding.outbound.shouldBeInstant
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Clock
import kotlin.uuid.Uuid

class MessageStateTransactionRepositorySpec : StringSpec({
    lateinit var container: PostgreSQLContainer<Nothing>

    beforeEach {
        container = container()
        container.start()
    }

    "create initial state – all external states are null" {
        resourceScope {
            val database = database(container.jdbcUrl)

            val messageStateTransactionRepository = ExposedMessageStateTransactionRepository(
                database,
                ExposedMessageRepository(database),
                ExposedMessageStateHistoryRepository(database)
            )

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val now = Clock.System.now()

            val messageStateSnapshot = messageStateTransactionRepository.createInitialState(
                CreateState(
                    id = id,
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    occurredAt = now
                )
            )
                .shouldBeRight()

            val messageState = messageStateSnapshot.messageState

            messageState.id shouldBe id
            messageState.externalRefId shouldBe externalRefId
            messageState.messageType shouldBe DIALOG
            messageState.transport shouldBe MessageTransport.EDI_2_0
            messageState.publishedAt!! shouldBeInstant now

            messageState.externalDeliveryState shouldBe null
            messageState.appRecStatus shouldBe null

            messageStateSnapshot.messageStateChanges.size shouldBe 1
            val messageStateChange = messageStateSnapshot.messageStateChanges.first()

            messageStateChange.messageId shouldBe externalRefId
            messageStateChange.oldDeliveryState shouldBe null
            messageStateChange.newDeliveryState shouldBe null
            messageStateChange.oldAppRecStatus shouldBe null
            messageStateChange.newAppRecStatus shouldBe null
            messageStateChange.changedAt shouldBeInstant now
        }
    }

    "ABANDONED is preserved in PostgreSQL current state and history" {
        resourceScope {
            val database = database(container.jdbcUrl)
            val messages = ExposedMessageRepository(database)
            val history = ExposedMessageStateHistoryRepository(database)
            val transactions = ExposedMessageStateTransactionRepository(database, messages, history)
            val ref = Uuid.random()
            transactions.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()

            transactions.recordStateChange(UpdateState(ref, DIALOG, null, ABANDONED, null, null))

            messages.findByExternalReferenceId(ref)!!.externalDeliveryState shouldBe ABANDONED
            history.findAll(ref).single { it.newDeliveryState != null }.newDeliveryState shouldBe ABANDONED
        }
    }

    "record state change – with previous initial state" {
        resourceScope {
            val database = database(container.jdbcUrl)

            val messageStateTransactionRepository = ExposedMessageStateTransactionRepository(
                database,
                ExposedMessageRepository(database),
                ExposedMessageStateHistoryRepository(database)
            )

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val now = Clock.System.now()

            messageStateTransactionRepository.createInitialState(
                CreateState(
                    id = id,
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    occurredAt = now
                )
            )

            val messageStateSnapshot = messageStateTransactionRepository.recordStateChange(
                UpdateState(
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    oldDeliveryState = null,
                    newDeliveryState = ACKNOWLEDGED,
                    oldAppRecStatus = null,
                    newAppRecStatus = null,
                    occurredAt = now
                )
            )

            messageStateSnapshot.messageStateChanges.size shouldBe 2

            val messageStateChange = messageStateSnapshot.messageStateChanges.last()

            messageStateChange.messageId shouldBe externalRefId
            messageStateChange.oldDeliveryState shouldBe null
            messageStateChange.newDeliveryState shouldBe ACKNOWLEDGED

            messageStateChange.oldAppRecStatus shouldBe null
            messageStateChange.newAppRecStatus shouldBe null

            messageStateChange.changedAt shouldBeInstant now
        }
    }

    "record state change – preserves edi publication metadata without extra history entries" {
        resourceScope {
            val database = database(container.jdbcUrl)
            val messages = ExposedMessageRepository(database)
            val history = ExposedMessageStateHistoryRepository(database)
            val transactions = ExposedMessageStateTransactionRepository(database, messages, history)
            val id = Uuid.random()
            transactions.createInitialState(
                CreateState(id, id, DIALOG, transport = EDI_1_0, publishedAt = null)
            )
                .shouldBeRight()
            messages.findById(id)!!.publishedAt shouldBe null
            messages.findById(id)!!.transport shouldBe EDI_1_0

            transactions.recordStateChange(
                UpdateState(
                    id,
                    DIALOG,
                    null,
                    ACKNOWLEDGED,
                    null,
                    OK
                )
            )
            val publishedAt = Clock.System.now()
            messages.markPublished(id, publishedAt).shouldBeRight()

            val stored = messages.findById(id)!!
            stored.publishedAt!! shouldBeInstant publishedAt
            stored.transport shouldBe EDI_1_0
            stored.externalRefId shouldBe id
            stored.appRecStatus shouldBe OK
            history.findAll(id).size shouldBe 2
        }
    }

    "marking publication for an unknown message returns MessageNotFound" {
        resourceScope {
            val messages = ExposedMessageRepository(database(container.jdbcUrl))
            val id = Uuid.random()

            messages.markPublished(id, Clock.System.now()).shouldBeLeft(MessageNotFound(id))
        }
    }

    afterEach { container.stop() }
})

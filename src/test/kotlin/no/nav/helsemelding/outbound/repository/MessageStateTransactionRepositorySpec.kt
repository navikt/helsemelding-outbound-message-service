package no.nav.helsemelding.outbound.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.container
import no.nav.helsemelding.outbound.database
import no.nav.helsemelding.outbound.model.AppRecStatus.OK
import no.nav.helsemelding.outbound.model.CreateState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ABANDONED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.MessageTransport.EDI_1_0
import no.nav.helsemelding.outbound.model.MessageTransport.EDI_2_0
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
            messageState.transport shouldBe EDI_2_0
            messageState.publishedAt.shouldBeNull()

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
            val messageRepository = ExposedMessageRepository(database)
            val historyRepository = ExposedMessageStateHistoryRepository(database)
            val transactionRepository = ExposedMessageStateTransactionRepository(
                database,
                messageRepository,
                historyRepository
            )
            val ref = Uuid.random()
            transactionRepository.createInitialState(CreateState(Uuid.random(), ref, DIALOG)).shouldBeRight()

            transactionRepository.recordStateChange(UpdateState(ref, DIALOG, null, ABANDONED, null, null))

            messageRepository.findByExternalReferenceId(ref)!!.externalDeliveryState shouldBe ABANDONED
            historyRepository.findAll(ref).single { it.newDeliveryState != null }.newDeliveryState shouldBe ABANDONED
        }
    }

    "record state change – with previous initial state" {
        resourceScope {
            val database = database(container.jdbcUrl)

            val transactionRepository = ExposedMessageStateTransactionRepository(
                database,
                ExposedMessageRepository(database),
                ExposedMessageStateHistoryRepository(database)
            )

            val id = Uuid.random()
            val externalRefId = Uuid.random()
            val now = Clock.System.now()

            transactionRepository.createInitialState(
                CreateState(
                    id = id,
                    externalRefId = externalRefId,
                    messageType = DIALOG,
                    occurredAt = now
                )
            )

            val messageStateSnapshot = transactionRepository.recordStateChange(
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
            val messageRepository = ExposedMessageRepository(database)
            val historyRepository = ExposedMessageStateHistoryRepository(database)
            val transactionRepository = ExposedMessageStateTransactionRepository(
                database,
                messageRepository,
                historyRepository
            )
            val id = Uuid.random()
            transactionRepository.createInitialState(
                CreateState(id, id, DIALOG, transport = EDI_1_0)
            )
                .shouldBeRight()
            messageRepository.findById(id)!!.publishedAt shouldBe null
            messageRepository.findById(id)!!.transport shouldBe EDI_1_0

            transactionRepository.recordStateChange(
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
            messageRepository.markPublished(id, publishedAt).shouldBeRight()

            val storedMessage = messageRepository.findById(id)!!
            storedMessage.publishedAt!! shouldBeInstant publishedAt
            storedMessage.transport shouldBe EDI_1_0
            storedMessage.externalRefId shouldBe id
            storedMessage.appRecStatus shouldBe OK
            historyRepository.findAll(id).size shouldBe 2
        }
    }

    afterEach { container.stop() }
})

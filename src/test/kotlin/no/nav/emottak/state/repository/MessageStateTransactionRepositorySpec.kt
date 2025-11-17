package no.nav.emottak.state.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.emottak.state.container
import no.nav.emottak.state.database
import no.nav.emottak.state.model.MessageDeliveryState.NEW
import no.nav.emottak.state.model.MessageDeliveryState.PROCESSED
import no.nav.emottak.state.model.MessageType.DIALOG
import no.nav.emottak.state.shouldBeInstant
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MESSAGE = "http://exmaple.com/messages/1"

class MessageStateTransactionRepositorySpec : StringSpec(
    {
        lateinit var container: PostgreSQLContainer<Nothing>

        beforeEach {
            container = container()
            container.start()
        }

        "Record state change - without previous transactions" {
            resourceScope {
                val database = database(container.jdbcUrl)
                val messageStateTransactionRepository = ExposedMessageStateTransactionRepository(
                    database,
                    ExposedMessageRepository(database),
                    ExposedMessageStateHistoryRepository(database)
                )

                val externalRefId = Uuid.random()
                val externalMessageUrl = URI.create(MESSAGE).toURL()
                val now = Clock.System.now()
                val snapshot = messageStateTransactionRepository.createInitialState(
                    DIALOG,
                    NEW,
                    externalRefId,
                    externalMessageUrl,
                    now
                )

                snapshot.messageState.messageType shouldBe DIALOG
                snapshot.messageState.currentState shouldBe NEW
                snapshot.messageState.externalRefId shouldBe externalRefId
                snapshot.messageState.externalMessageUrl shouldBe externalMessageUrl

                snapshot.messageStateChange.size shouldBe 1

                val stateChange = snapshot.messageStateChange.first()

                stateChange.messageId shouldBe externalRefId
                stateChange.oldState shouldBe null
                stateChange.newState shouldBe NEW
                stateChange.changedAt shouldBeInstant now
            }
        }

        "Record state change - with previous transactions" {
            resourceScope {
                val database = database(container.jdbcUrl)
                val messageStateTransactionRepository = ExposedMessageStateTransactionRepository(
                    database,
                    ExposedMessageRepository(database),
                    ExposedMessageStateHistoryRepository(database)
                )

                val externalRefId = Uuid.random()
                val externalMessageUrl = URI.create(MESSAGE).toURL()
                val now = Clock.System.now()

                messageStateTransactionRepository.createInitialState(
                    DIALOG,
                    NEW,
                    externalRefId,
                    externalMessageUrl,
                    now
                )

                val snapshot = messageStateTransactionRepository.recordStateChange(
                    DIALOG,
                    NEW,
                    PROCESSED,
                    externalRefId,
                    now
                )

                snapshot.messageStateChange.size shouldBe 2

                val stateChange = snapshot.messageStateChange.last()

                stateChange.messageId shouldBe externalRefId
                stateChange.oldState shouldBe NEW
                stateChange.newState shouldBe PROCESSED
                stateChange.changedAt shouldBeInstant now
            }
        }

        afterEach { container.stop() }
    }
)

package no.nav.helsemelding.state.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.container
import no.nav.helsemelding.state.database
import no.nav.helsemelding.state.model.CreateState
import no.nav.helsemelding.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.state.model.MessageType.DIALOG
import no.nav.helsemelding.state.model.UpdateState
import no.nav.helsemelding.state.shouldBeInstant
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

        "Create initial state – all external states are null" {
            resourceScope {
                val database = database(container.jdbcUrl)

                val messageStateTransactionRepository = ExposedMessageStateTransactionRepository(
                    database,
                    ExposedMessageRepository(database),
                    ExposedMessageStateHistoryRepository(database)
                )

                val id = Uuid.random()
                val externalRefId = Uuid.random()
                val url = URI.create(MESSAGE).toURL()
                val now = Clock.System.now()

                val messageStateSnapshot = messageStateTransactionRepository.createInitialState(
                    CreateState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        externalMessageUrl = url,
                        occurredAt = now
                    )
                )

                val messageState = messageStateSnapshot.messageState

                messageState.id shouldBe id
                messageState.externalRefId shouldBe externalRefId
                messageState.messageType shouldBe DIALOG
                messageState.externalMessageUrl shouldBe url

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

        "Record state change – with previous initial state" {
            resourceScope {
                val database = database(container.jdbcUrl)

                val messageStateTransactionRepository = ExposedMessageStateTransactionRepository(
                    database,
                    ExposedMessageRepository(database),
                    ExposedMessageStateHistoryRepository(database)
                )

                val id = Uuid.random()
                val externalRefId = Uuid.random()
                val externalMessageUrl = URI.create(MESSAGE).toURL()
                val now = Clock.System.now()

                messageStateTransactionRepository.createInitialState(
                    CreateState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        externalMessageUrl = externalMessageUrl,
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

        afterEach { container.stop() }
    }
)

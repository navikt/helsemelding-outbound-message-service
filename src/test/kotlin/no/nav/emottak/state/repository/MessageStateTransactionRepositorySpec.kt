package no.nav.emottak.state.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.emottak.state.container
import no.nav.emottak.state.database
import no.nav.emottak.state.model.CreateState
import no.nav.emottak.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.emottak.state.model.MessageType.DIALOG
import no.nav.emottak.state.model.UpdateState
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

        "Record state change – initial creation has null external states" {
            resourceScope {
                val database = database(container.jdbcUrl)

                val messageStateTransactionRepository = ExposedMessageStateTransactionRepository(
                    database,
                    ExposedMessageRepository(database),
                    ExposedMessageStateHistoryRepository(database)
                )

                val externalRefId = Uuid.random()
                val url = URI.create(MESSAGE).toURL()
                val now = Clock.System.now()

                val snapshot = messageStateTransactionRepository.createInitialState(
                    CreateState(
                        messageType = DIALOG,
                        externalRefId = externalRefId,
                        externalMessageUrl = url,
                        occurredAt = now
                    )
                )

                val messageState = snapshot.messageState

                messageState.messageType shouldBe DIALOG
                messageState.externalRefId shouldBe externalRefId
                messageState.externalMessageUrl shouldBe url

                messageState.externalDeliveryState shouldBe null
                messageState.appRecStatus shouldBe null

                snapshot.messageStateChange.size shouldBe 1
                val entry = snapshot.messageStateChange.first()

                entry.messageId shouldBe externalRefId
                entry.oldDeliveryState shouldBe null
                entry.newDeliveryState shouldBe null
                entry.oldAppRecStatus shouldBe null
                entry.newAppRecStatus shouldBe null
                entry.changedAt shouldBeInstant now
            }
        }

        "Record state change – with previous transactions" {
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
                    CreateState(
                        messageType = DIALOG,
                        externalRefId = externalRefId,
                        externalMessageUrl = externalMessageUrl,
                        occurredAt = now
                    )
                )

                val snapshot = messageStateTransactionRepository.recordStateChange(
                    UpdateState(
                        messageType = DIALOG,
                        externalRefId = externalRefId,
                        oldDeliveryState = null,
                        newDeliveryState = ACKNOWLEDGED,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        occurredAt = now
                    )
                )

                snapshot.messageStateChange.size shouldBe 2

                val entry = snapshot.messageStateChange.last()

                entry.messageId shouldBe externalRefId
                entry.oldDeliveryState shouldBe null
                entry.newDeliveryState shouldBe ACKNOWLEDGED

                entry.oldAppRecStatus shouldBe null
                entry.newAppRecStatus shouldBe null

                entry.changedAt shouldBeInstant now
            }
        }

        afterEach { container.stop() }
    }
)

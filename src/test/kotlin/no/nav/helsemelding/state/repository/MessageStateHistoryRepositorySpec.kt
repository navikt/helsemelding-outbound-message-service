package no.nav.helsemelding.state.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.helsemelding.state.container
import no.nav.helsemelding.state.database
import no.nav.helsemelding.state.model.AppRecStatus.OK
import no.nav.helsemelding.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.state.model.ExternalDeliveryState.UNCONFIRMED
import no.nav.helsemelding.state.model.MessageType.DIALOG
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MESSAGE = "http://exmaple.com/messages/1"

class MessageStateHistoryRepositorySpec : StringSpec(
    {

        lateinit var container: PostgreSQLContainer<Nothing>

        beforeEach {
            container = container()
            container.start()
        }

        "Append history – fails when messageId does not exist" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageStateHistoryRepository = ExposedMessageStateHistoryRepository(database)

                    val exception = shouldThrow<ExposedSQLException> {
                        messageStateHistoryRepository.append(
                            messageId = Uuid.random(),
                            oldDeliveryState = null,
                            newDeliveryState = ACKNOWLEDGED,
                            oldAppRecStatus = null,
                            newAppRecStatus = null,
                            changedAt = Clock.System.now()
                        )
                    }

                    exception.cause.shouldBeInstanceOf<PSQLException>()
                    exception.cause?.message shouldStartWith ("ERROR: insert or update on table")
                }
            }
        }

        "Append history – first entry" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)
                    val messageStateHistoryRepository = ExposedMessageStateHistoryRepository(database)

                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE).toURL()
                    val occurredAt = Clock.System.now()

                    messageRepository.createState(
                        messageType = DIALOG,
                        externalRefId = externalRefId,
                        externalMessageUrl = externalMessageUrl,
                        lastStateChange = occurredAt
                    )

                    val history = messageStateHistoryRepository.append(
                        messageId = externalRefId,
                        oldDeliveryState = null,
                        newDeliveryState = UNCONFIRMED,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = occurredAt
                    )

                    history.size shouldBe 1
                    val entry = history.first()

                    entry.messageId shouldBe externalRefId
                    entry.oldDeliveryState shouldBe null
                    entry.newDeliveryState shouldBe UNCONFIRMED
                    entry.oldAppRecStatus shouldBe null
                    entry.newAppRecStatus shouldBe null
                }
            }
        }

        "Append history – subsequent entries" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)
                    val messageStateHistoryRepository = ExposedMessageStateHistoryRepository(database)

                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE).toURL()
                    val now = Clock.System.now()

                    messageRepository.createState(DIALOG, externalRefId, externalMessageUrl, now)

                    messageStateHistoryRepository.append(
                        messageId = externalRefId,
                        oldDeliveryState = null,
                        newDeliveryState = UNCONFIRMED,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = now
                    )

                    val next = Clock.System.now()
                    val history = messageStateHistoryRepository.append(
                        messageId = externalRefId,
                        oldDeliveryState = UNCONFIRMED,
                        newDeliveryState = ACKNOWLEDGED,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = next
                    )

                    history.size shouldBe 2

                    val last = history.last()
                    last.messageId shouldBe externalRefId
                    last.oldDeliveryState shouldBe UNCONFIRMED
                    last.newDeliveryState shouldBe ACKNOWLEDGED
                    last.oldAppRecStatus shouldBe null
                    last.newAppRecStatus shouldBe null
                }
            }
        }

        "Find all – returns empty list" {
            resourceScope {
                val database = database(container.jdbcUrl)
                val messageStateHistoryRepository = ExposedMessageStateHistoryRepository(database)

                val list = messageStateHistoryRepository.findAll(Uuid.random())
                list shouldBe emptyList()
            }
        }

        "Find all – returns full history list" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)
                    val messageStateHistoryRepository = ExposedMessageStateHistoryRepository(database)

                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE).toURL()
                    val now = Clock.System.now()

                    messageRepository.createState(DIALOG, externalRefId, externalMessageUrl, now)

                    messageStateHistoryRepository.append(
                        externalRefId,
                        oldDeliveryState = null,
                        newDeliveryState = UNCONFIRMED,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = now
                    )

                    val t2 = Clock.System.now()
                    messageStateHistoryRepository.append(
                        externalRefId,
                        oldDeliveryState = UNCONFIRMED,
                        newDeliveryState = ACKNOWLEDGED,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = t2
                    )

                    val t3 = Clock.System.now()
                    messageStateHistoryRepository.append(
                        messageId = externalRefId,
                        oldDeliveryState = ACKNOWLEDGED,
                        newDeliveryState = ACKNOWLEDGED,
                        oldAppRecStatus = null,
                        newAppRecStatus = OK,
                        changedAt = t3
                    )

                    messageStateHistoryRepository.findAll(externalRefId).size shouldBe 3
                }
            }
        }

        afterEach { container.stop() }
    }
)

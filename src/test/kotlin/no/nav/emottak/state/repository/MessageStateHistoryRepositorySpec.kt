package no.nav.emottak.state.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.emottak.state.container
import no.nav.emottak.state.database
import no.nav.emottak.state.model.AppRecStatus.Ok
import no.nav.emottak.state.model.ExternalDeliveryState.Acknowledged
import no.nav.emottak.state.model.ExternalDeliveryState.Unconfirmed
import no.nav.emottak.state.model.MessageType.DIALOG
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
                    val repo = ExposedMessageStateHistoryRepository(database)

                    val exception = shouldThrow<ExposedSQLException> {
                        repo.append(
                            messageId = Uuid.random(),
                            oldDeliveryState = null,
                            newDeliveryState = Acknowledged,
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
                    val messageRepo = ExposedMessageRepository(database)
                    val historyRepo = ExposedMessageStateHistoryRepository(database)

                    val refId = Uuid.random()
                    val url = URI.create(MESSAGE).toURL()
                    val occurredAt = Clock.System.now()

                    messageRepo.create(
                        messageType = DIALOG,
                        externalRefId = refId,
                        externalMessageUrl = url,
                        lastStateChange = occurredAt
                    )

                    val history = historyRepo.append(
                        messageId = refId,
                        oldDeliveryState = null,
                        newDeliveryState = Unconfirmed,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = occurredAt
                    )

                    history.size shouldBe 1
                    val entry = history.first()

                    entry.messageId shouldBe refId
                    entry.oldDeliveryState shouldBe null
                    entry.newDeliveryState shouldBe Unconfirmed
                    entry.oldAppRecStatus shouldBe null
                    entry.newAppRecStatus shouldBe null
                }
            }
        }

        "Append history – subsequent entries" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepo = ExposedMessageRepository(database)
                    val historyRepo = ExposedMessageStateHistoryRepository(database)

                    val refId = Uuid.random()
                    val url = URI.create(MESSAGE).toURL()
                    val now = Clock.System.now()

                    messageRepo.create(DIALOG, refId, url, now)

                    historyRepo.append(
                        messageId = refId,
                        oldDeliveryState = null,
                        newDeliveryState = Unconfirmed,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = now
                    )

                    val next = Clock.System.now()
                    val history = historyRepo.append(
                        messageId = refId,
                        oldDeliveryState = Unconfirmed,
                        newDeliveryState = Acknowledged,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = next
                    )

                    history.size shouldBe 2

                    val last = history.last()
                    last.messageId shouldBe refId
                    last.oldDeliveryState shouldBe Unconfirmed
                    last.newDeliveryState shouldBe Acknowledged
                    last.oldAppRecStatus shouldBe null
                    last.newAppRecStatus shouldBe null
                }
            }
        }

        "Find all – empty" {
            resourceScope {
                val database = database(container.jdbcUrl)
                val historyRepo = ExposedMessageStateHistoryRepository(database)

                val list = historyRepo.findAll(Uuid.random())
                list shouldBe emptyList()
            }
        }

        "Find all – returns full history list" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)
                    val messageStateHistoryRepository = ExposedMessageStateHistoryRepository(database)

                    val refId = Uuid.random()
                    val url = URI.create(MESSAGE).toURL()
                    val now = Clock.System.now()

                    messageRepository.create(DIALOG, refId, url, now)

                    messageStateHistoryRepository.append(
                        refId,
                        oldDeliveryState = null,
                        newDeliveryState = Unconfirmed,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = now
                    )

                    val t2 = Clock.System.now()
                    messageStateHistoryRepository.append(
                        refId,
                        oldDeliveryState = Unconfirmed,
                        newDeliveryState = Acknowledged,
                        oldAppRecStatus = null,
                        newAppRecStatus = null,
                        changedAt = t2
                    )

                    val t3 = Clock.System.now()
                    messageStateHistoryRepository.append(
                        messageId = refId,
                        oldDeliveryState = Acknowledged,
                        newDeliveryState = Acknowledged,
                        oldAppRecStatus = null,
                        newAppRecStatus = Ok,
                        changedAt = t3
                    )

                    messageStateHistoryRepository.findAll(refId).size shouldBe 3
                }
            }
        }

        afterEach { container.stop() }
    }
)

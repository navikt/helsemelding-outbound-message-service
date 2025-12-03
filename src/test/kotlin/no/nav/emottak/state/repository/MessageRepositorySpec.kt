package no.nav.emottak.state.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.emottak.state.container
import no.nav.emottak.state.database
import no.nav.emottak.state.model.AppRecStatus
import no.nav.emottak.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.emottak.state.model.ExternalDeliveryState.REJECTED
import no.nav.emottak.state.model.ExternalDeliveryState.UNCONFIRMED
import no.nav.emottak.state.model.MessageType.DIALOG
import no.nav.emottak.state.repository.Messages.externalRefId
import no.nav.emottak.state.repository.Messages.lastPolledAt
import no.nav.emottak.state.shouldBeInstant
import no.nav.emottak.state.util.olderThanSeconds
import no.nav.emottak.state.util.toSql
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

private const val MESSAGE1 = "http://exmaple.com/messages/1"
private const val MESSAGE2 = "http://exmaple.com/messages/2"
private const val MESSAGE3 = "http://exmaple.com/messages/3"
private const val MESSAGE4 = "http://exmaple.com/messages/4"
private const val MESSAGE5 = "http://exmaple.com/messages/5"

class MessageRepositorySpec : StringSpec(
    {

        lateinit var container: PostgreSQLContainer<Nothing>

        beforeEach {
            container = container()
            container.start()
        }

        "Create state - no existing message" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()
                    val now = Clock.System.now()

                    val state = messageRepository.createState(
                        messageType = DIALOG,
                        externalRefId = externalRefId,
                        externalMessageUrl = externalMessageUrl,
                        lastStateChange = now
                    )

                    state.messageType shouldBe DIALOG
                    state.externalRefId shouldBe externalRefId
                    state.externalMessageUrl shouldBe externalMessageUrl
                    state.lastStateChange shouldBeInstant now

                    state.externalDeliveryState shouldBe null
                    state.appRecStatus shouldBe null
                }
            }
        }

        "Update state - existing message" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()

                    messageRepository.createState(
                        messageType = DIALOG,
                        externalRefId = externalRefId,
                        externalMessageUrl = externalMessageUrl,
                        lastStateChange = Clock.System.now()
                    )

                    val updatedAt = Clock.System.now()

                    val newState = messageRepository.updateState(
                        externalRefId = externalRefId,
                        externalDeliveryState = ACKNOWLEDGED,
                        appRecStatus = null,
                        lastStateChange = updatedAt
                    )

                    newState.externalDeliveryState shouldBe ACKNOWLEDGED
                    newState.appRecStatus shouldBe null
                    newState.lastStateChange shouldBeInstant updatedAt
                }
            }
        }

        "Find or null - no value found" {
            resourceScope {
                val database = database(container.jdbcUrl)
                val messageRepository = ExposedMessageRepository(database)

                val externalRefId = Uuid.random()
                messageRepository.findOrNull(externalRefId) shouldBe null
            }
        }

        "Find or null - value found" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()

                    messageRepository.createState(
                        messageType = DIALOG,
                        externalRefId = externalRefId,
                        externalMessageUrl = externalMessageUrl,
                        lastStateChange = Clock.System.now()
                    )

                    messageRepository.findOrNull(externalRefId)!!.externalRefId shouldBe externalRefId
                }
            }
        }

        "Find for polling - generate correct sql" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val expr = lastPolledAt.olderThanSeconds(Duration.parse("30s"))
                    expr.toSql() shouldBe "messages.last_polled_at <= (NOW() - INTERVAL '30 seconds')"
                }
            }
        }

        "Find for polling - empty list (no values stored)" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)
                    messageRepository.findForPolling() shouldBe emptyList()
                }
            }
        }

        "Find for polling - only messages with NULL, ACKNOWLEDGED, or UNCONFIRMED delivery state" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    messageRepository.createState(
                        DIALOG,
                        Uuid.random(),
                        URI.create(MESSAGE1).toURL(),
                        Clock.System.now()
                    )

                    messageRepository.createState(
                        DIALOG,
                        Uuid.random(),
                        URI.create(MESSAGE2).toURL(),
                        Clock.System.now()
                    )
                        .also {
                            Messages.update({ externalRefId eq it.externalRefId }) { row ->
                                row[externalDeliveryState] = ACKNOWLEDGED
                            }
                        }

                    messageRepository.createState(
                        DIALOG,
                        Uuid.random(),
                        URI.create(MESSAGE3).toURL(),
                        Clock.System.now()
                    )
                        .also {
                            Messages.update({ externalRefId eq it.externalRefId }) { row ->
                                row[externalDeliveryState] = UNCONFIRMED
                            }
                        }

                    messageRepository.createState(
                        DIALOG,
                        Uuid.random(),
                        URI.create(MESSAGE4).toURL(),
                        Clock.System.now()
                    )
                        .also {
                            Messages.update({ externalRefId eq it.externalRefId }) { row ->
                                row[externalDeliveryState] = ACKNOWLEDGED
                                row[appRecStatus] = AppRecStatus.OK
                            }
                        }

                    messageRepository.createState(
                        DIALOG,
                        Uuid.random(),
                        URI.create(MESSAGE5).toURL(),
                        Clock.System.now()
                    )
                        .also {
                            Messages.update({ externalRefId eq it.externalRefId }) { row ->
                                row[externalDeliveryState] = REJECTED
                            }
                        }

                    messageRepository.findForPolling().size shouldBe 3
                }
            }
        }

        "Find for polling - only messages older than threshold" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)
                    val now = Clock.System.now()

                    val oldExternalRefId = Uuid.random()
                    val newExternalRefId = Uuid.random()

                    messageRepository.createState(DIALOG, oldExternalRefId, URI.create(MESSAGE1).toURL(), now)
                    messageRepository.createState(DIALOG, newExternalRefId, URI.create(MESSAGE2).toURL(), now)

                    Messages.update({ externalRefId eq oldExternalRefId }) {
                        it[lastPolledAt] = now - Duration.parse("31s")
                    }

                    Messages.update({ externalRefId eq newExternalRefId }) {
                        it[lastPolledAt] = now - Duration.parse("5s")
                    }

                    val pollingExternalRefIds = messageRepository.findForPolling().map { it.externalRefId }
                    pollingExternalRefIds shouldContain oldExternalRefId
                    pollingExternalRefIds shouldNotContain newExternalRefId
                }
            }
        }

        "Find for polling - messages with null for last polled at are included" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)
                    val now = Clock.System.now()

                    val never = Uuid.random()
                    val recent = Uuid.random()

                    messageRepository.createState(DIALOG, never, URI.create(MESSAGE1).toURL(), now)
                    messageRepository.createState(DIALOG, recent, URI.create(MESSAGE2).toURL(), now)

                    Messages.update({ externalRefId eq recent }) {
                        it[lastPolledAt] = now
                    }

                    val externalRefIds = messageRepository.findForPolling().map { it.externalRefId }
                    externalRefIds shouldContain never
                    externalRefIds shouldNotContain recent
                }
            }
        }

        "Mark polled - update only selected id's" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val externalRefId1 = Uuid.random()
                    val externalRefId2 = Uuid.random()
                    val externalRefId3 = Uuid.random()
                    val now = Clock.System.now()

                    messageRepository.createState(DIALOG, externalRefId1, URI.create(MESSAGE1).toURL(), now)
                    messageRepository.createState(DIALOG, externalRefId2, URI.create(MESSAGE2).toURL(), now)
                    messageRepository.createState(DIALOG, externalRefId3, URI.create(MESSAGE3).toURL(), now)

                    messageRepository.markPolled(listOf(externalRefId1, externalRefId2)) shouldBe 2

                    messageRepository.findOrNull(externalRefId1)!!.lastPolledAt shouldNotBe null
                    messageRepository.findOrNull(externalRefId2)!!.lastPolledAt shouldNotBe null

                    messageRepository.findOrNull(externalRefId3)!!.lastPolledAt shouldBe null
                }
            }
        }

        afterEach { container.stop() }
    }
)

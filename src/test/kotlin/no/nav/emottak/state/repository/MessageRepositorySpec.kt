package no.nav.emottak.state.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.emottak.state.container
import no.nav.emottak.state.database
import no.nav.emottak.state.model.ExternalDeliveryState.Acknowledged
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

class MessageRepositorySpec : StringSpec(
    {

        lateinit var container: PostgreSQLContainer<Nothing>

        beforeEach {
            container = container()
            container.start()
        }

        "Create - no existing message" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val repo = ExposedMessageRepository(database)

                    val externalRefId = Uuid.random()
                    val externalUrl = URI.create(MESSAGE1).toURL()
                    val now = Clock.System.now()

                    val state = repo.create(
                        messageType = DIALOG,
                        externalRefId = externalRefId,
                        externalMessageUrl = externalUrl,
                        lastStateChange = now
                    )

                    state.messageType shouldBe DIALOG
                    state.externalRefId shouldBe externalRefId
                    state.externalMessageUrl shouldBe externalUrl
                    state.lastStateChange shouldBeInstant now

                    state.externalDeliveryState shouldBe null
                    state.appRecStatus shouldBe null
                }
            }
        }

        "Update external state - existing message" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val repo = ExposedMessageRepository(database)

                    val externalRefId = Uuid.random()
                    val externalUrl = URI.create(MESSAGE1).toURL()

                    repo.create(
                        messageType = DIALOG,
                        externalRefId = externalRefId,
                        externalMessageUrl = externalUrl,
                        lastStateChange = Clock.System.now()
                    )

                    val updatedAt = Clock.System.now()

                    val newState = repo.updateState(
                        externalRefId = externalRefId,
                        externalDeliveryState = Acknowledged,
                        appRecStatus = null,
                        lastStateChange = updatedAt
                    )

                    newState.externalDeliveryState shouldBe Acknowledged
                    newState.appRecStatus shouldBe null
                    newState.lastStateChange shouldBeInstant updatedAt
                }
            }
        }

        "Find or null - no value found" {
            resourceScope {
                val database = database(container.jdbcUrl)
                val repo = ExposedMessageRepository(database)

                val ref = Uuid.random()
                repo.findOrNull(ref) shouldBe null
            }
        }

        "Find or null - value found" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val repo = ExposedMessageRepository(database)

                    val ref = Uuid.random()
                    val url = URI.create(MESSAGE1).toURL()

                    repo.create(
                        messageType = DIALOG,
                        externalRefId = ref,
                        externalMessageUrl = url,
                        lastStateChange = Clock.System.now()
                    )

                    repo.findOrNull(ref)!!.externalRefId shouldBe ref
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
                    val repo = ExposedMessageRepository(database)
                    repo.findForPolling() shouldBe emptyList()
                }
            }
        }

        "Find for polling - only messages with externalDeliveryState = NULL" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    messageRepository.create(
                        DIALOG,
                        Uuid.random(),
                        URI.create(MESSAGE1).toURL(),
                        Clock.System.now()
                    )
                        .also {
                            Messages.update({ externalRefId eq it.externalRefId }) { row ->
                                row[externalDeliveryState] = Acknowledged
                            }
                        }

                    messageRepository.create(
                        DIALOG,
                        Uuid.random(),
                        URI.create(MESSAGE2).toURL(),
                        Clock.System.now()
                    )

                    messageRepository.findForPolling().size shouldBe 1
                }
            }
        }

        "Find for polling - only messages older than threshold" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)
                    val now = Clock.System.now()

                    val oldRef = Uuid.random()
                    val ngRef = Uuid.random()

                    messageRepository.create(DIALOG, oldRef, URI.create(MESSAGE1).toURL(), now)
                    messageRepository.create(DIALOG, ngRef, URI.create(MESSAGE2).toURL(), now)

                    Messages.update({ externalRefId eq oldRef }) {
                        it[lastPolledAt] = now - Duration.parse("31s")
                    }

                    Messages.update({ externalRefId eq ngRef }) {
                        it[lastPolledAt] = now - Duration.parse("5s")
                    }

                    val pollingRefs = messageRepository.findForPolling().map { it.externalRefId }
                    pollingRefs shouldContain oldRef
                    pollingRefs shouldNotContain ngRef
                }
            }
        }

        "Find for polling - null lastPolledAt messages are included" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)
                    val now = Clock.System.now()

                    val never = Uuid.random()
                    val recent = Uuid.random()

                    messageRepository.create(DIALOG, never, URI.create(MESSAGE1).toURL(), now)
                    messageRepository.create(DIALOG, recent, URI.create(MESSAGE2).toURL(), now)

                    Messages.update({ externalRefId eq recent }) {
                        it[lastPolledAt] = now
                    }

                    val refs = messageRepository.findForPolling().map { it.externalRefId }
                    refs shouldContain never
                    refs shouldNotContain recent
                }
            }
        }

        "Mark Polled - updates only selected IDs" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val ref1 = Uuid.random()
                    val ref2 = Uuid.random()
                    val ref3 = Uuid.random()
                    val now = Clock.System.now()

                    messageRepository.create(DIALOG, ref1, URI.create(MESSAGE1).toURL(), now)
                    messageRepository.create(DIALOG, ref2, URI.create(MESSAGE2).toURL(), now)
                    messageRepository.create(DIALOG, ref3, URI.create(MESSAGE3).toURL(), now)

                    messageRepository.markPolled(listOf(ref1, ref2)) shouldBe 2

                    messageRepository.findOrNull(ref1)!!.lastPolledAt shouldNotBe null
                    messageRepository.findOrNull(ref2)!!.lastPolledAt shouldNotBe null

                    messageRepository.findOrNull(ref3)!!.lastPolledAt shouldBe null
                }
            }
        }

        afterEach { container.stop() }
    }
)

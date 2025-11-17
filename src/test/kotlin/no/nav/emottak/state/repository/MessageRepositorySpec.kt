package no.nav.emottak.state.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.emottak.state.container
import no.nav.emottak.state.database
import no.nav.emottak.state.model.MessageDeliveryState.NEW
import no.nav.emottak.state.model.MessageDeliveryState.PROCESSED
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

        "Upsert state - no existing message" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()
                    val now = Clock.System.now()
                    val messageState = messageRepository.createState(
                        DIALOG,
                        NEW,
                        externalRefId,
                        externalMessageUrl,
                        now
                    )

                    messageState.messageType shouldBe DIALOG
                    messageState.currentState shouldBe NEW
                    messageState.externalRefId shouldBe externalRefId
                    messageState.externalMessageUrl shouldBe externalMessageUrl
                    messageState.lastStateChange shouldBeInstant now
                }
            }
        }

        "Upsert state - existing message" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()
                    val now = Clock.System.now()

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        externalRefId,
                        externalMessageUrl,
                        now
                    )

                    val stateChanged = Clock.System.now()
                    val messageState = messageRepository.updateState(
                        DIALOG,
                        PROCESSED,
                        externalRefId,
                        stateChanged
                    )

                    messageState.messageType shouldBe DIALOG
                    messageState.currentState shouldBe PROCESSED
                    messageState.externalRefId shouldBe externalRefId
                    messageState.externalMessageUrl shouldBe externalMessageUrl
                    messageState.lastStateChange shouldBeInstant stateChanged
                }
            }
        }

        "Find or null - no value found" {
            resourceScope {
                val database = database(container.jdbcUrl)
                val messageRepository = ExposedMessageRepository(database)

                val referenceId = Uuid.random()
                messageRepository.findOrNull(referenceId) shouldBe null
            }
        }

        "Find or null - value found" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val referenceId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()
                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        referenceId,
                        externalMessageUrl,
                        Clock.System.now()
                    )

                    val messageState = messageRepository.findOrNull(referenceId)
                    messageState?.externalRefId shouldBe referenceId
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

        "Find for polling - empty list (no NEW states stored)" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    messageRepository.createState(
                        DIALOG,
                        PROCESSED,
                        Uuid.random(),
                        URI.create(MESSAGE1).toURL(),
                        Clock.System.now()
                    )

                    messageRepository.createState(
                        DIALOG,
                        PROCESSED,
                        Uuid.random(),
                        URI.create(MESSAGE2).toURL(),
                        Clock.System.now()
                    )

                    messageRepository.findForPolling() shouldBe emptyList()
                }
            }
        }

        "Find for polling - values found" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        Uuid.random(),
                        URI.create(MESSAGE1).toURL(),
                        Clock.System.now()
                    )

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        Uuid.random(),
                        URI.create(MESSAGE2).toURL(),
                        Clock.System.now()
                    )

                    messageRepository.findForPolling().size shouldBe 2
                }
            }
        }

        "Find for polling - only messages older than threshold are returned" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val now = Clock.System.now()

                    val oldRef = Uuid.random()
                    val recentRef = Uuid.random()

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        oldRef,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        recentRef,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )

                    Messages.update({ externalRefId eq oldRef }) { row ->
                        row[lastPolledAt] = now - Duration.parse("31s") // older than 30s
                    }

                    Messages.update({ externalRefId eq recentRef }) { row ->
                        row[lastPolledAt] = now - Duration.parse("5s") // more recent than 30s
                    }

                    val pollables = messageRepository.findForPolling()
                    val pollableRefs = pollables.map { it.externalRefId }

                    pollableRefs shouldContain oldRef
                    pollableRefs shouldNotContain recentRef
                }
            }
        }

        "Find for polling - messages never polled (null lastPolledAt) are included" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val now = Clock.System.now()

                    val refNeverPolled = Uuid.random()
                    val refRecentlyPolled = Uuid.random()

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        refNeverPolled,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        refRecentlyPolled,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )

                    Messages.update({ externalRefId eq refRecentlyPolled }) { row ->
                        row[lastPolledAt] = now
                    }

                    val pollables = messageRepository.findForPolling()
                    val pollableRefs = pollables.map { it.externalRefId }

                    pollableRefs shouldContain refNeverPolled
                    pollableRefs shouldNotContain refRecentlyPolled
                }
            }
        }

        "Find for polling - non-pollable states are excluded even if old" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val now = Clock.System.now()

                    val oldProcessedRef = Uuid.random()
                    val oldNewRef = Uuid.random()

                    messageRepository.createState(
                        DIALOG,
                        PROCESSED,
                        oldProcessedRef,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )
                    Messages.update({ externalRefId eq oldProcessedRef }) { row ->
                        row[lastPolledAt] = now - Duration.parse("60s")
                    }

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        oldNewRef,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )
                    Messages.update({ externalRefId eq oldNewRef }) { row ->
                        row[lastPolledAt] = now - Duration.parse("60s")
                    }

                    val pollables = messageRepository.findForPolling()
                    val pollableRefs = pollables.map { it.externalRefId }

                    pollableRefs shouldContain oldNewRef
                    pollableRefs shouldNotContain oldProcessedRef
                }
            }
        }

        "Mark Polled - updates last polled only for the provided external ref id's" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val now = Clock.System.now()

                    val ref1 = Uuid.random()
                    val ref2 = Uuid.random()
                    val ref3 = Uuid.random()

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        ref1,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        ref2,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )

                    messageRepository.createState(
                        DIALOG,
                        NEW,
                        ref3,
                        URI.create(MESSAGE3).toURL(),
                        now
                    )

                    messageRepository.findOrNull(ref1)!!.lastPolledAt shouldBe null
                    messageRepository.findOrNull(ref2)!!.lastPolledAt shouldBe null
                    messageRepository.findOrNull(ref3)!!.lastPolledAt shouldBe null

                    val updatedCount = messageRepository.markPolled(listOf(ref1, ref2))

                    updatedCount shouldBe 2

                    messageRepository.findOrNull(ref1)!!.lastPolledAt shouldNotBe null
                    messageRepository.findOrNull(ref2)!!.lastPolledAt shouldNotBe null

                    messageRepository.findOrNull(ref3)!!.lastPolledAt shouldBe null
                }
            }
        }

        afterEach { container.stop() }
    }
)

package no.nav.helsemelding.outbound.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.helsemelding.outbound.LifecycleError.ConflictingExternalMessageUrl
import no.nav.helsemelding.outbound.LifecycleError.ConflictingExternalReferenceId
import no.nav.helsemelding.outbound.LifecycleError.ConflictingLifecycleId
import no.nav.helsemelding.outbound.container
import no.nav.helsemelding.outbound.database
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.CreateStateResult
import no.nav.helsemelding.outbound.model.ExternalDeliveryState
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.REJECTED
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.UNCONFIRMED
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.repository.Messages.externalRefId
import no.nav.helsemelding.outbound.repository.Messages.lastPolledAt
import no.nav.helsemelding.outbound.shouldBeInstant
import no.nav.helsemelding.outbound.shouldBeRightOfType
import no.nav.helsemelding.outbound.util.olderThanSeconds
import no.nav.helsemelding.outbound.util.toSql
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

private const val MESSAGE1 = "http://example.com/messages/1"
private const val MESSAGE2 = "http://example.com/messages/2"
private const val MESSAGE3 = "http://example.com/messages/3"
private const val MESSAGE4 = "http://example.com/messages/4"
private const val MESSAGE5 = "http://example.com/messages/5"

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

                    val id = Uuid.random()
                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()
                    val now = Clock.System.now()

                    val result = messageRepository.createState(
                        id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        externalMessageUrl = externalMessageUrl,
                        lastStateChange = now
                    )

                    result.shouldBeRightOfType<CreateStateResult.Created> { created ->
                        created.state.messageType shouldBe DIALOG
                        created.state.externalRefId shouldBe externalRefId
                        created.state.externalMessageUrl shouldBe externalMessageUrl
                        created.state.lastStateChange shouldBeInstant now

                        created.state.externalDeliveryState shouldBe null
                        created.state.appRecStatus shouldBe null
                    }
                }
            }
        }

        "Create state - idempotent duplicate returns existing state" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val id = Uuid.random()
                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()
                    val now = Clock.System.now()

                    val firstResult = messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        externalMessageUrl = externalMessageUrl,
                        lastStateChange = now
                    )

                    val secondResult = messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        externalMessageUrl = externalMessageUrl,
                        lastStateChange = now
                    )

                    firstResult.shouldBeRightOfType<CreateStateResult.Created> { created ->
                        created.state.messageType shouldBe DIALOG
                        created.state.externalRefId shouldBe externalRefId
                        created.state.externalMessageUrl shouldBe externalMessageUrl
                        created.state.lastStateChange shouldBeInstant now
                    }

                    secondResult.shouldBeRightOfType<CreateStateResult.Existing> { existing ->
                        existing.state.messageType shouldBe DIALOG
                        existing.state.externalRefId shouldBe externalRefId
                        existing.state.externalMessageUrl shouldBe externalMessageUrl
                        existing.state.lastStateChange shouldBeInstant now
                    }
                }
            }
        }

        "Create state - conflicting lifecycle id" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val id = Uuid.random()
                    val externalRefId1 = Uuid.random()
                    val externalRefId2 = Uuid.random()
                    val url1 = URI.create(MESSAGE1).toURL()
                    val url2 = URI.create(MESSAGE2).toURL()
                    val now = Clock.System.now()

                    messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId1,
                        messageType = DIALOG,
                        externalMessageUrl = url1,
                        lastStateChange = now
                    )
                        .shouldBeRight()

                    val conflict = messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId2,
                        messageType = DIALOG,
                        externalMessageUrl = url2,
                        lastStateChange = now
                    )

                    val error = conflict.shouldBeLeft()
                    val lifecycleError = error.shouldBeInstanceOf<ConflictingLifecycleId>()
                    lifecycleError.messageId shouldBe id
                    lifecycleError.existingExternalRefId shouldBe externalRefId1
                    lifecycleError.existingExternalUrl shouldBe url1
                    lifecycleError.newExternalRefId shouldBe externalRefId2
                    lifecycleError.newExternalUrl shouldBe url2
                }
            }
        }

        "Create state - conflicting external reference id" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val id1 = Uuid.random()
                    val id2 = Uuid.random()
                    val externalRefId = Uuid.random()
                    val url1 = URI.create(MESSAGE1).toURL()
                    val url2 = URI.create(MESSAGE2).toURL()
                    val now = Clock.System.now()

                    messageRepository.createState(
                        id = id1,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        externalMessageUrl = url1,
                        lastStateChange = now
                    )
                        .shouldBeRight()

                    val conflict = messageRepository.createState(
                        id = id2,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        externalMessageUrl = url2,
                        lastStateChange = now
                    )

                    val error = conflict.shouldBeLeft()
                    val lifecycleError = error.shouldBeInstanceOf<ConflictingExternalReferenceId>()
                    lifecycleError.externalRefId shouldBe externalRefId
                    lifecycleError.existingMessageId shouldBe id1
                    lifecycleError.newMessageId shouldBe id2
                }
            }
        }

        "Create state - conflicting message URL" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val id1 = Uuid.random()
                    val id2 = Uuid.random()
                    val externalRefId1 = Uuid.random()
                    val externalRefId2 = Uuid.random()
                    val url = URI.create(MESSAGE1).toURL()
                    val now = Clock.System.now()

                    messageRepository.createState(
                        id = id1,
                        externalRefId = externalRefId1,
                        messageType = DIALOG,
                        externalMessageUrl = url,
                        lastStateChange = now
                    )
                        .shouldBeRight()

                    val conflict = messageRepository.createState(
                        id = id2,
                        externalRefId = externalRefId2,
                        messageType = DIALOG,
                        externalMessageUrl = url,
                        lastStateChange = now
                    )

                    val error = conflict.shouldBeLeft()
                    val lifecycleError = error.shouldBeInstanceOf<ConflictingExternalMessageUrl>()
                    lifecycleError.externalUrl shouldBe url
                    lifecycleError.existingMessageId shouldBe id1
                    lifecycleError.newMessageId shouldBe id2
                }
            }
        }

        "Update state - existing message" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val id = Uuid.random()
                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()

                    messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
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

                    val id = Uuid.random()
                    val externalRefId = Uuid.random()
                    val externalMessageUrl = URI.create(MESSAGE1).toURL()

                    messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
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
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
                        URI.create(MESSAGE1).toURL(),
                        Clock.System.now()
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
                        URI.create(MESSAGE2).toURL(),
                        Clock.System.now()
                    )
                        .shouldBeRightOfType<CreateStateResult.Created> {
                            Messages.update({ externalRefId eq it.state.externalRefId }) { row ->
                                row[externalDeliveryState] = ACKNOWLEDGED
                            }
                        }

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
                        URI.create(MESSAGE3).toURL(),
                        Clock.System.now()
                    )
                        .shouldBeRightOfType<CreateStateResult.Created> {
                            Messages.update({ externalRefId eq it.state.externalRefId }) { row ->
                                row[externalDeliveryState] = UNCONFIRMED
                            }
                        }

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
                        URI.create(MESSAGE4).toURL(),
                        Clock.System.now()
                    )
                        .shouldBeRightOfType<CreateStateResult.Created> {
                            Messages.update({ externalRefId eq it.state.externalRefId }) { row ->
                                row[externalDeliveryState] = ACKNOWLEDGED
                                row[appRecStatus] = AppRecStatus.OK
                            }
                        }

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
                        URI.create(MESSAGE5).toURL(),
                        Clock.System.now()
                    )
                        .shouldBeRightOfType<CreateStateResult.Created> {
                            Messages.update({ externalRefId eq it.state.externalRefId }) { row ->
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

                    val oldId = Uuid.random()
                    val newId = Uuid.random()
                    val oldExternalRefId = Uuid.random()
                    val newExternalRefId = Uuid.random()

                    messageRepository.createState(
                        oldId,
                        oldExternalRefId,
                        DIALOG,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )

                    messageRepository.createState(
                        newId,
                        newExternalRefId,
                        DIALOG,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )

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

                    val neverId = Uuid.random()
                    val recentId = Uuid.random()
                    val neverExternalRefId = Uuid.random()
                    val recentExternalRefId = Uuid.random()

                    messageRepository.createState(
                        neverId,
                        neverExternalRefId,
                        DIALOG,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )

                    messageRepository.createState(
                        recentId,
                        recentExternalRefId,
                        DIALOG,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )

                    Messages.update({ externalRefId eq recentExternalRefId }) {
                        it[lastPolledAt] = now
                    }

                    val externalRefIds = messageRepository.findForPolling().map { it.externalRefId }
                    externalRefIds shouldContain neverExternalRefId
                    externalRefIds shouldNotContain recentExternalRefId
                }
            }
        }

        "Mark polled - update only selected id's" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val id1 = Uuid.random()
                    val id2 = Uuid.random()
                    val id3 = Uuid.random()
                    val externalRefId1 = Uuid.random()
                    val externalRefId2 = Uuid.random()
                    val externalRefId3 = Uuid.random()
                    val now = Clock.System.now()

                    messageRepository.createState(
                        id1,
                        externalRefId1,
                        DIALOG,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )

                    messageRepository.createState(
                        id2,
                        externalRefId2,
                        DIALOG,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )

                    messageRepository.createState(
                        id3,
                        externalRefId3,
                        DIALOG,
                        URI.create(MESSAGE3).toURL(),
                        now
                    )

                    messageRepository.markPolled(listOf(externalRefId1, externalRefId2)) shouldBe 2

                    messageRepository.findOrNull(externalRefId1)!!.lastPolledAt shouldNotBe null
                    messageRepository.findOrNull(externalRefId2)!!.lastPolledAt shouldNotBe null

                    messageRepository.findOrNull(externalRefId3)!!.lastPolledAt shouldBe null
                }
            }
        }

        "countByExternalDeliveryState should return correct counts for each ExternalDeliveryState including null" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val now = Clock.System.now()

                    val externalRefId1 = Uuid.random()
                    val externalRefId2 = Uuid.random()

                    messageRepository.createState(
                        Uuid.random(),
                        externalRefId1,
                        DIALOG,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        externalRefId2,
                        DIALOG,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
                        URI.create(MESSAGE3).toURL(),
                        now
                    )

                    messageRepository.updateState(
                        externalRefId1,
                        ACKNOWLEDGED,
                        null,
                        now
                    )

                    messageRepository.updateState(
                        externalRefId2,
                        ACKNOWLEDGED,
                        null,
                        now
                    )

                    val counts = messageRepository.countByExternalDeliveryState()

                    counts.size shouldBe 2
                    counts[ACKNOWLEDGED] shouldBe 2
                    counts[null] shouldBe 1
                }
            }
        }

        "countByAppRecState should return correct counts for each AppRecStatus including null" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val now = Clock.System.now()

                    val externalRefId1 = Uuid.random()
                    val externalRefId2 = Uuid.random()

                    messageRepository.createState(
                        Uuid.random(),
                        externalRefId1,
                        DIALOG,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        externalRefId2,
                        DIALOG,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
                        URI.create(MESSAGE3).toURL(),
                        now
                    )

                    messageRepository.updateState(
                        externalRefId1,
                        null,
                        AppRecStatus.OK,
                        now
                    )

                    messageRepository.updateState(
                        externalRefId2,
                        null,
                        AppRecStatus.OK,
                        now
                    )

                    val counts = messageRepository.countByAppRecState()

                    counts.size shouldBe 2
                    counts[AppRecStatus.OK] shouldBe 2
                    counts[null] shouldBe 1
                }
            }
        }

        "countByExternalDeliveryStateAndAppRecStatus should return correct counts for each ExternalDeliveryState-AppRecStatus combination" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val now = Clock.System.now()

                    val externalRefId1 = Uuid.random()
                    val externalRefId2 = Uuid.random()

                    messageRepository.createState(
                        Uuid.random(),
                        externalRefId1,
                        DIALOG,
                        URI.create(MESSAGE1).toURL(),
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        externalRefId2,
                        DIALOG,
                        URI.create(MESSAGE2).toURL(),
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
                        URI.create(MESSAGE3).toURL(),
                        now
                    )

                    messageRepository.updateState(
                        externalRefId1,
                        ExternalDeliveryState.ACKNOWLEDGED,
                        AppRecStatus.OK,
                        now
                    )

                    messageRepository.updateState(
                        externalRefId2,
                        ExternalDeliveryState.ACKNOWLEDGED,
                        AppRecStatus.OK,
                        now
                    )

                    val counts = messageRepository.countByExternalDeliveryStateAndAppRecStatus()

                    counts.size shouldBe 2
                    counts[Pair(ExternalDeliveryState.ACKNOWLEDGED, AppRecStatus.OK)] shouldBe 2
                    counts[Pair(null, null)] shouldBe 1
                }
            }
        }

        afterEach { container.stop() }
    }
)

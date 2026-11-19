package no.nav.helsemelding.outbound.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.helsemelding.outbound.LifecycleError.ConflictingExternalReferenceId
import no.nav.helsemelding.outbound.LifecycleError.ConflictingLifecycleId
import no.nav.helsemelding.outbound.LifecycleError.MessageNotFound
import no.nav.helsemelding.outbound.LifecycleError.UnexpectedPublicationTransport
import no.nav.helsemelding.outbound.container
import no.nav.helsemelding.outbound.database
import no.nav.helsemelding.outbound.model.AppRecStatus
import no.nav.helsemelding.outbound.model.CreateStateResult
import no.nav.helsemelding.outbound.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.outbound.model.MessageTransport.EDI_2_0
import no.nav.helsemelding.outbound.model.MessageType.DIALOG
import no.nav.helsemelding.outbound.shouldBeInstant
import no.nav.helsemelding.outbound.shouldBeRightOfType
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.time.Clock
import kotlin.uuid.Uuid

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
                    val now = Clock.System.now()

                    val result = messageRepository.createState(
                        id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        lastStateChange = now
                    )

                    result.shouldBeRightOfType<CreateStateResult.Created> { created ->
                        created.state.messageType shouldBe DIALOG
                        created.state.externalRefId shouldBe externalRefId
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
                    val now = Clock.System.now()

                    val firstResult = messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        lastStateChange = now
                    )

                    val secondResult = messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        lastStateChange = now
                    )

                    firstResult.shouldBeRightOfType<CreateStateResult.Created> { created ->
                        created.state.messageType shouldBe DIALOG
                        created.state.externalRefId shouldBe externalRefId
                        created.state.lastStateChange shouldBeInstant now
                    }

                    secondResult.shouldBeRightOfType<CreateStateResult.Existing> { existing ->
                        existing.state.messageType shouldBe DIALOG
                        existing.state.externalRefId shouldBe externalRefId
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
                    val now = Clock.System.now()

                    messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId1,
                        messageType = DIALOG,
                        lastStateChange = now
                    )
                        .shouldBeRight()

                    val conflict = messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId2,
                        messageType = DIALOG,
                        lastStateChange = now
                    )

                    val error = conflict.shouldBeLeft()
                    val lifecycleError = error.shouldBeInstanceOf<ConflictingLifecycleId>()
                    lifecycleError.messageId shouldBe id
                    lifecycleError.existingExternalRefId shouldBe externalRefId1
                    lifecycleError.newExternalRefId shouldBe externalRefId2
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
                    val now = Clock.System.now()

                    messageRepository.createState(
                        id = id1,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        lastStateChange = now
                    )
                        .shouldBeRight()

                    val conflict = messageRepository.createState(
                        id = id2,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
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

        "Update state - existing message" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val id = Uuid.random()
                    val externalRefId = Uuid.random()

                    messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
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
                messageRepository.findByExternalReferenceId(externalRefId) shouldBe null
            }
        }

        "Find or null - value found" {
            resourceScope {
                val database = database(container.jdbcUrl)

                suspendTransaction(database) {
                    val messageRepository = ExposedMessageRepository(database)

                    val id = Uuid.random()
                    val externalRefId = Uuid.random()

                    messageRepository.createState(
                        id = id,
                        externalRefId = externalRefId,
                        messageType = DIALOG,
                        lastStateChange = Clock.System.now()
                    )

                    messageRepository.findByExternalReferenceId(externalRefId)!!.externalRefId shouldBe externalRefId
                }
            }
        }

        "Mark published - unknown lifecycle ID returns MessageNotFound" {
            resourceScope {
                val messageRepository = ExposedMessageRepository(database(container.jdbcUrl))
                val id = Uuid.random()

                messageRepository.markPublished(id, Clock.System.now()).shouldBeLeft(MessageNotFound(id))
            }
        }

        "Mark published - EDI 2.0 message returns UnexpectedPublicationTransport" {
            resourceScope {
                val database = database(container.jdbcUrl)
                val messageRepository = ExposedMessageRepository(database)
                val id = Uuid.random()
                val now = Clock.System.now()

                suspendTransaction(database) {
                    messageRepository.createState(id, Uuid.random(), DIALOG, now, transport = EDI_2_0).shouldBeRight()
                }

                messageRepository.markPublished(id, now).shouldBeLeft(UnexpectedPublicationTransport(id, EDI_2_0))
                messageRepository.findById(id).shouldNotBeNull().publishedAt.shouldBeNull()
            }
        }

        "Count by external delivery state - includes null state" {
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
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        externalRefId2,
                        DIALOG,
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
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

        "Count by AppRec status - includes null status" {
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
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        externalRefId2,
                        DIALOG,
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
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

        "Count by external delivery state and AppRec status - includes null values" {
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
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        externalRefId2,
                        DIALOG,
                        now
                    )

                    messageRepository.createState(
                        Uuid.random(),
                        Uuid.random(),
                        DIALOG,
                        now
                    )

                    messageRepository.updateState(
                        externalRefId1,
                        ACKNOWLEDGED,
                        AppRecStatus.OK,
                        now
                    )

                    messageRepository.updateState(
                        externalRefId2,
                        ACKNOWLEDGED,
                        AppRecStatus.OK,
                        now
                    )

                    val counts = messageRepository.countByExternalDeliveryStateAndAppRecStatus()

                    counts.size shouldBe 2
                    counts[Pair(ACKNOWLEDGED, AppRecStatus.OK)] shouldBe 2
                    counts[Pair(null, null)] shouldBe 1
                }
            }
        }

        afterEach { container.stop() }
    }
)

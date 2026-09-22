package no.nav.helsemelding.outbound.repository

import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.helsemelding.outbound.container
import no.nav.helsemelding.outbound.database
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant

class NotificationOffsetRepositorySpec : StringSpec(
    {
        beforeEach {
            container().start()
        }

        "missing offset returns zero" {
            resourceScope {
                val db = database(container().jdbcUrl)
                val repository = ExposedNotificationOffsetRepository(db)

                repository.getOffset(91001) shouldBe 0L
            }
        }

        "offsets are persisted independently per her id" {
            resourceScope {
                val db = database(container().jdbcUrl)
                val repository = ExposedNotificationOffsetRepository(db)
                val offset = Int.MAX_VALUE.toLong() + 100L

                repository.saveOffset(91002, offset)
                repository.saveOffset(91003, 0L)

                val reopened = ExposedNotificationOffsetRepository(db)
                reopened.getOffset(91002) shouldBe offset
                reopened.getOffset(91003) shouldBe 0L
                reopened.getOffset(91004) shouldBe 0L
            }
        }

        "saving again updates the same row and refreshes its timestamp" {
            resourceScope {
                val db = database(container().jdbcUrl)
                val repository = ExposedNotificationOffsetRepository(db)
                val oldTimestamp = Instant.fromEpochSeconds(0)
                repository.saveOffset(91005, 12L)
                suspendTransaction(db) {
                    NotificationOffsets.update({ NotificationOffsets.herId eq 91005 }) {
                        it[updatedAt] = oldTimestamp
                    }
                }

                repository.saveOffset(91005, 37L)

                repository.getOffset(91005) shouldBe 37L
                suspendTransaction(db) {
                    val row = NotificationOffsets.selectAll()
                        .where { NotificationOffsets.herId eq 91005 }
                        .single()
                    row[NotificationOffsets.updatedAt] shouldNotBe oldTimestamp
                }
            }
        }

        "negative offsets are rejected without changing the saved offset" {
            resourceScope {
                val db = database(container().jdbcUrl)
                val repository = ExposedNotificationOffsetRepository(db)
                repository.saveOffset(91006, 42L)

                shouldThrow<java.sql.SQLException> {
                    repository.saveOffset(91006, -1L)
                }

                repository.getOffset(91006) shouldBe 42L
            }
        }
    }
)

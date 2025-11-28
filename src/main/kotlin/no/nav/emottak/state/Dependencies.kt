package no.nav.emottak.state

import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.await.awaitAll
import com.zaxxer.hikari.HikariDataSource
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.prometheus.PrometheusConfig.DEFAULT
import io.micrometer.prometheus.PrometheusMeterRegistry
import no.nav.emottak.utils.config.Kafka
import no.nav.emottak.utils.config.toKafkaPublisherSettings
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import no.nav.emottak.state.config.Database as DatabaseConfig

private val log = KotlinLogging.logger {}

data class Dependencies(
    val database: Database,
    val meterRegistry: PrometheusMeterRegistry,
    val kafkaPublisher: KafkaPublisher<String, ByteArray>
)

internal suspend fun ResourceScope.metricsRegistry(): PrometheusMeterRegistry =
    install({ PrometheusMeterRegistry(DEFAULT) }) { p, _: ExitCase ->
        p.close().also { log.info { "Closed prometheus registry" } }
    }

internal suspend fun ResourceScope.kafkaPublisher(kafka: Kafka): KafkaPublisher<String, ByteArray> =
    install({ KafkaPublisher(kafka.toKafkaPublisherSettings()) }) { p, _: ExitCase ->
        p.close().also { log.info { "Closed kafka publisher" } }
    }

internal suspend fun ResourceScope.dataSource(config: DatabaseConfig): HikariDataSource =
    install({ HikariDataSource(config.toHikariConfig()) }) { h, _: ExitCase ->
        h.close().also { log.info { "Closed hikari data source" } }
    }

internal suspend fun ResourceScope.database(config: DatabaseConfig, dataSource: HikariDataSource): Database =
    install({ flyway(dataSource, config.flyway).run { Database.connect(dataSource) } }) { d, _: ExitCase ->
        TransactionManager.closeAndUnregister(d).also { log.info { "Closed database" } }
    }

private fun flyway(dataSource: HikariDataSource, flywayConfig: DatabaseConfig.Flyway): MigrateResult =
    Flyway.configure()
        .dataSource(dataSource)
        .locations(flywayConfig.locations)
        .baselineOnMigrate(flywayConfig.baselineOnMigrate)
        .load()
        .migrate()

suspend fun ResourceScope.dependencies(): Dependencies = awaitAll {
    val config = config()

    val metricsRegistry = async { metricsRegistry() }
    val kafkaPublisher = async { kafkaPublisher(config.kafka) }
    val dataSource = async { dataSource(config.database) }
    val database = async { database(config.database, dataSource.await()) }

    Dependencies(
        database.await(),
        metricsRegistry.await(),
        kafkaPublisher.await()
    )
}

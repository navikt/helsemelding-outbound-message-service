package no.nav.emottak.state

import arrow.fx.coroutines.ExitCase
import arrow.fx.coroutines.ResourceScope
import arrow.fx.coroutines.await.awaitAll
import com.zaxxer.hikari.HikariDataSource
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.nomisRev.kafka.receiver.AutoOffsetReset
import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.prometheus.PrometheusConfig.DEFAULT
import io.micrometer.prometheus.PrometheusMeterRegistry
import no.nav.emottak.ediadapter.client.EdiAdapterClient
import no.nav.emottak.ediadapter.client.HttpEdiAdapterClient
import no.nav.emottak.ediadapter.client.scopedAuthHttpClient
import no.nav.emottak.state.config.EdiAdapter
import no.nav.emottak.state.config.Kafka
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import no.nav.emottak.state.config.Database as DatabaseConfig

private val log = KotlinLogging.logger {}

data class Dependencies(
    val database: Database,
    val ediAdapterClient: EdiAdapterClient,
    val meterRegistry: PrometheusMeterRegistry,
    val kafkaReceiver: KafkaReceiver<String, ByteArray>,
    val kafkaPublisher: KafkaPublisher<String, ByteArray>
)

internal suspend fun ResourceScope.metricsRegistry(): PrometheusMeterRegistry =
    install({ PrometheusMeterRegistry(DEFAULT) }) { p, _: ExitCase ->
        p.close().also { log.info { "Closed prometheus registry" } }
    }

internal suspend fun ResourceScope.ediAdapterClient(ediAdapter: EdiAdapter): EdiAdapterClient =
    install({ HttpEdiAdapterClient(scopedAuthHttpClient(ediAdapter.scope.value)) }) { p, _: ExitCase ->
        p.close().also { log.info { "Closed edi adapter client" } }
    }

internal suspend fun ResourceScope.kafkaPublisher(kafka: Kafka): KafkaPublisher<String, ByteArray> =
    install({ KafkaPublisher(kafka.toPublisherSettings()) }) { p, _: ExitCase ->
        p.close().also { log.info { "Closed kafka publisher" } }
    }

internal fun kafkaReceiver(kafka: Kafka, autoOffsetReset: AutoOffsetReset): KafkaReceiver<String, ByteArray> =
    KafkaReceiver(kafka.toReceiverSettings(kafka, autoOffsetReset))

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
    val ediAdapterClient = async { ediAdapterClient(config.ediAdapter) }
    val database = async { database(config.database, dataSource.await()) }
    val kafkaReceiver = kafkaReceiver(config.kafka, AutoOffsetReset.Latest)

    Dependencies(
        database.await(),
        ediAdapterClient.await(),
        metricsRegistry.await(),
        kafkaReceiver,
        kafkaPublisher.await()
    )
}

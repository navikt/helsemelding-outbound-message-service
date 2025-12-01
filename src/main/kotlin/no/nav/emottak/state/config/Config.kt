package no.nav.emottak.state.config

import com.sksamuel.hoplite.Masked
import com.zaxxer.hikari.HikariConfig
import io.github.nomisRev.kafka.publisher.PublisherSettings
import io.github.nomisRev.kafka.receiver.AutoOffsetReset
import io.github.nomisRev.kafka.receiver.ReceiverSettings
import kotlinx.serialization.Serializable
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties
import kotlin.time.Duration

data class Config(
    val kafka: Kafka,
    val kafkaTopics: KafkaTopics,
    val server: Server,
    val poller: Poller,
    val database: Database,
    val ediAdapter: EdiAdapter
)

data class Kafka(
    val topic: String,
    val groupId: String,
    val bootstrapServers: String,
    val securityProtocol: SecurityProtocol,
    val keystoreType: KeystoreType,
    val keystoreLocation: KeystoreLocation,
    val keystorePassword: Masked,
    val truststoreType: TruststoreType,
    val truststoreLocation: TruststoreLocation,
    val truststorePassword: Masked
) {
    private val securityProtocolConfig = "security.protocol"
    private val sslKeystoreTypeConfig = "ssl.keystore.type"
    private val sslKeystoreLocationConfig = "ssl.keystore.location"
    private val sslKeystorePasswordConfig = "ssl.keystore.password"
    private val sslTruststoreTypeConfig = "ssl.truststore.type"
    private val sslTruststoreLocationConfig = "ssl.truststore.location"
    private val sslTruststorePasswordConfig = "ssl.truststore.password"

    @JvmInline
    value class SecurityProtocol(val value: String)

    @JvmInline
    value class KeystoreType(val value: String)

    @JvmInline
    value class KeystoreLocation(val value: String)

    @JvmInline
    value class TruststoreType(val value: String)

    @JvmInline
    value class TruststoreLocation(val value: String)

    fun toPublisherSettings(): PublisherSettings<String, ByteArray> =
        PublisherSettings(
            bootstrapServers = bootstrapServers,
            keySerializer = StringSerializer(),
            valueSerializer = ByteArraySerializer(),
            properties = toProperties()
        )

    fun toReceiverSettings(
        kafka: Kafka,
        autoOffsetReset: AutoOffsetReset
    ): ReceiverSettings<String, ByteArray> =
        ReceiverSettings(
            bootstrapServers = kafka.bootstrapServers,
            keyDeserializer = StringDeserializer(),
            valueDeserializer = ByteArrayDeserializer(),
            groupId = kafka.groupId,
            properties = kafka.toProperties(),
            autoOffsetReset = autoOffsetReset
        )

    private fun toProperties() = Properties()
        .apply {
            put(securityProtocolConfig, securityProtocol.value)
            put(sslKeystoreTypeConfig, keystoreType.value)
            put(sslKeystoreLocationConfig, keystoreLocation.value)
            put(sslKeystorePasswordConfig, keystorePassword.value)
            put(sslTruststoreTypeConfig, truststoreType.value)
            put(sslTruststoreLocationConfig, truststoreLocation.value)
            put(sslTruststorePasswordConfig, truststorePassword.value)
        }
}

fun Config.withKafka(update: Kafka.() -> Kafka) = copy(kafka = kafka.update())

data class KafkaTopics(
    val messagesInTopic: String
)

data class Server(
    val port: Port,
    val preWait: Duration
) {
    @JvmInline
    value class Port(val value: Int)
}

data class EdiAdapter(
    val scope: Scope
) {
    @JvmInline
    value class Scope(val value: String)
}

data class Poller(
    val fetchLimit: Int,
    val minAgeSeconds: Duration,
    val scheduleInterval: Duration
)

data class Database(
    val url: Url,
    val minimumIdleConnections: MinimumIdleConnections,
    val maxLifetimeConnections: MaxLifeTimeConnections,
    val maxConnectionPoolSize: MaxConnectionPoolSize,
    val connectionTimeout: ConnectionTimeout,
    val idleConnectionTimeout: IdleConnectionTimeout,
    val cachePreparedStatements: CachePreparedStatements,
    val preparedStatementsCacheSize: PreparedStatementsCacheSize,
    val preparedStatementsCacheSqlLimit: PreparedStatementsCacheSqlLimit,
    val driverClassName: DriverClassName,
    val username: UserName,
    val password: Masked,
    val flyway: Flyway
) {
    @JvmInline
    value class Url(val value: String)

    @JvmInline
    value class MinimumIdleConnections(val value: Int)

    @JvmInline
    value class MaxLifeTimeConnections(val value: Int)

    @JvmInline
    value class MaxConnectionPoolSize(val value: Int)

    @JvmInline
    value class ConnectionTimeout(val value: Int)

    @JvmInline
    value class IdleConnectionTimeout(val value: Int)

    @JvmInline
    value class CachePreparedStatements(val value: Boolean)

    @JvmInline
    value class PreparedStatementsCacheSize(val value: Int)

    @JvmInline
    value class PreparedStatementsCacheSqlLimit(val value: Int)

    @JvmInline
    value class DriverClassName(val value: String)

    @JvmInline
    value class UserName(val value: String)

    @Serializable
    data class Flyway(val locations: String, val baselineOnMigrate: Boolean)

    fun toHikariConfig(): HikariConfig = Properties()
        .apply {
            put("jdbcUrl", url.value)
            put("username", username.value)
            put("password", password.value)
            put("driverClassName", driverClassName.value)
            put("minimumIdle", minimumIdleConnections.value)
            put("maxLifetime", maxLifetimeConnections.value)
            put("maximumPoolSize", maxConnectionPoolSize.value)
            put("connectionTimeout", connectionTimeout.value)
            put("idleTimeout", idleConnectionTimeout.value)
            put("dataSource.cachePrepStmts", cachePreparedStatements.value)
            put("dataSource.prepStmtCacheSize", preparedStatementsCacheSize.value)
            put("dataSource.prepStmtCacheSqlLimit", preparedStatementsCacheSqlLimit.value)
        }
        .let(::HikariConfig)
}

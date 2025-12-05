package no.nav.emottak.state.config

import com.sksamuel.hoplite.Masked
import com.zaxxer.hikari.HikariConfig
import io.github.nomisRev.kafka.publisher.PublisherSettings
import kotlinx.serialization.Serializable
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties
import kotlin.time.Duration

private const val SECURITY_PROTOCOL_CONFIG = "security.protocol"
private const val SSL_KEYSTORE_TYPE_CONFIG = "ssl.keystore.type"
private const val SSL_KEYSTORE_LOCATION_CONFIG = "ssl.keystore.location"
private const val SSL_KEYSTORE_PASSWORD_CONFIG = "ssl.keystore.password"
private const val SSL_TRUSTSTORE_TYPE_CONFIG = "ssl.truststore.type"
private const val SSL_TRUSTSTORE_LOCATION_CONFIG = "ssl.truststore.location"
private const val SSL_TRUSTSTORE_PASSWORD_CONFIG = "ssl.truststore.password"

data class Config(
    val kafka: Kafka,
    val server: Server,
    val poller: Poller,
    val database: Database,
    val ediAdapter: EdiAdapter
)

data class Kafka(
    val bootstrapServers: String,
    val securityProtocol: SecurityProtocol,
    val keystoreType: KeystoreType,
    val keystoreLocation: KeystoreLocation,
    val keystorePassword: Masked,
    val truststoreType: TruststoreType,
    val truststoreLocation: TruststoreLocation,
    val truststorePassword: Masked,
    val groupId: String
) {
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

    private fun toProperties() = Properties()
        .apply {
            put(SECURITY_PROTOCOL_CONFIG, securityProtocol.value)
            put(SSL_KEYSTORE_TYPE_CONFIG, keystoreType.value)
            put(SSL_KEYSTORE_LOCATION_CONFIG, keystoreLocation.value)
            put(SSL_KEYSTORE_PASSWORD_CONFIG, keystorePassword.value)
            put(SSL_TRUSTSTORE_TYPE_CONFIG, truststoreType.value)
            put(SSL_TRUSTSTORE_LOCATION_CONFIG, truststoreLocation.value)
            put(SSL_TRUSTSTORE_PASSWORD_CONFIG, truststorePassword.value)
        }
}

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

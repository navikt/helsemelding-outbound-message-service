package no.nav.emottak.state.config

import com.sksamuel.hoplite.Masked
import kotlinx.serialization.Serializable
import no.nav.emottak.utils.config.Server
import java.util.Properties
import kotlin.time.Duration

data class Config(
    val server: Server,
    val poller: Poller,
    val database: Database
)

data class Poller(
    val fetchLimit: Int,
    val minAgeSeconds: Duration
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
}

fun Database.toProperties() = Properties()
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

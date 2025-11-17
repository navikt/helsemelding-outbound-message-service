package no.nav.emottak.state

import arrow.core.memoize
import arrow.fx.coroutines.ResourceScope
import no.nav.emottak.state.config.Database.Url
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer

suspend fun ResourceScope.database(jdbcUrl: String): Database =
    database(
        config().database,
        dataSource(config().database.copy(url = Url(jdbcUrl)))
    )

val container: () -> PostgreSQLContainer<Nothing> = {
    PostgreSQLContainer<Nothing>("postgres:18-alpine")
        .apply {
            startupAttempts = 1
            withDatabaseName("message-state-db")
            withUsername("postgres")
            withPassword("postgres")
        }
}
    .memoize()

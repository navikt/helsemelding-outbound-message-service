package no.nav.emottak.state.util

import org.jetbrains.exposed.v1.core.ColumnTransformer
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class UuidTransformer : ColumnTransformer<UUID, Uuid> {
    override fun wrap(value: UUID): Uuid = value.toKotlinUuid()
    override fun unwrap(value: Uuid): UUID = value.toJavaUuid()
}

object UrlTransformer : ColumnTransformer<String, URL> {
    override fun wrap(value: String): URL = URI.create(value).toURL()
    override fun unwrap(value: URL): String = value.toString()
}

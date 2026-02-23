package no.nav.helsemelding.outbound.util

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import no.nav.helsemelding.outbound.PublishError
import org.apache.kafka.clients.producer.RecordMetadata

fun Result<RecordMetadata>.toEither(
    onFailure: (Throwable) -> PublishError
): Either<PublishError, RecordMetadata> =
    fold(
        onSuccess = { it.right() },
        onFailure = { onFailure(it).left() }
    )

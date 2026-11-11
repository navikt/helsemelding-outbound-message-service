package no.nav.helsemelding.outbound.publisher

import arrow.core.Either
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import kotlinx.serialization.json.Json
import no.nav.helsemelding.jsonschema.core.model.ErrorMessage
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.config.Topics
import no.nav.helsemelding.outbound.util.toEither
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata

interface ErrorMessagePublisher {
    suspend fun publish(message: ErrorMessage): Either<PublishError, RecordMetadata>
}

class KafkaErrorMessagePublisher(
    private val topics: Topics,
    private val kafkaPublisher: KafkaPublisher<String, ByteArray>
) : ErrorMessagePublisher {
    override suspend fun publish(message: ErrorMessage): Either<PublishError, RecordMetadata> {
        val messageId = requireNotNull(message.messageId)
        return kafkaPublisher.publishScope {
            publishCatching(
                ProducerRecord(
                    topics.errorMessage,
                    null,
                    Json.encodeToString(message).encodeToByteArray()
                )
            )
        }
            .toEither { error -> PublishError.Failure(messageId, topics.errorMessage, error) }
    }
}

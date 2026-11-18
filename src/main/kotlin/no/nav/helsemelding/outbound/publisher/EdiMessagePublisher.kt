package no.nav.helsemelding.outbound.publisher

import arrow.core.Either
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import no.nav.helsemelding.outbound.PublishError
import no.nav.helsemelding.outbound.util.toEither
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import kotlin.uuid.Uuid

fun interface EdiMessagePublisher {
    suspend fun publish(messageId: Uuid, payload: ByteArray): Either<PublishError, RecordMetadata>
}

class KafkaEdiMessagePublisher(
    private val topic: String,
    private val kafkaPublisher: KafkaPublisher<String, ByteArray>
) : EdiMessagePublisher {
    override suspend fun publish(messageId: Uuid, payload: ByteArray): Either<PublishError, RecordMetadata> =
        kafkaPublisher.publishScope {
            publishCatching(
                ProducerRecord(
                    topic,
                    messageId.toString(),
                    payload
                )
            )
        }
            .toEither { PublishError.Failure(messageId, topic, it) }
}

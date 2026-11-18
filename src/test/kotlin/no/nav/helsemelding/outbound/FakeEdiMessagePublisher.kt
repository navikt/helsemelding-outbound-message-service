package no.nav.helsemelding.outbound

import arrow.core.Either
import arrow.core.right
import no.nav.helsemelding.outbound.publisher.EdiMessagePublisher
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import kotlin.uuid.Uuid

class FakeEdiMessagePublisher : EdiMessagePublisher {
    val published = mutableListOf<Pair<Uuid, ByteArray>>()
    var beforePublish: suspend (Uuid, ByteArray) -> Unit = { _, _ -> }
    var result: Either<PublishError, RecordMetadata> = RecordMetadata(
        TopicPartition("test.edi.xml", 0),
        0,
        0,
        0,
        0,
        0
    )
        .right()

    override suspend fun publish(messageId: Uuid, payload: ByteArray): Either<PublishError, RecordMetadata> {
        published.add(messageId to payload)
        beforePublish(messageId, payload)
        return result
    }
}

package no.nav.helsemelding.state.publisher

import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import no.nav.helsemelding.state.config
import no.nav.helsemelding.state.model.AppRecStatus
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import kotlin.Result.Companion.failure
import kotlin.uuid.Uuid

private val log = KotlinLogging.logger {}

interface MessagePublisher {
    suspend fun publish(referenceId: Uuid, message: String): Result<RecordMetadata>
}

class DialogMessagePublisher(
    private val kafkaPublisher: KafkaPublisher<String, ByteArray>
) : MessagePublisher {
    private val kafkaConfig = config().kafka

    override suspend fun publish(
        referenceId: Uuid,
        message: String
    ): Result<RecordMetadata> = kafkaPublisher
        .publishScope {
            val record = toProducerRecord(
                referenceId,
                message
            )
            publishCatching(record)
        }
        .onSuccess { log.info { "Published message with reference id $referenceId to: TOPIC" } }
        .onFailure { log.error { "Failed to publish message with reference id: $referenceId" } }

    private fun toProducerRecord(referenceId: Uuid, message: String) =
        ProducerRecord(
            kafkaConfig.topic,
            referenceId.toString(),
            message.toByteArray()
        )
}

class FakeDialogMessagePublisher : MessagePublisher {
    data class PublishedMessage(
        val referenceId: Uuid,
        val appRecStatus: AppRecStatus
    )

    val published = mutableListOf<PublishedMessage>()
    var failNext: Boolean = false

    override suspend fun publish(
        referenceId: Uuid,
        message: String
    ): Result<RecordMetadata> {
        if (failNext) {
            failNext = false
            return failure(RuntimeException("Publish failure"))
        }

        val status = extractAppRecStatus(message)

        published += PublishedMessage(referenceId, status)

        val metadata = RecordMetadata(
            TopicPartition("TOPIC", 0),
            0L,
            0,
            System.currentTimeMillis(),
            referenceId.toString().length,
            message.toByteArray().size
        )

        return Result.success(metadata)
    }

    private fun extractAppRecStatus(message: String): AppRecStatus {
        return when (message) {
            "OK" -> AppRecStatus.OK
            "OK_ERROR_IN_MESSAGE_PART" -> AppRecStatus.OK_ERROR_IN_MESSAGE_PART
            else -> AppRecStatus.REJECTED
        }
    }
}

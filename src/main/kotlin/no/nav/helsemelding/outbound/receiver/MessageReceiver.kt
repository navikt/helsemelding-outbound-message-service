package no.nav.helsemelding.outbound.receiver

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import io.github.nomisRev.kafka.receiver.ReceiverRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import no.nav.helsemelding.outbound.handler.MessageHandler
import no.nav.helsemelding.outbound.model.DialogMessage
import kotlin.uuid.Uuid

class MessageReceiver(
    private val dialogMessageOutTopic: String,
    private val kafkaReceiver: KafkaReceiver<String, ByteArray>,
    private val errorHandler: MessageHandler
) {
    fun receiveMessages(): Flow<DialogMessage> =
        kafkaReceiver
            .receive(dialogMessageOutTopic)
            .mapNotNull { record ->
                when (val validation = validateRecordKey(record)) {
                    RecordKeyValidation.Valid -> toMessage(record)
                    is RecordKeyValidation.Invalid -> {
                        errorHandler.handleInvalidKafkaKey(record, validation)
                        null
                    }
                }
            }

    private fun toMessage(record: ReceiverRecord<String, ByteArray>): DialogMessage =
        DialogMessage(
            Uuid.parse(record.key()),
            record.value()
        )
}

sealed interface RecordKeyValidation {
    data object Valid : RecordKeyValidation

    data class Invalid(
        val reason: String
    ) : RecordKeyValidation
}

internal fun validateRecordKey(
    record: ReceiverRecord<String, ByteArray>
): RecordKeyValidation =
    when (val key = record.key()) {
        null -> RecordKeyValidation.Invalid("Kafka record key is null")
        else ->
            if (Uuid.parseOrNull(key) == null) {
                RecordKeyValidation.Invalid("Kafka record key is not a valid UUID")
            } else {
                RecordKeyValidation.Valid
            }
    }

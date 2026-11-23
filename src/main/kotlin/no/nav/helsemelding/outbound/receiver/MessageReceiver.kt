package no.nav.helsemelding.outbound.receiver

import io.github.nomisRev.kafka.receiver.KafkaReceiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import no.nav.helsemelding.outbound.model.DialogMessage
import no.nav.helsemelding.outbound.model.ReceivedDialogMessage
import kotlin.uuid.Uuid

class MessageReceiver(
    private val dialogMessageOutTopic: String,
    private val kafkaReceiver: KafkaReceiver<String, ByteArray>
) {
    fun receiveMessages(): Flow<ReceivedDialogMessage> =
        kafkaReceiver
            .receive(dialogMessageOutTopic)
            .map { record ->
                val sourceSystem = record.headers()
                    .lastHeader("sourceSystem")
                    ?.value()
                    ?.decodeToString()
                    ?: "UNKNOWN"

                ReceivedDialogMessage(
                    message = DialogMessage(
                        id = Uuid.parse(record.key()),
                        payload = record.value(),
                        sourceSystem = sourceSystem,
                        ediVersion = record.headers().lastHeader("ediVersion")?.value()?.decodeToString()
                    ),
                    acknowledge = { record.offset.acknowledge() }
                )
            }
}

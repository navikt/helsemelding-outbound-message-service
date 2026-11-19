package no.nav.helsemelding.outbound.receiver

import app.cash.turbine.test
import arrow.fx.coroutines.resourceScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.helsemelding.outbound.KafkaSpec
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.model.AppRecErrorMessage
import no.nav.helsemelding.outbound.model.EdiStatus
import no.nav.helsemelding.outbound.model.EdiStatusEvent
import org.apache.kafka.clients.producer.ProducerRecord
import kotlin.uuid.Uuid

class EdiMessageReceiverSpec : KafkaSpec({
    val topic = config().edi.statusMessageTopic

    beforeEach { clearTopic(topic) }

    "receives the message id and status" {
        resourceScope {
            val publisher = kafkaPublisher()
            val id = Uuid.random()
            val status = EdiStatusEvent(EdiStatus.REJECTED_TRANSPORT, 700, listOf(AppRecErrorMessage(code = "E1")))
            publisher.publishScope {
                publish(ProducerRecord(topic, id.toString(), Json.encodeToString(status).toByteArray()))
            }
            val kafka = kafkaReceiver()

            EdiMessageReceiver(topic, kafka).receiveMessages().test(timeout = kafkaTimeout) {
                val received = awaitItem()
                received.messageId shouldBe id
                received.status shouldBe status
            }
        }
    }

    "rejects an invalid message key" {
        resourceScope {
            val publisher = kafkaPublisher()
            publisher.publishScope { publish(ProducerRecord(topic, "invalid", """{"status":"ACKNOWLEDGED"}""".toByteArray())) }
            val kafka = kafkaReceiver()

            EdiMessageReceiver(topic, kafka).receiveMessages().test(timeout = kafkaTimeout) {
                awaitError().shouldBeInstanceOf<IllegalArgumentException>()
            }
        }
    }

    for ((description, json) in mapOf("an unsupported" to """{"status":"QUEUED"}""", "a malformed" to "{invalid")) {
        "rejects $description status" {
            resourceScope {
                val publisher = kafkaPublisher()
                publisher.publishScope { publish(ProducerRecord(topic, Uuid.random().toString(), json.toByteArray())) }
                val kafka = kafkaReceiver()

                EdiMessageReceiver(topic, kafka).receiveMessages().test(timeout = kafkaTimeout) {
                    awaitError().shouldBeInstanceOf<SerializationException>()
                }
            }
        }
    }
})

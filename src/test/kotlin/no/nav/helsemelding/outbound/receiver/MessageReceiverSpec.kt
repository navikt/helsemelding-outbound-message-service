package no.nav.helsemelding.outbound.receiver

import app.cash.turbine.test
import arrow.fx.coroutines.resourceScope
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.helsemelding.outbound.KafkaSpec
import no.nav.helsemelding.outbound.config
import org.apache.kafka.clients.producer.ProducerRecord
import kotlin.uuid.Uuid

class MessageReceiverSpec : KafkaSpec({
    val topic = config().kafka.topics.dialogMessageOut

    beforeEach { clearTopic(topic) }

    "receives message data" {
        resourceScope {
            val publisher = kafkaPublisher()
            val id = Uuid.random()
            val payload = "<MsgHead>test</MsgHead>".toByteArray()
            val record = ProducerRecord(topic, id.toString(), payload).apply {
                headers().add("sourceSystem", "test-system".toByteArray())
            }
            publisher.publishScope { publish(record) }
            val kafka = kafkaReceiver()

            MessageReceiver(topic, kafka).receiveMessages().test(timeout = kafkaTimeout) {
                val received = awaitItem()
                received.message.id shouldBe id
                received.message.payload shouldBe payload
                received.message.sourceSystem shouldBe "test-system"
            }
        }
    }

    "uses UNKNOWN when sourceSystem is missing" {
        resourceScope {
            val publisher = kafkaPublisher()
            publisher.publishScope { publish(ProducerRecord(topic, Uuid.random().toString(), "data".toByteArray())) }

            MessageReceiver(topic, kafkaReceiver()).receiveMessages().test(timeout = kafkaTimeout) {
                awaitItem().message.sourceSystem shouldBe "UNKNOWN"
            }
        }
    }

    "rejects an invalid message key" {
        resourceScope {
            val publisher = kafkaPublisher()
            publisher.publishScope { publish(ProducerRecord(topic, "invalid", "data".toByteArray())) }
            val kafka = kafkaReceiver()

            MessageReceiver(topic, kafka).receiveMessages().test(timeout = kafkaTimeout) {
                awaitError().shouldBeInstanceOf<IllegalArgumentException>()
            }
        }
    }
})

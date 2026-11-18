package no.nav.helsemelding.outbound.publisher

import app.cash.turbine.test
import arrow.fx.coroutines.resourceScope
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.KafkaSpec
import no.nav.helsemelding.outbound.config
import kotlin.uuid.Uuid

class EdiMessagePublisherSpec : KafkaSpec({
    "publishes original XML keyed by the existing lifecycle ID" {
        resourceScope {
            val topic = config().edi.dialogMessageOutTopic
            val publisher = KafkaEdiMessagePublisher(topic, kafkaPublisher())
            val id = Uuid.random()
            val xml = "<MsgHead>æøå</MsgHead>".toByteArray()

            publisher.publish(id, xml).shouldBeRight().topic() shouldBe topic

            kafkaReceiver().receive(topic)
                .test(timeout = kafkaTimeout) {
                    val record = awaitItem()
                    record.key() shouldBe id.toString()
                    record.value() shouldBe xml
                }
        }
    }
})

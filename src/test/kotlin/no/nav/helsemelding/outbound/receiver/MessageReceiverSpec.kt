package no.nav.helsemelding.outbound.receiver

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import arrow.fx.coroutines.resourceScope
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.nomisRev.kafka.receiver.AutoOffsetReset
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.KafkaSpec
import no.nav.helsemelding.outbound.config
import no.nav.helsemelding.outbound.config.Config
import no.nav.helsemelding.outbound.config.Kafka.SecurityProtocol
import no.nav.helsemelding.outbound.config.withKafka
import no.nav.helsemelding.outbound.kafkaReceiver
import org.apache.kafka.clients.producer.ProducerRecord
import kotlin.uuid.Uuid

class MessageReceiverSpec : KafkaSpec(
    {
        lateinit var config: Config

        beforeSpec {
            config = config()
                .withKafka {
                    copy(
                        bootstrapServers = container.bootstrapServers,
                        securityProtocol = SecurityProtocol("PLAINTEXT")
                    )
                }
        }

        "One message if record key is a valid uuid - one message" {
            resourceScope {
                turbineScope {
                    val publisher = KafkaPublisher(publisherSettings())
                    val referenceId = Uuid.random()
                    val content = "data".toByteArray()
                    val topic = config.kafka.topics.dialogMessageOut
                    publisher.publishScope {
                        publish(
                            ProducerRecord(
                                topic,
                                referenceId.toString(),
                                content
                            )
                        )
                    }

                    val receiver = MessageReceiver(
                        topic,
                        kafkaReceiver(config.kafka, AutoOffsetReset.Earliest)
                    )
                    val messages = receiver.receiveMessages()

                    messages.test {
                        val message = awaitItem()
                        message.id shouldBe referenceId
                        message.payload shouldBe content
                    }
                }
            }
        }
    }
)

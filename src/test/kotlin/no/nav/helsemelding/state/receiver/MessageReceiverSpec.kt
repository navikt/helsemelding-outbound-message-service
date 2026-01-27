package no.nav.helsemelding.state.receiver

import app.cash.turbine.test
import app.cash.turbine.turbineScope
import arrow.fx.coroutines.resourceScope
import io.github.nomisRev.kafka.publisher.KafkaPublisher
import io.github.nomisRev.kafka.receiver.AutoOffsetReset
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.KafkaSpec
import no.nav.helsemelding.state.config
import no.nav.helsemelding.state.config.Config
import no.nav.helsemelding.state.config.Kafka.SecurityProtocol
import no.nav.helsemelding.state.config.withKafka
import no.nav.helsemelding.state.kafkaReceiver
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

        "Receive messages - one published message" {
            resourceScope {
                turbineScope {
                    val publisher = KafkaPublisher(publisherSettings())
                    val referenceId = Uuid.random()
                    val content = "data".toByteArray()
                    publisher.publishScope {
                        publish(
                            ProducerRecord(
                                config.kafka.topics.dialogMessageOut,
                                referenceId.toString(),
                                content
                            )
                        )
                    }

                    val receiver = MessageReceiver(
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

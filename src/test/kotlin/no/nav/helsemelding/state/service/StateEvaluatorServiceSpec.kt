package no.nav.helsemelding.state.service

import arrow.core.Either.Right
import arrow.core.raise.either
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.evaluator.AppRecTransitionEvaluator
import no.nav.helsemelding.state.evaluator.StateTransitionEvaluator
import no.nav.helsemelding.state.evaluator.TransportStatusTranslator
import no.nav.helsemelding.state.evaluator.TransportTransitionEvaluator
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.DeliveryEvaluationState
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.state.model.MessageDeliveryState.UNCHANGED
import no.nav.helsemelding.state.model.MessageState
import no.nav.helsemelding.state.model.MessageType.DIALOG
import no.nav.helsemelding.state.model.TransportStatus
import no.nav.helsemelding.state.shouldBeLeftWhere
import java.net.URI
import kotlin.time.Clock
import kotlin.uuid.Uuid

class StateEvaluatorServiceSpec : StringSpec(
    {
        val translator = TransportStatusTranslator()

        val transitionEvaluator = StateTransitionEvaluator(
            transportValidator = TransportTransitionEvaluator(),
            appRecValidator = AppRecTransitionEvaluator()
        )

        val service = StateEvaluatorService(
            transportTranslator = translator,
            transitionValidator = transitionEvaluator
        )

        fun createMessageState(
            externalDeliveryState: ExternalDeliveryState,
            appRecStatus: AppRecStatus?
        ) = MessageState(
            externalDeliveryState = externalDeliveryState,
            appRecStatus = appRecStatus,
            id = Uuid.random(),
            messageType = DIALOG,
            externalRefId = Uuid.random(),
            externalMessageUrl = URI.create("http://localhost").toURL(),
            lastStateChange = Clock.System.now(),
            lastPolledAt = null,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        "evaluate(null, null) -> DeliveryEvaluationState(NEW, null)" {
            service.evaluate(null, null) shouldBe DeliveryEvaluationState(
                transport = TransportStatus.NEW,
                appRec = null
            )
        }

        "evaluate(ACKNOWLEDGED, null) -> DeliveryEvaluationState(ACKNOWLEDGED, null)" {
            service.evaluate(ACKNOWLEDGED, null) shouldBe DeliveryEvaluationState(
                transport = TransportStatus.ACKNOWLEDGED,
                appRec = null
            )
        }

        "evaluate(UNCONFIRMED, null) -> DeliveryEvaluationState(PENDING, null)" {
            service.evaluate(ExternalDeliveryState.UNCONFIRMED, null) shouldBe DeliveryEvaluationState(
                transport = TransportStatus.PENDING,
                appRec = null
            )
        }

        "evaluate(ACKNOWLEDGED, OK) -> DeliveryEvaluationState(ACKNOWLEDGED, OK)" {
            service.evaluate(ACKNOWLEDGED, AppRecStatus.OK) shouldBe DeliveryEvaluationState(
                transport = TransportStatus.ACKNOWLEDGED,
                appRec = AppRecStatus.OK
            )
        }

        "evaluate(message) uses message fields (ACKNOWLEDGED + null -> ACKNOWLEDGED, null)" {
            val messageState = createMessageState(ACKNOWLEDGED, null)

            service.evaluate(messageState) shouldBe DeliveryEvaluationState(
                transport = TransportStatus.ACKNOWLEDGED,
                appRec = null
            )
        }

        "determineNextState -> UNCHANGED when resolved states are equal (PENDING -> PENDING)" {
            val old = DeliveryEvaluationState(transport = TransportStatus.PENDING, appRec = null)
            val new = DeliveryEvaluationState(transport = TransportStatus.PENDING, appRec = null)

            either { with(service) { determineNextState(old, new) } } shouldBe Right(UNCHANGED)
        }

        "determineNextState -> UNCHANGED when resolved states are equal (COMPLETED -> COMPLETED)" {
            val old = DeliveryEvaluationState(
                transport = TransportStatus.ACKNOWLEDGED,
                appRec = AppRecStatus.OK
            )
            val new = DeliveryEvaluationState(
                transport = TransportStatus.ACKNOWLEDGED,
                appRec = AppRecStatus.OK
            )

            either { with(service) { determineNextState(old, new) } } shouldBe Right(UNCHANGED)
        }

        "determineNextState -> new resolved state on valid transition (NEW -> PENDING)" {
            val old = DeliveryEvaluationState(transport = TransportStatus.NEW, appRec = null)
            val new = DeliveryEvaluationState(transport = TransportStatus.PENDING, appRec = null)

            either { with(service) { determineNextState(old, new) } } shouldBe Right(PENDING)
        }

        "determineNextState -> new resolved state on valid transition (PENDING -> COMPLETED)" {
            val old = DeliveryEvaluationState(
                transport = TransportStatus.PENDING,
                appRec = null
            )
            val new = DeliveryEvaluationState(
                transport = TransportStatus.ACKNOWLEDGED,
                appRec = AppRecStatus.OK
            )

            either { with(service) { determineNextState(old, new) } } shouldBe Right(COMPLETED)
        }

        "determineNextState raises on illegal resolved transition (PENDING -> NEW)" {
            val old = DeliveryEvaluationState(transport = TransportStatus.PENDING, appRec = null)
            val new = DeliveryEvaluationState(transport = TransportStatus.NEW, appRec = null)

            val result = either { with(service) { determineNextState(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalTransition &&
                    it.from == PENDING &&
                    it.to == NEW
            }
        }

        "determineNextState raises on illegal resolved transition (COMPLETED -> PENDING)" {
            val old = DeliveryEvaluationState(
                transport = TransportStatus.ACKNOWLEDGED,
                appRec = AppRecStatus.OK
            )
            val new = DeliveryEvaluationState(
                transport = TransportStatus.ACKNOWLEDGED,
                appRec = null
            )

            val result = either { with(service) { determineNextState(old, new) } }

            result shouldBeLeftWhere { it is StateTransitionError.IllegalAppRecTransition }
        }

        "determineNextState raises on illegal resolved transition (REJECTED -> COMPLETED)" {
            val old = DeliveryEvaluationState(
                transport = TransportStatus.REJECTED,
                appRec = null
            )
            val new = DeliveryEvaluationState(
                transport = TransportStatus.ACKNOWLEDGED,
                appRec = AppRecStatus.OK
            )

            val result = either { with(service) { determineNextState(old, new) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalTransition &&
                    it.from == REJECTED &&
                    it.to == COMPLETED
            }
        }

        "determineNextState raises on invalid combined state (apprec present but transport not ACKNOWLEDGED)" {
            val old = DeliveryEvaluationState(transport = TransportStatus.NEW, appRec = null)
            val new = DeliveryEvaluationState(transport = TransportStatus.PENDING, appRec = AppRecStatus.OK)

            val result = either { with(service) { determineNextState(old, new) } }

            result shouldBeLeftWhere { it is StateTransitionError.IllegalCombinedState }
        }
    }
)

package no.nav.helsemelding.state.service

import arrow.core.Either.Right
import arrow.core.raise.either
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.StateEvaluationError
import no.nav.helsemelding.state.StateTransitionError
import no.nav.helsemelding.state.evaluator.StateEvaluator
import no.nav.helsemelding.state.evaluator.StateTransitionValidator
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.AppRecStatus.OK
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.state.model.ExternalDeliveryState.UNCONFIRMED
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.state.model.MessageDeliveryState.UNCHANGED
import no.nav.helsemelding.state.model.MessageState
import no.nav.helsemelding.state.model.MessageType.DIALOG
import no.nav.helsemelding.state.shouldBeLeftWhere
import java.net.URI
import kotlin.time.Clock
import kotlin.uuid.Uuid

class StateEvaluatorServiceSpec : StringSpec({

    val evaluator = StateEvaluator()
    val validator = StateTransitionValidator()
    val service = StateEvaluatorService(evaluator, validator)

    "evaluate(null, null) -> NEW" {
        either {
            with(service) { evaluate(null, null) }
        } shouldBe Right(NEW)
    }

    "evaluate(ACKNOWLEDGED, null) -> PENDING" {
        either {
            with(service) { evaluate(ACKNOWLEDGED, null) }
        } shouldBe Right(PENDING)
    }

    "evaluate(ACKNOWLEDGED, OK) -> COMPLETED" {
        either {
            with(service) { evaluate(ACKNOWLEDGED, OK) }
        } shouldBe Right(COMPLETED)
    }

    "evaluate(message) uses message fields (ACKNOWLEDGED + null -> PENDING)" {
        val messageState = createMessageState(ACKNOWLEDGED, null)

        either {
            with(service) { evaluate(messageState) }
        } shouldBe Right(PENDING)
    }

    "evaluate(message) propagates evaluation errors (UNCONFIRMED + OK -> Unresolvable)" {
        val messageState = createMessageState(UNCONFIRMED, OK)

        val result = either {
            with(service) { evaluate(messageState) }
        }

        result shouldBeLeftWhere { it is StateEvaluationError.UnresolvableState }
    }

    "determineNextState -> UNCHANGED when old == new (PENDING -> PENDING)" {
        either {
            with(service) { determineNextState(PENDING, PENDING) }
        } shouldBe Right(UNCHANGED)
    }

    "determineNextState -> UNCHANGED when old == new (COMPLETED -> COMPLETED)" {
        either {
            with(service) { determineNextState(COMPLETED, COMPLETED) }
        } shouldBe Right(UNCHANGED)
    }

    "determineNextState -> newState on valid transition (NEW -> PENDING)" {
        either {
            with(service) { determineNextState(NEW, PENDING) }
        } shouldBe Right(PENDING)
    }

    "determineNextState -> newState on valid transition (PENDING -> COMPLETED)" {
        either {
            with(service) { determineNextState(PENDING, COMPLETED) }
        } shouldBe Right(COMPLETED)
    }

    "determineNextState raises on illegal transition (PENDING -> NEW)" {
        val result = either {
            with(service) { determineNextState(PENDING, NEW) }
        }

        result shouldBeLeftWhere { it is StateTransitionError.IllegalTransition }
    }

    "determineNextState raises on illegal transition (COMPLETED -> PENDING)" {
        val result = either {
            with(service) { determineNextState(COMPLETED, PENDING) }
        }

        result shouldBeLeftWhere { it is StateTransitionError.IllegalTransition }
    }

    "determineNextState raises on illegal transition (REJECTED -> COMPLETED)" {
        val result = either {
            with(service) { determineNextState(REJECTED, COMPLETED) }
        }

        result shouldBeLeftWhere { it is StateTransitionError.IllegalTransition }
    }
})

private fun createMessageState(
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

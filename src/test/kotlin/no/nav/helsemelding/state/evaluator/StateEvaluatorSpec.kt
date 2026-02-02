package no.nav.helsemelding.state.evaluator

import arrow.core.Either.Right
import arrow.core.raise.either
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.StateEvaluationError
import no.nav.helsemelding.state.model.AppRecStatus
import no.nav.helsemelding.state.model.AppRecStatus.OK_ERROR_IN_MESSAGE_PART
import no.nav.helsemelding.state.model.ExternalDeliveryState
import no.nav.helsemelding.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.helsemelding.state.model.ExternalDeliveryState.UNCONFIRMED
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.state.shouldBeLeftWhere

class StateEvaluatorSpec : StringSpec(
    {
        val evaluator = StateEvaluator()

        "Null delivery + null apprec → NEW" {
            either {
                with(evaluator) { evaluate(null, null) }
            } shouldBe Right(NEW)
        }

        "ACK delivery + null apprec → PENDING" {
            either {
                with(evaluator) { evaluate(ACKNOWLEDGED, null) }
            } shouldBe Right(PENDING)
        }

        "UNCONFIRMED delivery + null apprec → PENDING" {
            either {
                with(evaluator) { evaluate(UNCONFIRMED, null) }
            } shouldBe Right(PENDING)
        }

        "ACK delivery + OK apprec → COMPLETED" {
            either {
                with(evaluator) { evaluate(ACKNOWLEDGED, AppRecStatus.OK) }
            } shouldBe Right(COMPLETED)
        }

        "ACK delivery + OK_ERROR_IN_MESSAGE_PART apprec → COMPLETED" {
            either {
                with(evaluator) { evaluate(ACKNOWLEDGED, OK_ERROR_IN_MESSAGE_PART) }
            } shouldBe Right(COMPLETED)
        }

        "ACK delivery + REJECTED apprec → REJECTED" {
            either {
                with(evaluator) { evaluate(ACKNOWLEDGED, AppRecStatus.REJECTED) }
            } shouldBe Right(REJECTED)
        }

        "REJECTED delivery + null apprec → REJECTED" {
            either {
                with(evaluator) { evaluate(ExternalDeliveryState.REJECTED, null) }
            } shouldBe Right(REJECTED)
        }

        "UNCONFIRMED delivery + OK apprec → Unresolvable" {
            val result = either {
                with(evaluator) { evaluate(UNCONFIRMED, AppRecStatus.OK) }
            }

            result shouldBeLeftWhere { it is StateEvaluationError.UnresolvableState }
        }

        "UNCONFIRMED delivery + REJECTED apprec → Unresolvable" {
            val result = either {
                with(evaluator) { evaluate(UNCONFIRMED, AppRecStatus.REJECTED) }
            }

            result shouldBeLeftWhere { it is StateEvaluationError.UnresolvableState }
        }

        "UNCONFIRMED delivery + OK_ERROR_IN_MESSAGE_PART apprec → Unresolvable" {
            val result = either {
                with(evaluator) { evaluate(UNCONFIRMED, OK_ERROR_IN_MESSAGE_PART) }
            }

            result shouldBeLeftWhere { it is StateEvaluationError.UnresolvableState }
        }

        "null delivery + OK apprec → Unresolvable" {
            val result = either {
                with(evaluator) { evaluate(null, AppRecStatus.OK) }
            }

            result shouldBeLeftWhere { it is StateEvaluationError.UnresolvableState }
        }

        "null delivery + REJECTED apprec → Unresolvable" {
            val result = either {
                with(evaluator) { evaluate(null, AppRecStatus.REJECTED) }
            }

            result shouldBeLeftWhere { it is StateEvaluationError.UnresolvableState }
        }
    }
)

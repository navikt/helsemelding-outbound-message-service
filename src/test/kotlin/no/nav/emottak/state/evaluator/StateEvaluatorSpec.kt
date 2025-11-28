package no.nav.emottak.state.evaluator

import arrow.core.Either.Right
import arrow.core.raise.either
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.emottak.state.StateEvaluationError
import no.nav.emottak.state.model.AppRecStatus
import no.nav.emottak.state.model.ExternalDeliveryState
import no.nav.emottak.state.model.ExternalDeliveryState.ACKNOWLEDGED
import no.nav.emottak.state.model.ExternalDeliveryState.UNCONFIRMED
import no.nav.emottak.state.model.MessageDeliveryState.COMPLETED
import no.nav.emottak.state.model.MessageDeliveryState.NEW
import no.nav.emottak.state.model.MessageDeliveryState.PENDING
import no.nav.emottak.state.model.MessageDeliveryState.REJECTED
import no.nav.emottak.state.shouldBeEitherLeftWhere

class StateEvaluatorSpec : StringSpec(
    {
        val evaluator = StateEvaluator()

        "Null delivery + null apprec → NEW" {
            either {
                with(evaluator) { evaluate(null, null) }
            } shouldBe Right(NEW)
        }

        "ACK + null → PENDING" {
            either {
                with(evaluator) { evaluate(ACKNOWLEDGED, null) }
            } shouldBe Right(PENDING)
        }

        "UNCONFIRMED + null → PENDING" {
            either {
                with(evaluator) { evaluate(UNCONFIRMED, null) }
            } shouldBe Right(PENDING)
        }

        "ACK + AppRec OK → COMPLETED" {
            either {
                with(evaluator) { evaluate(ACKNOWLEDGED, AppRecStatus.OK) }
            } shouldBe Right(COMPLETED)
        }

        "ACK + AppRec OK_ERROR_IN_MESSAGE_PART → COMPLETED" {
            either {
                with(evaluator) { evaluate(ACKNOWLEDGED, AppRecStatus.OK_ERROR_IN_MESSAGE_PART) }
            } shouldBe Right(COMPLETED)
        }

        "ACK + AppRec REJECTED → REJECTED" {
            either {
                with(evaluator) { evaluate(ACKNOWLEDGED, AppRecStatus.REJECTED) }
            } shouldBe Right(REJECTED)
        }

        "REJECTED + null → REJECTED" {
            either {
                with(evaluator) { evaluate(ExternalDeliveryState.REJECTED, null) }
            } shouldBe Right(REJECTED)
        }

        "UNCONFIRMED + AppRec OK → Unresolvable" {
            val result = either {
                with(evaluator) { evaluate(UNCONFIRMED, AppRecStatus.OK) }
            }

            result shouldBeEitherLeftWhere { it is StateEvaluationError.UnresolvableState }
        }

        "UNCONFIRMED + AppRec REJECTED → Unresolvable" {
            val result = either {
                with(evaluator) { evaluate(UNCONFIRMED, AppRecStatus.REJECTED) }
            }

            result shouldBeEitherLeftWhere { it is StateEvaluationError.UnresolvableState }
        }

        "null + AppRec OK → Unresolvable" {
            val result = either {
                with(evaluator) { evaluate(null, AppRecStatus.OK) }
            }

            result shouldBeEitherLeftWhere { it is StateEvaluationError.UnresolvableState }
        }

        "null + AppRec REJECTED → Unresolvable" {
            val result = either {
                with(evaluator) { evaluate(null, AppRecStatus.REJECTED) }
            }

            result shouldBeEitherLeftWhere { it is StateEvaluationError.UnresolvableState }
        }
    }
)

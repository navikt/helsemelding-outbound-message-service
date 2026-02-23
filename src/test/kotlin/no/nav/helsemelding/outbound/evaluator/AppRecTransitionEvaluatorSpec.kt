package no.nav.helsemelding.outbound.evaluator

import arrow.core.Either.Right
import arrow.core.raise.either
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.outbound.StateTransitionError
import no.nav.helsemelding.outbound.model.AppRecStatus.OK
import no.nav.helsemelding.outbound.model.AppRecStatus.OK_ERROR_IN_MESSAGE_PART
import no.nav.helsemelding.outbound.model.AppRecStatus.REJECTED
import no.nav.helsemelding.outbound.model.isNull
import no.nav.helsemelding.outbound.model.isOk
import no.nav.helsemelding.outbound.model.isOkErrorInMessagePart
import no.nav.helsemelding.outbound.model.isRejected
import no.nav.helsemelding.outbound.shouldBeLeftWhere

class AppRecTransitionEvaluatorSpec : StringSpec(
    {
        val evaluator = AppRecTransitionEvaluator()

        "null → null is allowed" {
            either { with(evaluator) { evaluate(null, null) } } shouldBe Right(Unit)
        }

        "OK → OK is allowed" {
            either { with(evaluator) { evaluate(OK, OK) } } shouldBe Right(Unit)
        }

        "OK_ERROR_IN_MESSAGE_PART → OK_ERROR_IN_MESSAGE_PART is allowed" {
            either {
                with(evaluator) {
                    evaluate(OK_ERROR_IN_MESSAGE_PART, OK_ERROR_IN_MESSAGE_PART)
                }
            } shouldBe Right(Unit)
        }

        "REJECTED → REJECTED is allowed" {
            either { with(evaluator) { evaluate(REJECTED, REJECTED) } } shouldBe Right(Unit)
        }

        "null → OK is allowed" {
            either { with(evaluator) { evaluate(null, OK) } } shouldBe Right(Unit)
        }

        "null → OK_ERROR_IN_MESSAGE_PART is allowed" {
            either { with(evaluator) { evaluate(null, OK_ERROR_IN_MESSAGE_PART) } } shouldBe Right(Unit)
        }

        "null → REJECTED is allowed" {
            either { with(evaluator) { evaluate(null, REJECTED) } } shouldBe Right(Unit)
        }

        "OK → null is illegal" {
            val result = either { with(evaluator) { evaluate(OK, null) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from.isOk() &&
                    it.to.isNull()
            }
        }

        "OK → OK_ERROR_IN_MESSAGE_PART is illegal" {
            val result = either { with(evaluator) { evaluate(OK, OK_ERROR_IN_MESSAGE_PART) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from.isOk() &&
                    it.to.isOkErrorInMessagePart()
            }
        }

        "OK → REJECTED is illegal" {
            val result = either { with(evaluator) { evaluate(OK, REJECTED) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from.isOk() &&
                    it.to.isRejected()
            }
        }

        "OK_ERROR_IN_MESSAGE_PART → OK is illegal" {
            val result = either {
                with(evaluator) { evaluate(OK_ERROR_IN_MESSAGE_PART, OK) }
            }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from.isOkErrorInMessagePart() &&
                    it.to.isOk()
            }
        }

        "OK_ERROR_IN_MESSAGE_PART → REJECTED is illegal" {
            val result = either { with(evaluator) { evaluate(OK_ERROR_IN_MESSAGE_PART, REJECTED) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from.isOkErrorInMessagePart() &&
                    it.to.isRejected()
            }
        }

        "REJECTED → OK is illegal" {
            val result = either { with(evaluator) { evaluate(REJECTED, OK) } }

            result shouldBeLeftWhere {
                it is StateTransitionError.IllegalAppRecTransition &&
                    it.from.isRejected() &&
                    it.to.isOk()
            }
        }
    }
)

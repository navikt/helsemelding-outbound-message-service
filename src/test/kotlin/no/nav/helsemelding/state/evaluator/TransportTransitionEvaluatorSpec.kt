package no.nav.helsemelding.state.evaluator

import arrow.core.Either.Right
import arrow.core.raise.either
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.helsemelding.state.StateTransitionError.IllegalTransition
import no.nav.helsemelding.state.model.MessageDeliveryState.COMPLETED
import no.nav.helsemelding.state.model.MessageDeliveryState.INVALID
import no.nav.helsemelding.state.model.MessageDeliveryState.NEW
import no.nav.helsemelding.state.model.MessageDeliveryState.PENDING
import no.nav.helsemelding.state.model.MessageDeliveryState.REJECTED
import no.nav.helsemelding.state.model.isNew
import no.nav.helsemelding.state.model.isPending
import no.nav.helsemelding.state.shouldBeLeftWhere

class TransportTransitionEvaluatorSpec : StringSpec(
    {
        val evaluator = TransportTransitionEvaluator()

        "NEW → NEW is allowed" {
            either {
                with(evaluator) { evaluate(NEW, NEW) }
            } shouldBe Right(Unit)
        }

        "NEW → PENDING is allowed" {
            either {
                with(evaluator) { evaluate(NEW, PENDING) }
            } shouldBe Right(Unit)
        }

        "NEW → COMPLETED is allowed" {
            either {
                with(evaluator) { evaluate(NEW, COMPLETED) }
            } shouldBe Right(Unit)
        }

        "NEW → REJECTED is allowed" {
            either {
                with(evaluator) { evaluate(NEW, REJECTED) }
            } shouldBe Right(Unit)
        }

        "PENDING → PENDING is allowed" {
            either {
                with(evaluator) { evaluate(PENDING, PENDING) }
            } shouldBe Right(Unit)
        }

        "PENDING → COMPLETED is allowed" {
            either {
                with(evaluator) { evaluate(PENDING, COMPLETED) }
            } shouldBe Right(Unit)
        }

        "PENDING → REJECTED is allowed" {
            either {
                with(evaluator) { evaluate(PENDING, REJECTED) }
            } shouldBe Right(Unit)
        }

        "PENDING → NEW is illegal" {
            val result = either {
                with(evaluator) { evaluate(PENDING, NEW) }
            }

            result shouldBeLeftWhere {
                it is IllegalTransition &&
                    it.from.isPending() &&
                    it.to.isNew()
            }
        }

        "COMPLETED → COMPLETED is allowed" {
            either {
                with(evaluator) { evaluate(COMPLETED, COMPLETED) }
            } shouldBe Right(Unit)
        }

        "COMPLETED → PENDING is illegal" {
            val result = either {
                with(evaluator) { evaluate(COMPLETED, PENDING) }
            }

            result shouldBeLeftWhere { it is IllegalTransition }
        }

        "COMPLETED → REJECTED is illegal" {
            val result = either {
                with(evaluator) { evaluate(COMPLETED, REJECTED) }
            }

            result shouldBeLeftWhere { it is IllegalTransition }
        }

        "COMPLETED → NEW is illegal" {
            val result = either {
                with(evaluator) { evaluate(COMPLETED, NEW) }
            }

            result shouldBeLeftWhere { it is IllegalTransition }
        }

        "REJECTED → REJECTED is allowed" {
            either {
                with(evaluator) { evaluate(REJECTED, REJECTED) }
            } shouldBe Right(Unit)
        }

        "REJECTED → NEW is illegal" {
            val result = either {
                with(evaluator) { evaluate(REJECTED, NEW) }
            }

            result shouldBeLeftWhere { it is IllegalTransition }
        }

        "REJECTED → PENDING is illegal" {
            val result = either {
                with(evaluator) { evaluate(REJECTED, PENDING) }
            }

            result shouldBeLeftWhere { it is IllegalTransition }
        }

        "REJECTED → COMPLETED is illegal" {
            val result = either {
                with(evaluator) { evaluate(REJECTED, COMPLETED) }
            }

            result shouldBeLeftWhere { it is IllegalTransition }
        }

        "INVALID → INVALID is allowed" {
            either {
                with(evaluator) { evaluate(INVALID, INVALID) }
            } shouldBe Right(Unit)
        }

        "INVALID → NEW is illegal" {
            either {
                with(evaluator) { evaluate(INVALID, NEW) }
            } shouldBeLeftWhere { it is IllegalTransition }
        }

        "INVALID → PENDING is illegal" {
            either {
                with(evaluator) { evaluate(INVALID, PENDING) }
            } shouldBeLeftWhere { it is IllegalTransition }
        }

        "INVALID → COMPLETED is illegal" {
            either {
                with(evaluator) { evaluate(INVALID, COMPLETED) }
            } shouldBeLeftWhere { it is IllegalTransition }
        }

        "INVALID → REJECTED is illegal" {
            either {
                with(evaluator) { evaluate(INVALID, REJECTED) }
            } shouldBeLeftWhere { it is IllegalTransition }
        }
    }
)

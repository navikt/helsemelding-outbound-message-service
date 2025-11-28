package no.nav.emottak.state.evaluator

import arrow.core.Either.Right
import arrow.core.raise.either
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import no.nav.emottak.state.StateTransitionError.IllegalTransition
import no.nav.emottak.state.model.MessageDeliveryState.COMPLETED
import no.nav.emottak.state.model.MessageDeliveryState.NEW
import no.nav.emottak.state.model.MessageDeliveryState.PENDING
import no.nav.emottak.state.model.MessageDeliveryState.REJECTED
import no.nav.emottak.state.shouldBeEitherLeftWhere

class StateTransitionValidatorSpec : StringSpec(
    {
        val validator = StateTransitionValidator()

        "NEW → NEW is allowed" {
            either {
                with(validator) { validate(NEW, NEW) }
            } shouldBe Right(Unit)
        }

        "NEW → PENDING is allowed" {
            either {
                with(validator) { validate(NEW, PENDING) }
            } shouldBe Right(Unit)
        }

        "NEW → COMPLETED is allowed" {
            either {
                with(validator) { validate(NEW, COMPLETED) }
            } shouldBe Right(Unit)
        }

        "NEW → REJECTED is allowed" {
            either {
                with(validator) { validate(NEW, REJECTED) }
            } shouldBe Right(Unit)
        }

        "PENDING → PENDING is allowed" {
            either {
                with(validator) { validate(PENDING, PENDING) }
            } shouldBe Right(Unit)
        }

        "PENDING → COMPLETED is allowed" {
            either {
                with(validator) { validate(PENDING, COMPLETED) }
            } shouldBe Right(Unit)
        }

        "PENDING → REJECTED is allowed" {
            either {
                with(validator) { validate(PENDING, REJECTED) }
            } shouldBe Right(Unit)
        }

        "PENDING → NEW is illegal" {
            val result = either {
                with(validator) { validate(PENDING, NEW) }
            }

            result shouldBeEitherLeftWhere {
                it is IllegalTransition &&
                    it.from == PENDING &&
                    it.to == NEW
            }
        }

        "COMPLETED → COMPLETED is allowed" {
            either {
                with(validator) { validate(COMPLETED, COMPLETED) }
            } shouldBe Right(Unit)
        }

        "COMPLETED → PENDING is illegal" {
            val result = either {
                with(validator) { validate(COMPLETED, PENDING) }
            }

            result shouldBeEitherLeftWhere { it is IllegalTransition }
        }

        "COMPLETED → REJECTED is illegal" {
            val result = either {
                with(validator) { validate(COMPLETED, REJECTED) }
            }

            result shouldBeEitherLeftWhere { it is IllegalTransition }
        }

        "COMPLETED → NEW is illegal" {
            val result = either {
                with(validator) { validate(COMPLETED, NEW) }
            }

            result shouldBeEitherLeftWhere { it is IllegalTransition }
        }

        "REJECTED → REJECTED is allowed" {
            either {
                with(validator) { validate(REJECTED, REJECTED) }
            } shouldBe Right(Unit)
        }

        "REJECTED → NEW is illegal" {
            val result = either {
                with(validator) { validate(REJECTED, NEW) }
            }

            result shouldBeEitherLeftWhere { it is IllegalTransition }
        }

        "REJECTED → PENDING is illegal" {
            val result = either {
                with(validator) { validate(REJECTED, PENDING) }
            }

            result shouldBeEitherLeftWhere { it is IllegalTransition }
        }

        "REJECTED → COMPLETED is illegal" {
            val result = either {
                with(validator) { validate(REJECTED, COMPLETED) }
            }

            result shouldBeEitherLeftWhere { it is IllegalTransition }
        }
    }
)

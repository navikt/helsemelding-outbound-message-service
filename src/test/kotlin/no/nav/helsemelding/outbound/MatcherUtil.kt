package no.nav.helsemelding.outbound

import arrow.core.Either
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

private fun beEqualInstant(expected: Instant): Matcher<Instant> = object : Matcher<Instant> {
    override fun test(value: Instant): MatcherResult {
        val v = value.truncateToMillis()
        val e = expected.truncateToMillis()
        return MatcherResult(
            v == e,
            { "Expected Instant <$e> but got <$v>" },
            { "Instants should not have been equal" }
        )
    }
}

infix fun Instant.shouldBeInstant(expected: Instant): Any = this shouldBe beEqualInstant(expected)

infix fun <L> Either<L, *>.shouldBeLeftWhere(predicate: (L) -> Boolean) {
    this.isLeft() shouldBe true
    predicate(this.swap().getOrNull()!!) shouldBe true
}

inline fun <reified R> Either<*, *>.shouldBeRightOfType(
    crossinline block: (R) -> Unit = {}
) {
    this.isRight() shouldBe true
    block(this.getOrNull() as R)
}

inline fun <reified L> Either<*, *>.shouldBeLeftOfType(
    crossinline block: (L) -> Unit = {}
) {
    this.isLeft() shouldBe true
    block(this.swap().getOrNull() as L)
}

package com.yeshuwahane.fairsplit.common.money

@JvmInline
value class Money(val amountMinor: Long) : Comparable<Money> {
    init {
        require(amountMinor >= 0) { "Money amountMinor must be non-negative: $amountMinor" }
    }

    operator fun plus(other: Money): Money = Money(Math.addExact(this.amountMinor, other.amountMinor))

    operator fun minus(other: Money): Money {
        require(this.amountMinor >= other.amountMinor) {
            "Subtraction would result in negative money: ${this.amountMinor} - ${other.amountMinor}"
        }
        return Money(this.amountMinor - other.amountMinor)
    }

    override fun compareTo(other: Money): Int = this.amountMinor.compareTo(other.amountMinor)

    fun isZero(): Boolean = amountMinor == 0L

    companion object {
        val ZERO = Money(0L)

        fun of(amountMinor: Long): Money = Money(amountMinor)

        fun fromMajor(major: Long, minorUnitsPerMajor: Int = 100): Money =
            Money(Math.multiplyExact(major, minorUnitsPerMajor.toLong()))
    }
}

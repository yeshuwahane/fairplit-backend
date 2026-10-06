package com.yeshuwahane.fairsplit.common.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun testMoneyZeroAndCreation() {
        val zero = Money.ZERO
        assertTrue(zero.isZero())
        assertEquals(0L, zero.amountMinor)

        val money = Money.of(10050L)
        assertEquals(10050L, money.amountMinor)

        val fromMajor = Money.fromMajor(100)
        assertEquals(10000L, fromMajor.amountMinor)
    }

    @Test
    fun testMoneyAddition() {
        val m1 = Money.of(5000L)
        val m2 = Money.of(2550L)
        val sum = m1 + m2
        assertEquals(7550L, sum.amountMinor)
    }

    @Test
    fun testMoneySubtraction() {
        val m1 = Money.of(5000L)
        val m2 = Money.of(2000L)
        val diff = m1 - m2
        assertEquals(3000L, diff.amountMinor)
    }

    @Test
    fun testMoneyNegativeDisallowed() {
        assertFailsWith<IllegalArgumentException> {
            Money(-100L)
        }

        val m1 = Money.of(1000L)
        val m2 = Money.of(2000L)
        assertFailsWith<IllegalArgumentException> {
            m1 - m2
        }
    }

    @Test
    fun testMoneyComparison() {
        val m1 = Money.of(1000L)
        val m2 = Money.of(2000L)
        val m3 = Money.of(1000L)

        assertTrue(m1 < m2)
        assertTrue(m2 > m1)
        assertEquals(0, m1.compareTo(m3))
    }
}

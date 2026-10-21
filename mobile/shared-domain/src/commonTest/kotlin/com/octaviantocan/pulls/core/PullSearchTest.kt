package com.octaviantocan.pulls.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PullSearchTest {
    @Test
    fun aBareNumberWithOrWithoutAHashIsAPullRequestNumber() {
        assertEquals(42, searchNumber("42"))
        assertEquals(42, searchNumber(" #42 "))
    }

    @Test
    fun textThatIsMoreThanANumberIsNot() {
        assertNull(searchNumber("owner/repo#42"))
        assertNull(searchNumber("fix 42"))
        assertNull(searchNumber("0"))
        assertNull(searchNumber(""))
        assertNull(searchNumber("12345678"))
    }

    @Test
    fun aNumberNamesThePullRequestWhenOnlyOneListedHasIt() {
        val listed = listOf(ListedPull("a", "x", 1), ListedPull("a", "x", 2), ListedPull("b", "y", 3))
        assertEquals(ListedPull("a", "x", 2), listedPullByNumber(2, listed))
    }

    @Test
    fun aNumberTwoRepositoriesShareOrNoneHasNamesNothing() {
        val listed = listOf(ListedPull("a", "x", 7), ListedPull("b", "y", 7))
        assertNull(listedPullByNumber(7, listed))
        assertNull(listedPullByNumber(8, listed))
    }

    @Test
    fun aPullRequestListedTwiceStillCountsOnce() {
        assertEquals(ListedPull("a", "x", 7), listedPullByNumber(7, listOf(ListedPull("a", "x", 7), ListedPull("a", "x", 7))))
    }
}

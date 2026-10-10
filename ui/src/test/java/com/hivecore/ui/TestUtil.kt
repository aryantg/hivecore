package com.hivecore.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Runs every pending view model coroutine, including delayed ones (up to a minute of virtual time). The view models
 * launch into `backgroundScope`, and `advanceUntilIdle()` stops as soon as no foreground task is left, so it does not
 * drive them; `advanceTimeBy` + `runCurrent` do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.settle(virtualMs: Long = 60_000) {
    runCurrent()
    advanceTimeBy(virtualMs)
    runCurrent()
}

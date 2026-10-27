package com.v2ray.ang.ui.qsn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The animated background must stop when nobody can see it or motion is off. */
class BackgroundMotionTest {

    @Test
    fun `it animates while the screen is resumed`() {
        assertTrue(shouldAnimateBackground(resumed = true, reduceMotion = false))
    }

    @Test
    fun `it stops in the background`() {
        assertFalse(shouldAnimateBackground(resumed = false, reduceMotion = false))
    }

    @Test
    fun `it stops when reduced motion is requested`() {
        assertFalse(shouldAnimateBackground(resumed = true, reduceMotion = true))
        assertFalse(shouldAnimateBackground(resumed = false, reduceMotion = true))
    }
}

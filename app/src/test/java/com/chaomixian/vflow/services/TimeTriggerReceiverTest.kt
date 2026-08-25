package com.chaomixian.vflow.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeTriggerReceiverTest {

    @Test
    fun `time trigger is duplicate within dedup window`() {
        assertTrue(TimeTriggerReceiver.isDuplicateTimeTrigger(1_000L, 60_999L))
    }

    @Test
    fun `time trigger is accepted at dedup window boundary`() {
        assertFalse(TimeTriggerReceiver.isDuplicateTimeTrigger(1_000L, 61_000L))
    }

    @Test
    fun `first and clock adjusted triggers are accepted`() {
        assertFalse(TimeTriggerReceiver.isDuplicateTimeTrigger(0L, 1_000L))
        assertFalse(TimeTriggerReceiver.isDuplicateTimeTrigger(2_000L, 1_000L))
    }
}

package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HookStatusClientTest {
    @Test fun rejectedSystemHookClearsStatusWithoutCrashing() {
        val active = HookStatusClient.Status(true, 123L, 1, 1000)
        HookStatusClient.refreshStatus { active }
        assertEquals(active, HookStatusClient.status.value)

        // Regression: this is the exception reported by the connected Android 17 device.
        HookStatusClient.refreshStatus { throw SecurityException("unauthorized") }
        assertNull(HookStatusClient.status.value)
    }

    @Test fun missingAndIncompatibleHooksRemainOptional() {
        HookStatusClient.refreshStatus { null }
        assertNull(HookStatusClient.status.value)
        HookStatusClient.refreshStatus { throw IllegalStateException("invalid Binder reply") }
        assertNull(HookStatusClient.status.value)
    }
}

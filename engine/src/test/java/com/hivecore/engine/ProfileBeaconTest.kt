package com.hivecore.engine

import com.hivecore.core.Profile
import com.hivecore.core.fakes.FakeWorkerPolicy
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MsgType
import com.hivecore.mesh.ProfileBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBeaconTest {

    @Test fun heartbeatEvery15sAndOnPolicyChange() = runTest {
        val sent = mutableListOf<Profile>()
        val policy = FakeWorkerPolicy(Profile("me", 0L, workerOptIn = false))
        val depth = MutableStateFlow(2)
        val b = ProfileBeacon("me", policy, { t, body -> assertEquals(MsgType.PROFILE, t); sent += MeshJson.decode<ProfileBody>(body).profile },
            clock = { testScheduler.currentTime }, queueDepth = depth)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        b.start(scope)
        runCurrent()
        assertEquals(1, sent.size)                          // the current profile, right away
        assertEquals(2, sent[0].queueDepth)
        advanceTimeBy(15_001)
        assertEquals(2, sent.size)
        policy.setOptIn(true)
        runCurrent()
        assertEquals(3, sent.size)                          // eligibility changed: sent at once
        assertTrue(sent.last().workerOptIn)
        advanceTimeBy(30_000)
        assertEquals(5, sent.size)
        scope.cancel()
    }

    @Test fun keepsLatestPerNodeAndDropsStaleOnes() = runTest {
        var now = 1_000_000L
        val b = ProfileBeacon("me", FakeWorkerPolicy(Profile("me", 0L)), { _, _ -> }, clock = { now })
        b.onProfile("hr", MeshJson.encode(ProfileBody(Profile("hr", ts = 10, thermal = 1))))
        b.onProfile("hr", MeshJson.encode(ProfileBody(Profile("hr", ts = 5, thermal = 3))))      // older: ignored
        b.onProfile("sales", MeshJson.encode(ProfileBody(Profile("hr", ts = 20))))               // names another node: ignored
        assertEquals(1, b.profileOf("hr")!!.thermal)
        assertEquals(listOf("hr", "me"), b.snapshot().map { it.nodeId })
        now += 45_000
        assertEquals(listOf("hr", "me"), b.snapshot().map { it.nodeId })
        now += 1
        assertEquals(listOf("me"), b.snapshot().map { it.nodeId })
        assertNull(b.profileOf("hr"))
        assertEquals(now, b.self().ts)
    }
}

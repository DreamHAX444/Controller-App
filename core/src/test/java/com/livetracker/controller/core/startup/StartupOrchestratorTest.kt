package com.livetracker.controller.core.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupOrchestratorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createOrchestrator(
        steps: List<StartupStep>,
        timeoutMs: Long = 10_000L
    ): StartupOrchestrator {
        return StartupOrchestrator(
            steps = steps,
            scope = testScope,
            defaultDispatcher = testDispatcher,
            stepTimeoutMs = timeoutMs
        )
    }

    // === Initial State ===

    @Test
    fun `initial state is Idle`() {
        val orchestrator = createOrchestrator(emptyList())
        assertEquals(StartupState.Idle, orchestrator.state.value)
    }

    // === Successful Initialization ===

    @Test
    fun `single step completes to Ready`() = testScope.runTest {
        var executed = false
        val orchestrator = createOrchestrator(
            listOf(StartupStep("Step 1") { executed = true })
        )

        orchestrator.start()
        advanceUntilIdle()

        assertTrue(executed)
        assertEquals(StartupState.Ready, orchestrator.state.value)
    }

    @Test
    fun `multiple independent steps all execute`() = testScope.runTest {
        val executed = mutableListOf<String>()
        val orchestrator = createOrchestrator(
            listOf(
                StartupStep("Step A") { executed.add("A") },
                StartupStep("Step B") { executed.add("B") },
                StartupStep("Step C") { executed.add("C") }
            )
        )

        orchestrator.start()
        advanceUntilIdle()

        assertEquals(3, executed.size)
        assertTrue(executed.containsAll(listOf("A", "B", "C")))
        assertEquals(StartupState.Ready, orchestrator.state.value)
    }

    @Test
    fun `dependent steps execute in correct order`() = testScope.runTest {
        val order = mutableListOf<String>()
        val orchestrator = createOrchestrator(
            listOf(
                StartupStep("Foundation") { order.add("foundation") },
                StartupStep("Auth", dependsOn = listOf("Foundation")) { order.add("auth") },
                StartupStep("Map", dependsOn = listOf("Auth")) { order.add("map") }
            )
        )

        orchestrator.start()
        advanceUntilIdle()

        assertEquals(listOf("foundation", "auth", "map"), order)
        assertEquals(StartupState.Ready, orchestrator.state.value)
    }

    @Test
    fun `diamond dependency graph resolves correctly`() = testScope.runTest {
        val order = mutableListOf<String>()
        val orchestrator = createOrchestrator(
            listOf(
                StartupStep("Base") { order.add("base") },
                StartupStep("Left", dependsOn = listOf("Base")) { order.add("left") },
                StartupStep("Right", dependsOn = listOf("Base")) { order.add("right") },
                StartupStep("Final", dependsOn = listOf("Left", "Right")) { order.add("final") }
            )
        )

        orchestrator.start()
        advanceUntilIdle()

        assertEquals("base", order.first())
        assertEquals("final", order.last())
        assertTrue(order.indexOf("left") < order.indexOf("final"))
        assertTrue(order.indexOf("right") < order.indexOf("final"))
        assertEquals(StartupState.Ready, orchestrator.state.value)
    }

    // === Progress Tracking ===

    @Test
    fun `progress state emits Loading during execution`() = testScope.runTest {
        val states = mutableListOf<StartupState>()
        val orchestrator = createOrchestrator(
            listOf(
                StartupStep("Step 1") { /* fast */ },
                StartupStep("Step 2") { /* fast */ }
            )
        )

        // Collect states before starting
        val job = testScope.launch {
            orchestrator.state.collect { states.add(it) }
        }

        orchestrator.start()
        advanceUntilIdle()
        job.cancel()

        // Should have transitioned through Idle -> Loading(s) -> Ready
        assertTrue(states.any { it is StartupState.Idle })
        assertTrue(states.any { it is StartupState.Loading })
        assertTrue(states.any { it is StartupState.Ready })
    }

    @Test
    fun `progress never goes backward`() = testScope.runTest {
        val progressValues = mutableListOf<Float>()
        val orchestrator = createOrchestrator(
            listOf(
                StartupStep("A") { /* fast */ },
                StartupStep("B") { /* fast */ },
                StartupStep("C") { /* fast */ }
            )
        )

        val job = testScope.launch {
            orchestrator.state.collect { state ->
                if (state is StartupState.Loading) {
                    progressValues.add(state.progress)
                }
            }
        }

        orchestrator.start()
        advanceUntilIdle()
        job.cancel()

        // Verify monotonically non-decreasing
        for (i in 1 until progressValues.size) {
            assertTrue(
                "Progress decreased: ${progressValues[i - 1]} -> ${progressValues[i]}",
                progressValues[i] >= progressValues[i - 1]
            )
        }
    }

    // === Idempotency ===

    @Test
    fun `calling start twice does not run steps twice`() = testScope.runTest {
        var executionCount = 0
        val orchestrator = createOrchestrator(
            listOf(StartupStep("Counted") { executionCount++ })
        )

        orchestrator.start()
        orchestrator.start() // Second call should be no-op
        advanceUntilIdle()

        assertEquals(1, executionCount)
    }

    // === Failure Handling ===

    @Test
    fun `step failure with dependents causes RecoverableFailure`() = testScope.runTest {
        val orchestrator = createOrchestrator(
            listOf(
                StartupStep("Critical") { throw RuntimeException("Boom") },
                StartupStep("Dependent", dependsOn = listOf("Critical")) { /* won't run */ }
            )
        )

        orchestrator.start()
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue("Expected RecoverableFailure but got $state", state is StartupState.RecoverableFailure)
        assertEquals("Critical", (state as StartupState.RecoverableFailure).failedStep)
    }

    @Test
    fun `step failure without dependents allows completion`() = testScope.runTest {
        var otherRan = false
        val orchestrator = createOrchestrator(
            listOf(
                StartupStep("Optional") { throw RuntimeException("Non-critical") },
                StartupStep("Essential") { otherRan = true }
            )
        )

        orchestrator.start()
        advanceUntilIdle()

        assertTrue(otherRan)
        assertEquals(StartupState.Ready, orchestrator.state.value)
    }

    // === Timeout ===

    @Test
    fun `step exceeding timeout is treated as failure`() = testScope.runTest {
        val orchestrator = createOrchestrator(
            steps = listOf(
                StartupStep("Slow") { delay(20_000L) }, // 20s > 5s timeout
                StartupStep("Dep", dependsOn = listOf("Slow")) { /* won't run */ }
            ),
            timeoutMs = 5_000L
        )

        orchestrator.start()
        advanceUntilIdle()

        val state = orchestrator.state.value
        assertTrue("Expected RecoverableFailure but got $state", state is StartupState.RecoverableFailure)
    }

    // === Retry ===

    @Test
    fun `retry after RecoverableFailure re-runs failed steps`() = testScope.runTest {
        var attempt = 0
        val orchestrator = createOrchestrator(
            listOf(
                StartupStep("Flaky") {
                    attempt++
                    if (attempt == 1) throw RuntimeException("First attempt fails")
                },
                StartupStep("After", dependsOn = listOf("Flaky")) { /* needs Flaky */ }
            )
        )

        orchestrator.start()
        advanceUntilIdle()
        assertTrue(orchestrator.state.value is StartupState.RecoverableFailure)

        orchestrator.retry()
        advanceUntilIdle()

        assertEquals(StartupState.Ready, orchestrator.state.value)
        assertEquals(2, attempt)
    }

    // === Empty Steps ===

    @Test
    fun `empty step list goes straight to Ready`() = testScope.runTest {
        val orchestrator = createOrchestrator(emptyList())
        orchestrator.start()
        advanceUntilIdle()
        assertEquals(StartupState.Ready, orchestrator.state.value)
    }
}

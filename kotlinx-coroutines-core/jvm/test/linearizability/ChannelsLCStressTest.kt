/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:Suppress("unused")

package kotlinx.coroutines.linearizability

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.channels.TestChannelKind.*
import kotlinx.coroutines.selects.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import org.junit.runner.*
import org.junit.runners.*
import kotlin.reflect.jvm.*

@Param.Params(
    Param(name = "value", gen = IntGen::class, conf = "1:5"),
    Param(name = "closeToken", gen = IntGen::class, conf = "1:3")
)
class ChannelsLCStressTestImpl: VerifierState() {
    private val c = channelKind.create()

    @Operation
    suspend fun send(@Param(name = "value") value: Int) = try {
        c.send(value)
    } catch (e : NumberedCancellationException) {
        e.testResult
    }

    @Operation
    fun offer(@Param(name = "value") value: Int) = try {
        c.offer(value)
    } catch (e : NumberedCancellationException) {
        e.testResult
    }

    @Operation
    suspend fun sendViaSelect(@Param(name = "value") value: Int) = try {
        select<Unit> { c.onSend(value) {} }
    } catch (e : NumberedCancellationException) {
        e.testResult
    }

    @Operation
    suspend fun receive() = try {
        c.receive()
    } catch (e : NumberedCancellationException) {
        e.testResult
    }

    @Operation
    fun poll() = try {
        c.poll()
    } catch (e : NumberedCancellationException) {
        e.testResult
    }

    @Operation
    suspend fun receiveViaSelect() = try {
        select<Int> { c.onReceive { it } }
    } catch (e : NumberedCancellationException) {
        e.testResult
    }

    @Operation
    fun close(@Param(name = "closeToken") token: Int) = c.close(NumberedCancellationException(token))

    // TODO: this operation should be (and can be!) linearizable, but is not
    // @Operation
    fun cancel(@Param(name = "closeToken") token: Int) = c.cancel(NumberedCancellationException(token))

    @Operation
    fun isClosedForReceive() = c.isClosedForReceive

    @Operation
    fun isClosedForSend() = c.isClosedForSend

    // TODO: this operation should be (and can be!) linearizable, but is not
    // @Operation
    fun isEmpty() = c.isEmpty

    override fun extractState(): Any {
        val state = mutableListOf<Any>()
        while (true) {
            val x = poll() ?: break // no elements
            state.add(x)
            if (x is String) break // closed/cancelled
        }
        return state
    }
}

private lateinit var channelKind: TestChannelKind

private class NumberedCancellationException(number: Int): CancellationException() {
    val testResult = "Closed($number)"
}

@RunWith(Parameterized::class)
class ChannelsLCStressTest(kind: TestChannelKind): TestBase() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = listOf(RENDEZVOUS, ARRAY_1, ARRAY_2, ARRAY_10, LINKED_LIST, CONFLATED)
        // TODO: ChannelViaBroadcast options fail, should be fixed
        // fun parameters() = TestChannelKind.values().map { arrayOf(it) }
    }

    init {
        channelKind = kind
    }

    @Test
    fun test() = LCStressOptionsDefault()
        .actorsBefore(0)
        .check(ChannelsLCStressTestImpl::class)

    @Test
    fun testClose() = StressOptions()
        .iterations(1)
        .invocationsPerIteration(100_000 * stressTestMultiplier)
        .executionGenerator(CloseTestScenarioGenerator::class.java)
        .check(ChannelsLCStressTestImpl::class)
}

class CloseTestScenarioGenerator(testCfg: CTestConfiguration, testStructure: CTestStructure): ExecutionGenerator(testCfg, testStructure) {
    override fun nextExecution(): ExecutionScenario {
        return ExecutionScenario(
            emptyList(),
            listOf(
                listOf(
                    Actor(ChannelsLCStressTestImpl::send.javaMethod!!, listOf(1), emptyList()),
                    Actor(ChannelsLCStressTestImpl::send.javaMethod!!, listOf(2), emptyList())
                ),
                listOf(
                    Actor(ChannelsLCStressTestImpl::receive.javaMethod!!, emptyList(), emptyList()),
                    Actor(ChannelsLCStressTestImpl::receive.javaMethod!!, emptyList(), emptyList())
                ),
                listOf(
                    Actor(ChannelsLCStressTestImpl::close.javaMethod!!, listOf(1), emptyList()),
                    Actor(ChannelsLCStressTestImpl::close.javaMethod!!, listOf(2), emptyList())
                )
            ),
            emptyList()
        )
    }
}
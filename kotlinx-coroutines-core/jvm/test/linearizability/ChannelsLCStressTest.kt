/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:Suppress("unused")

package kotlinx.coroutines.linearizability

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.reflect.jvm.javaMethod

class ChannelsLCStressTestImpl: VerifierState() {
    companion object {
        var capacity = Integer.MIN_VALUE
    }

    private val c = Channel<Int>(capacity)

    @Operation
    suspend fun send(@Param(gen = IntGen::class) value: Int) = try {
        c.send(value)
    } catch (e : NumberedCancellationException) {
        e.testResult
    }

    @Operation
    fun offer(@Param(gen = IntGen::class) value: Int) = try {
        c.offer(value)
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
    fun close(@Param(gen = IntGen::class) token: Int) = c.close(NumberedCancellationException(token))

//    TODO: this operation should be (and can be!) linearizable, but is not
//    @Operation
    fun cancel(@Param(gen = IntGen::class) token: Int) = c.cancel(NumberedCancellationException(token))

    @Operation
    fun isClosedForReceive() = c.isClosedForReceive

    @Operation
    fun isClosedForSend() = c.isClosedForSend

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

private class NumberedCancellationException(number: Int): CancellationException() {
    val testResult = "Closed($number)"
}

@RunWith(Parameterized::class)
class ChannelsLCStressTest(val capacity: Int): TestBase() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "capacity={0}")
        fun parameters() = listOf(Channel.RENDEZVOUS, 1, 2, 4, Channel.UNLIMITED, Channel.CONFLATED).map { arrayOf(it) }
    }

    init {
        ChannelsLCStressTestImpl.capacity = this.capacity
    }

    @Test
    fun test() {
        LCStressOptionsDefault()
            .actorsBefore(0)
            .also { LinChecker.check(ChannelsLCStressTestImpl::class.java, it) }
    }

    @Test
    fun testClose() {
        StressOptions()
            .iterations(1)
            .invocationsPerIteration(100_000 * stressTestMultiplier)
            .executionGenerator(CloseTestScenarioGenerator::class.java)
            .also { LinChecker.check(ChannelsLCStressTestImpl::class.java, it) }
    }
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
                    Actor(ChannelsLCStressTestImpl::close.javaMethod!!, listOf(1), emptyList())
                )
            ),
            emptyList()
        )
    }
}
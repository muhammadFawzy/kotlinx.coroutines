/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:Suppress("unused")

package kotlinx.coroutines.linearizability

import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.jetbrains.kotlinx.lincheck.verifier.quiescent.QuiescentConsistencyVerifier
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.*

@Param(name = "value", gen = IntGen::class)
internal open class LockFreeTaskQueueWithoutRemoveLCStressTest : VerifierState() {
    protected val q = LockFreeTaskQueue<Int>(singleConsumer = singleConsumer)

    @Operation
    fun close() = q.close()

    @Operation
    fun addLast(@Param(name = "value") value: Int) = q.addLast(value)

    override fun extractState() = q.map { it } to  q.isClosed()

    @Test
    fun test() {
        LCStressOptionsDefault().also {
            LinChecker.check(LockFreeTaskQueueWithoutRemoveLCStressTest::class.java, it)
        }
    }
}

internal class MCLockFreeTaskQueueWithRemoveLCStressTest : LockFreeTaskQueueWithoutRemoveLCStressTest() {
    @Operation
    fun removeFirstOrNull() = q.removeFirstOrNull()
}

@OpGroupConfig.OpGroupConfigs(OpGroupConfig(name = "consumer", nonParallel = true))
internal class SCLockFreeTaskQueueWithRemoveLCStressTest : LockFreeTaskQueueWithoutRemoveLCStressTest() {
    @Operation(group = "consumer")
    fun removeFirstOrNull() = q.removeFirstOrNull()
}

@RunWith(Parameterized::class)
class LockFreeTaskQueueLCStressTestRunner(sc: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "singleConsumer={0}")
        fun parameters() = listOf(true, false).map { arrayOf(it) }
    }

    init {
        singleConsumer = sc
    }

    @Test
    fun test() {
        val testClass = if (singleConsumer) SCLockFreeTaskQueueWithRemoveLCStressTest::class.java
                        else MCLockFreeTaskQueueWithRemoveLCStressTest::class.java
        LCStressOptionsDefault()
            .verifier(QuiescentConsistencyVerifier::class.java)
            .actorsPerThread(if (isStressTest) 3 else 2)
            .also { LinChecker.check(testClass, it) }
    }
}

private var singleConsumer = false
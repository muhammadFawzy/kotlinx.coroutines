/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:Suppress("unused")

package kotlinx.coroutines.internal

import kotlinx.coroutines.LCStressOptionsDefault
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

class SegmentQueueLCStressTest : VerifierState() {
    private val q = SegmentBasedQueue<Int>()

    @Operation
    fun add(@Param(gen = IntGen::class) x: Int) {
        q.enqueue(x)
    }

    @Operation
    fun poll(): Int? = q.dequeue()

    override fun extractState(): Any {
        val elements = ArrayList<Int>()
        while (true) {
            val x = q.dequeue() ?: break
            elements.add(x)
        }

        return elements
    }

    @Test
    fun test() {
        LCStressOptionsDefault().also { LinChecker.check(this.javaClass, it) }
    }
}
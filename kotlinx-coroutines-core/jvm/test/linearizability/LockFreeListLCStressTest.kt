/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */
@file:Suppress("unused")

package kotlinx.coroutines.linearizability

import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import kotlin.test.*

@Param(name = "value", gen = IntGen::class)
class LockFreeListLCStressTest : VerifierState() {
    class Node(val value: Int): LockFreeLinkedListNode()

    private val q: LockFreeLinkedListHead = LockFreeLinkedListHead()

    @Operation
    fun addLast(@Param(name = "value") value: Int) {
        q.addLast(Node(value))
    }

    @Operation
    fun addLastIfNotSame(@Param(name = "value") value: Int) {
        q.addLastIfPrev(Node(value)) { !it.isSame(value) }
    }

    @Operation
    fun removeFirst(): Int? {
        val node = q.removeFirstOrNull() ?: return null
        return (node as Node).value
    }

    @Operation
    fun removeFirstOrPeekIfNotSame(@Param(name = "value") value: Int): Int? {
        val node = q.removeFirstIfIsInstanceOfOrPeekIf<Node> { !it.isSame(value) } ?: return null
        return node.value
    }

    private fun Any.isSame(value: Int) = this is Node && this.value == value

    @Test
    fun testAddRemoveLinearizability() {
        LCStressOptionsDefault().also { LinChecker.check(this::class.java, it) }
    }

    override fun extractState(): Any {
        val elements = ArrayList<Int>()
        q.forEach<Node> { elements.add(it.value) }
        return elements
    }
}
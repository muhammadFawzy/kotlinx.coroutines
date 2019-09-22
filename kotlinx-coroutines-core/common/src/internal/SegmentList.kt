/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.internal

import kotlinx.atomicfu.*
import kotlinx.coroutines.*

// returns the first segment `s` with `s.id >= id` or `CLOSED` if all the segments in this linked list have lower `id`
//// and the list is closed for further segment additions.
private inline fun <S: Segment<S>> S.findSegmentInternal(id: Long, createNewSegment: (id: Long, prev: S?) -> S): SegmentOrClosed<S> {
    // Go through `next` references and add new segments if needed,
    // similarly to the `push` in the Michael-Scott queue algorithm.
    // The only difference is that `CAS failure` means that the
    // required segment has already been added, so the algorithm just
    // uses it. This way, only one segment with each id can be in the queue.
    var cur: S = this
    while (cur.id < id || cur.removed) {
        val nextOrClosed = cur.nextOrClosed
        if (nextOrClosed.isClosed) return SegmentOrClosed(CLOSED)
        val next: S = if (nextOrClosed.segment === null) {
            val newTail = createNewSegment(cur.id + 1, cur)
            if (cur.trySetNext(newTail)) {
                if (cur.removed) cur.remove()
                newTail
            } else {
                val nextOrClosed = cur.nextOrClosed
                if (nextOrClosed.isClosed) return SegmentOrClosed(CLOSED)
                nextOrClosed.segment!!
            }
        } else nextOrClosed.segment!!
        cur = next
    }
    return SegmentOrClosed(cur)
}

// Returns `false` if the segment `to` is logically removed, `true` on successful update.
private inline fun <S : Segment<S>> AtomicRef<S>.moveForward(to: S): Boolean = loop { cur ->
    if (cur.id >= to.id) return true
    if (!to.tryIncPointers()) return false
    if (compareAndSet(cur, to)) {
        // the segment is moved
        if (cur.decPointers()) cur.remove()
        return true
    } else {
        if (to.decPointers()) to.remove()
    }
}

/**
 * Tries to find a segment with the specified [id] following by next references from the
 * [startFrom] segment and creating new ones if needed. The typical use-case is reading this `AtomicRef` values,
 * doing some synchronization, and invoking this function to find the required segment and update the pointer.
 * At the same time, [Segment.cleanPrev] should also be invoked if the previous segments are no longer needed
 * (e.g., queues should use it in dequeue operations).
 *
 * Since segments can be removed from the list, or it can be closed for further segment additions, this function
 * returns the segment `s` with `s.id >= id` or `CLOSED` if all the segments in this linked list have lower `id`
 * and the list is closed.
 */
internal inline fun <S: Segment<S>> AtomicRef<S>.findSegmentAndMoveForward(id: Long, startFrom: S, createNewSegment: (id: Long, prev: S?) -> S): SegmentOrClosed<S> {
    while (true) {
        val s = startFrom.findSegmentInternal(id, createNewSegment)
        if (s.isClosed || moveForward(s.segment)) return s
    }
}

/**
 * Closes this linked list of segments by forbidding adding new segments,
 * returns the last segment in the list.
 */
internal fun <S : Segment<S>> S.close(): S {
    var cur: S = this
    while (true) {
        val next = cur.nextOrClosed.run { if (isClosed) return cur else segment }
        if (next === null) {
            if (cur.markAsClosed()) return cur
        } else {
            cur = next
        }
    }
}

/**
 * Each segment in [SegmentList] has a unique id and is created by [SegmentList.newSegment].
 * Essentially, this is a node in the Michael-Scott queue algorithm, but with
 * maintaining [prev] pointer for efficient [remove] implementation.
 */
internal abstract class Segment<S: Segment<S>>(val id: Long, prev: S?, pointers: Int) {
    // Pointer to the next segment, updates similarly to the Michael-Scott queue algorithm.
    private val _next = atomic<Any?>(null)
    val nextOrClosed: NextSegmentOrClosed<S> get() = NextSegmentOrClosed(_next.value)
    fun trySetNext(value: S): Boolean = _next.compareAndSet(null, value)

    // Pointer to the previous segment, updates in [remove] function.
    val prev = atomic(prev)
    /**
     * Cleans the pointer to the previous segment.
     */
    fun cleanPrev() = prev.lazySet(null)

    /**
     * This property should return the maximal number of slots in this segment,
     * it is used to define whether the segment is logically removed.
     */
    abstract val maxSlots: Int

    // numbers of cleaned slots (lowest bits) and AtomicRef pointers to this segment (highest bits)
    private val cleanedAndPointers = atomic(pointers shl POINTERS_SHIFT)

    /**
     * Returns `true` if this segment is logically removed from the queue.
     * The segment is considered as removed if all the slots are cleaned,
     * there is no pointers to this segment from outside, and
     * it is not a physical tail in the linked list of segments.
     */
    inline val removed get() = cleanedAndPointers.value == maxSlots && _next.value !== null

    // increments the number of pointers if this segment is not logically removed\
    inline fun tryIncPointers() = cleanedAndPointers.addConditionally(1 shl POINTERS_SHIFT) { it != maxSlots }
    // returns `true` if this segment is logically removed after the decrement
    inline fun decPointers() = cleanedAndPointers.addAndGet(-(1 shl POINTERS_SHIFT)) == maxSlots

    /**
     * This functions should be invoked on each slot clean-up;
     * should not be invoked twice for the same slot.
     */
    fun onSlotCleaned() {
        if (cleanedAndPointers.incrementAndGet() == maxSlots) remove()
    }

    /**
     * Tries to mark the linked list as closed by forbidding adding new segments after this one.
     */
    fun markAsClosed() = _next.compareAndSet(null, CLOSED)

    /**
     * Checks whether this segment is a physical tail and is closed for further segment additions.
     */
    val isClosed = _next.value === CLOSED

    /**
     * Removes this segment physically from the segment queue. The segment should be
     * logically removed (so [removed] returns `true`) at the point of invocation.
     */
    fun remove() {
        assert { removed } // The segment should be logically removed at first
        // Read `next` and `prev` pointers.
        var next = this.nextOrClosed.segment ?: return // tail cannot be removed
        var prev = prev.value ?: return // head cannot be removed
        // Link `next` and `prev`.
        prev.moveNextToRight(next)
        while (prev.removed) {
            prev = prev.prev.value ?: break
            prev.moveNextToRight(next)
        }
        next.movePrevToLeft(prev)
        while (next.removed) {
            next = next.nextOrClosed.segment ?: break
            next.movePrevToLeft(prev)
        }
    }

    /**
     * Updates [next] pointer to the specified segment if
     * the [id] of the specified segment is greater.
     */
    private fun moveNextToRight(next: S) {
        while (true) {
            val curNext = this._next.value as S
            if (next.id <= curNext.id) return
            if (this._next.compareAndSet(curNext, next)) return
        }
    }

    /**
     * Updates [prev] pointer to the specified segment if
     * the [id] of the specified segment is lower.
     */
    private fun movePrevToLeft(prev: S) {
        while (true) {
            val curPrev = this.prev.value ?: return
            if (curPrev.id <= prev.id) return
            if (this.prev.compareAndSet(curPrev, prev)) return
        }
    }
}

private inline fun AtomicInt.addConditionally(delta: Int, condition: (cur: Int) -> Boolean): Boolean {
    while(true) {
        val cur = this.value
        if (!condition(cur)) return false
        if (this.compareAndSet(cur, cur + delta)) return true
    }
}

internal inline class SegmentOrClosed<S: Segment<S>>(private val value: Any?) {
    val isClosed: Boolean get() = value === CLOSED
    val segment: S get() = if (value === CLOSED) error("Does not contain segment") else value as S
}

internal inline class NextSegmentOrClosed<S: Segment<S>>(private val value: Any?) {
    val isClosed: Boolean get() = value === CLOSED
    val segment: S? get() = if (isClosed) null else value as S?
}

private const val POINTERS_SHIFT = 16

@SharedImmutable
private val CLOSED = Symbol("CLOSED")
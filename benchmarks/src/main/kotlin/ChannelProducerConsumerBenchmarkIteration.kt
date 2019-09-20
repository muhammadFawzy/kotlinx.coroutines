/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Phaser

abstract class ChannelProducerConsumerBenchmarkIteration(private val withSelect: Boolean,
                                                         private val dispatcher: CoroutineDispatcher,
                                                         private val channelCreator: ChannelCreator,
                                                         private val producers: Int,
                                                         private val consumers: Int,
                                                         private val approximateBatchSize: Int) {
    private var channel: Channel<Int> = channelCreator.create()

    fun run() {
        val totalMessagesCount = approximateBatchSize / (producers * consumers) * (producers * consumers)
        val phaser = Phaser(producers + consumers + 1)
        // Run producers
        repeat(producers) { coroutineNumber ->
            GlobalScope.launch(dispatcher) {
                val dummy = if (withSelect) channelCreator.create() else null
                repeat(totalMessagesCount / producers) {
                    produce(it, dummy, coroutineNumber)
                }
                phaser.arrive()
            }
        }
        // Run consumers
        repeat(consumers) { coroutineNumber ->
            GlobalScope.launch(dispatcher) {
                val dummy = if (withSelect) channelCreator.create() else null
                repeat(totalMessagesCount / consumers) {
                    consume(dummy, coroutineNumber)
                }
                phaser.arrive()
            }
        }
        // Wait until work is done
        phaser.arriveAndAwaitAdvance()
    }

    private suspend fun produce(element: Int, dummy: Channel<Int>?, coroutineNumber: Int) {
        if (withSelect) {
            select<Unit> {
                channel.onSend(element) {}
                dummy!!.onReceive {}
            }
        } else {
            channel.send(element)
        }
        doProducerWork(coroutineNumber)
    }

    private suspend fun consume(dummy: Channel<Int>?, coroutineNumber: Int) {
        if (withSelect) {
            select<Unit> {
                channel.onReceive {}
                dummy!!.onReceive {}
            }
        } else {
            channel.receive()
        }
        doConsumerWork(coroutineNumber)
    }

    abstract fun doProducerWork(coroutineNumber: Int)

    abstract fun doConsumerWork(coroutineNumber: Int)
}

enum class DispatcherCreator(val create: (parallelism: Int) -> CoroutineDispatcher) {
    FORK_JOIN({ parallelism ->  ForkJoinPool(parallelism).asCoroutineDispatcher() }),
    EXPERIMENTAL({ parallelism -> kotlinx.coroutines.scheduling.ExperimentalCoroutineDispatcher(corePoolSize = parallelism, maxPoolSize = parallelism) })
}

enum class ChannelCreator(private val capacity: Int) {
    RENDEZVOUS(Channel.RENDEZVOUS),
    BUFFERED_1(1),
    BUFFERED_2(2),
    BUFFERED_4(4),
    BUFFERED_32(32),
    BUFFERED_128(128),
    BUFFERED_UNLIMITED(Channel.UNLIMITED);

    fun create(): Channel<Int> = Channel(capacity)
}
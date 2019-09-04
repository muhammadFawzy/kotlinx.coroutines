/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("ChannelProducerConsumerMonteCarloBenchmark")

package channel

import ChannelCreator
import ChannelProducerConsumerBenchmarkWorker
import DispatcherCreator
import doWork
import kotlinx.coroutines.CoroutineDispatcher
import org.nield.kotlinstatistics.standardDeviation
import java.io.Closeable
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/**
 * Total amount of threads in a benchmark
 */
val THREADS = arrayOf(1, 2, 4, 8, 16, 18, 32, 36, 64, 72, 96, 108, 128, 144)
/**
 * Coroutines channels creators
 */
val CHANNEL_CREATORS = listOf(ChannelCreator.BUFFERED_2, ChannelCreator.BUFFERED_32, ChannelCreator.BUFFERED_128, ChannelCreator.BUFFERED_UNLIMITED, ChannelCreator.RENDEZVOUS)
/**
 * Warm up iterations count
 */
const val WARM_UP_ITERATIONS = 5
/**
 * Maximum number of calling [run] function
 */
const val MAX_ITERATIONS = 5_000
/**
 * The baseline for loop iterations each working thread does on average on each interaction with a channel
 */
const val BASELINE_WORK = 50
/**
 * The max multiplier for the [BASELINE_WORK]
 */
const val MAX_WORK_MULTIPLIER = 5.0
/**
 * Approximate total number of sent/received messages
 */
const val APPROXIMATE_BATCH_SIZE = 750_000
/**
 * The threshold when a current benchmark configuration stops running
 */
const val BENCHMARK_RUN_STOP_PERCENTAGE_THRESHOLD = 0.01
/**
 * Number of iteration between checking if a current benchmark configuration should be stopped
 */
const val ITERATIONS_BETWEEN_THRESHOLD_CHECK = 50
/**
 * Different types of creating workload in the benchmark: using balanced or unbalanced work load
 */
val BENCHMARK_LOAD_MODE = listOf(BenchmarkLoadMode.WITH_BALANCING, BenchmarkLoadMode.WITHOUT_BALANCING)
/**
 * Should select be used in the benchmark or not
 */
val BENCHMARK_SELECT_MODE = listOf(BenchmarkSelectMode.WITH_SELECT, BenchmarkSelectMode.WITHOUT_SELECT)
/**
 * Coroutines dispatcher types
 */
val DISPATCHER_TYPES = listOf(DispatcherCreator.FORK_JOIN, DispatcherCreator.EXPERIMENTAL)
/**
 * Benchmark output file
 */
const val OUTPUT = "out/resultsProdCons.csv"

/**
 * This benchmark tests the performance of different types of channel as working queues.
 *
 * First it chooses all parameters that we want to test in this benchmark, a benchmark configuration.
 * Each iteration creates N producers and M consumers, M + N = current count of threads. For each producer and consumer
 * workload is randomly chosen (workload may be balanced or unbalanced according to the benchmark configuration). Then
 * the benchmark starts all producers and consumers coroutines. Producers send to a channel messages and then do
 * some work on CPU, consumers receive messages from the channel and do some work on CPU. Total amount of sent messages
 * is ~ [APPROXIMATE_BATCH_SIZE]. The main thread waits for all coroutines to stop, then measures the execution time of
 * the current iteration.
 *
 * The benchmark stops execution the current benchmark configuration if execution times became stable and stopped changing
 * drastically.
 */
fun main() {
    Files.createDirectories(Paths.get(OUTPUT).parent)
    val out = PrintWriter(OUTPUT)
    out.println("channel,threads,loadMode,dispatcherType,selectMode,result,std,iterations")

    val benchmarksConfigurationsNumber = THREADS.size * CHANNEL_CREATORS.size
    var currentConfigurationNumber = 0

    val startTime = System.currentTimeMillis()

    for (channel in CHANNEL_CREATORS) {
        for (threads in THREADS) {
            for (loadMode in BENCHMARK_LOAD_MODE) {
                for (dispatcherType in DISPATCHER_TYPES) {
                    for (selectMode in BENCHMARK_SELECT_MODE) {
                        print("\rchannel=$channel threads=$threads loadMode=$loadMode dispatcherType=$dispatcherType selectMode=$selectMode: warm-up phase... [${eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime)}]")

                        repeat(WARM_UP_ITERATIONS) {
                            run(threads, channel, loadMode, selectMode, dispatcherType)
                        }

                        val runExecutionTimesMs = ArrayList<Long>()
                        var lastMean = -10000.0
                        var runIteration = 0
                        while (true) {
                            repeat(ITERATIONS_BETWEEN_THRESHOLD_CHECK) {
                                runIteration++
                                runExecutionTimesMs += run(threads, channel, loadMode, selectMode, dispatcherType)
                                val result = runExecutionTimesMs.average().toInt()
                                val std = runExecutionTimesMs.standardDeviation().toInt()
                                val eta = eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime)
                                print("\rchannel=$channel threads=$threads loadMode=$loadMode dispatcherType=$dispatcherType selectMode=$selectMode iteration=$runIteration result=$result std=$std [$eta]")
                            }
                            val curMean = runExecutionTimesMs.average()
                            if (runIteration >= MAX_ITERATIONS || abs(curMean - lastMean) / curMean < BENCHMARK_RUN_STOP_PERCENTAGE_THRESHOLD) break
                            lastMean = curMean
                        }

                        val result = runExecutionTimesMs.average().toInt()
                        val std = runExecutionTimesMs.standardDeviation().toInt()
                        println("\rchannel=$channel threads=$threads loadMode=$loadMode dispatcherType=$dispatcherType selectMode=$selectMode result=$result std=$std iterations=$runIteration")
                        out.println("$channel,$threads,$loadMode,$dispatcherType,$selectMode,$result,$std,$runIteration")
                        out.flush()
                        currentConfigurationNumber++
                    }
                }
            }
        }
    }

    out.close()
}

/**
 * Estimated time of arrival
 */
fun eta(curIteration: Int, totalIterations: Int, startTime: Long): String {
    if (curIteration == 0) return "ETA - NaN"
    val time = System.currentTimeMillis() - startTime
    var eta = (time.toDouble() / curIteration * totalIterations / 60_000).toInt() // in minutes
    val minutes = eta % 60
    eta /= 60
    val hours = eta % 24
    eta /= 24
    val days = eta
    return "ETA - $days days, $hours hours,  $minutes minutes"
}

fun run(threads: Int, channelCreator: ChannelCreator, loadMode: BenchmarkLoadMode, selectMode: BenchmarkSelectMode,
        dispatcherCreator: DispatcherCreator): Long {
    // create workload
    val producers = Random.nextInt(1, max(threads, 2))
    val consumers = max(1, threads - producers)
    val producerConsumerWorkLoad = createWorkLoad(producers, consumers, loadMode)
    val producersDispatcher = dispatcherCreator.create(producers)
    val consumersDispatcher = dispatcherCreator.create(consumers)

    // start all threads
    val channelProducerConsumerBenchmarkWorker = ChannelProducerConsumerBenchmarkWorkerMonteCarlo(
            selectMode == BenchmarkSelectMode.WITH_SELECT, producersDispatcher, producerConsumerWorkLoad.producerWorks,
            consumersDispatcher, producerConsumerWorkLoad.consumerWorks, channelCreator)

    val startTime = System.nanoTime()
    channelProducerConsumerBenchmarkWorker.run(producers, consumers, APPROXIMATE_BATCH_SIZE)
    val endTime = System.nanoTime()

    if (producersDispatcher is Closeable) {
        producersDispatcher.close()
    }
    if (consumersDispatcher is Closeable) {
        consumersDispatcher.close()
    }

    return (endTime - startTime) / 1_000_000 // ms
}

private fun createWorkLoad(producers: Int, consumers: Int, mode: BenchmarkLoadMode): ProducerConsumerWorkLoad {
    return when (mode) {
        BenchmarkLoadMode.WITH_BALANCING -> {
            createBalancedWorkLoad(producers, consumers)
        }
        BenchmarkLoadMode.WITHOUT_BALANCING -> {
            createUnbalancedWorkLoad(producers, consumers)
        }
    }
}

class ProducerConsumerWorkLoad(val producerWorks: List<Int>, val consumerWorks: List<Int>)

private fun createUnbalancedWorkLoad(producers: Int, consumers: Int): ProducerConsumerWorkLoad {
    val producerWorks = ArrayList<Int>()
    val consumerWorks = ArrayList<Int>()

    repeat(producers) {
        producerWorks.add(Random.nextInt(BASELINE_WORK, (BASELINE_WORK * MAX_WORK_MULTIPLIER).toInt() + 1))
    }
    repeat(consumers) {
        consumerWorks.add(Random.nextInt(BASELINE_WORK, (BASELINE_WORK * MAX_WORK_MULTIPLIER).toInt() + 1))
    }

    return ProducerConsumerWorkLoad(producerWorks, consumerWorks)
}

private fun createBalancedWorkLoad(producers: Int, consumers: Int): ProducerConsumerWorkLoad {
    val producerWorkMultipliers = createWorkMultipliers(producers)
    val consumerWorkMultipliers = createWorkMultipliers(consumers)

    val consumerToProducerBaselineRelation = 1.0 * (consumers * consumers) / (producers * producers) *
            producerWorkMultipliers.sum() / consumerWorkMultipliers.sum()
    val producerWorkBaseline: Int
    val consumerWorkBaseline: Int
    if (consumerToProducerBaselineRelation > 1) {
        producerWorkBaseline = BASELINE_WORK
        consumerWorkBaseline = (consumerToProducerBaselineRelation * BASELINE_WORK).toInt()
    } else {
        consumerWorkBaseline = BASELINE_WORK
        producerWorkBaseline = (BASELINE_WORK / consumerToProducerBaselineRelation).toInt()
    }

    val producerWorks = producerWorkMultipliers.map { (it * producerWorkBaseline).toInt() }
    val consumerWorks = consumerWorkMultipliers.map { (it * consumerWorkBaseline).toInt() }

    return ProducerConsumerWorkLoad(producerWorks, consumerWorks)
}

/**
 * Returns multipliers for the work baseline.
 */
private fun createWorkMultipliers(workers: Int): ArrayList<Double> {
    val workMultipliers = ArrayList<Double>()
    repeat(workers) {
        workMultipliers += Random.nextDouble(1.0, MAX_WORK_MULTIPLIER)
    }
    return workMultipliers
}

enum class BenchmarkLoadMode {
    WITH_BALANCING,
    WITHOUT_BALANCING
}

enum class BenchmarkSelectMode {
    WITH_SELECT,
    WITHOUT_SELECT
}

class ChannelProducerConsumerBenchmarkWorkerMonteCarlo(withSelect: Boolean,
                                                       producerDispatcher: CoroutineDispatcher,
                                                       private val producerWorks: List<Int>,
                                                       consumerDispatcher: CoroutineDispatcher,
                                                       private val consumerWorks: List<Int>,
                                                       channelCreator: ChannelCreator)
    : ChannelProducerConsumerBenchmarkWorker(withSelect, producerDispatcher, consumerDispatcher, channelCreator) {
    override fun doProducerWork(coroutineNumber: Int) = doWork(producerWorks[coroutineNumber])

    override fun doConsumerWork(coroutineNumber: Int) = doWork(consumerWorks[coroutineNumber])
}
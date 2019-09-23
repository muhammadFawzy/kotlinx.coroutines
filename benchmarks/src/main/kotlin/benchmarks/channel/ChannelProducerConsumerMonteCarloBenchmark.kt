/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

@file:JvmName("ChannelProducerConsumerMonteCarloBenchmark")

package benchmarks.channel

import ChannelCreator
import ChannelProducerConsumerBenchmarkIteration
import DispatcherCreator
import doGeomDistrWork
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
private val THREADS = arrayOf(1, 2, 4, 8, 16, 18, 32, 36, 64, 72, 96, 108, 128, 144)
/**
 * Warm up iterations count
 */
private const val WARM_UP_ITERATIONS = 5
/**
 * Maximum number of calling [runIteration] function
 */
private const val MAX_ITERATIONS = 5_000
/**
 * The baseline for loop iterations each working thread does on average on each interaction with a channel
 */
private const val BASELINE_WORK = 50
/**
 * The max multiplier for the [BASELINE_WORK]
 */
private const val MAX_WORK_MULTIPLIER = 5.0
/**
 * Approximate total number of sent/received messages
 */
private const val APPROXIMATE_BATCH_SIZE = 750_000
/**
 * The threshold when a current benchmark configuration stops running
 */
private const val MONTECARLO_STOP_THRESHOLD = 0.01
/**
 * Number of iteration between checking if a current benchmark configuration should be stopped
 */
private const val ITERATIONS_BETWEEN_THRESHOLD_CHECK = 50
/**
 * Different types of creating workload in the benchmark: using balanced or unbalanced work load
 */
private val WITH_BALANCING = listOf(true, false)
/**
 * Should select be used in the benchmark or not
 */
private val WITH_SELECT = listOf(true, false)
/**
 * Benchmark output file
 */
private const val OUTPUT = "out/resultsProdCons.csv"

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
    PrintWriter(OUTPUT).use { pw ->
        pw.println("channel,threads,withBalancing,dispatcherType,withSelect,result,std,iterations")

        val benchmarksConfigurationsNumber = THREADS.size * ChannelCreator.values().size
        var currentConfigurationNumber = 0
        val startTime = System.currentTimeMillis()

        for (channel in ChannelCreator.values()) {
            for (threads in THREADS) {
                for (withBalancing in WITH_BALANCING) {
                    for (dispatcherType in DispatcherCreator.values()) {
                        for (withSelect in WITH_SELECT) {
                            print("\rchannel=$channel threads=$threads withBalancing=$withBalancing dispatcherType=$dispatcherType withSelect=$withSelect: warm-up phase... [${eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime)}]")

                            repeat(WARM_UP_ITERATIONS) {
                                runIteration(threads, channel, withBalancing, withSelect, dispatcherType)
                            }

                            runMonteCarlo(threads, channel, withBalancing, withSelect, dispatcherType, pw)
                            { eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime) }
                            currentConfigurationNumber++
                        }
                    }
                }
            }
        }
    }
}

private fun runMonteCarlo(threads: Int,
                          channel: ChannelCreator,
                          withBalancing: Boolean,
                          withSelect: Boolean,
                          dispatcherType: DispatcherCreator,
                          pw: PrintWriter,
                          generateEta : () -> String) {
    val runExecutionTimesMs = ArrayList<Long>()
    var lastMean = -10000.0
    var runIteration = 0
    while (true) {
        repeat(ITERATIONS_BETWEEN_THRESHOLD_CHECK) {
            runIteration++
            runExecutionTimesMs += runIteration(threads, channel, withBalancing, withSelect, dispatcherType)
            val result = runExecutionTimesMs.average().toInt()
            val std = runExecutionTimesMs.standardDeviation().toInt()
            val eta = generateEta.invoke()
            print("\rchannel=$channel threads=$threads withBalancing=$withBalancing dispatcherType=$dispatcherType withSelect=$withSelect iteration=$runIteration result=$result std=$std [$eta]")
        }
        val curMean = runExecutionTimesMs.average()
        if (runIteration >= MAX_ITERATIONS || abs(curMean - lastMean) / curMean < MONTECARLO_STOP_THRESHOLD) break
        lastMean = curMean
    }

    val result = runExecutionTimesMs.average().toInt()
    val std = runExecutionTimesMs.standardDeviation().toInt()
    println("\rchannel=$channel threads=$threads withBalancing=$withBalancing dispatcherType=$dispatcherType withSelect=$withSelect result=$result std=$std iterations=$runIteration")
    pw.println("$channel,$threads,$withBalancing,$dispatcherType,$withSelect,$result,$std,$runIteration")
    pw.flush()
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

fun runIteration(threads: Int, channelCreator: ChannelCreator, withBalancing: Boolean, withSelect: Boolean,
                 dispatcherCreator: DispatcherCreator): Long {
    val producers = Random.nextInt(1, max(threads, 2))
    val consumers = max(1, threads - producers)
    val dispatcher = dispatcherCreator.create(producers + consumers)

    val channelProducerConsumerBenchmarkWorker = ChannelProducerConsumerBenchmarkIterationMonteCarlo(
            withBalancing, withSelect, dispatcher, channelCreator, producers, consumers, APPROXIMATE_BATCH_SIZE)

    val startTime = System.nanoTime()
    channelProducerConsumerBenchmarkWorker.run()
    val endTime = System.nanoTime()

    if (dispatcher is Closeable) {
        dispatcher.close()
    }

    return (endTime - startTime) / 1_000_000 // ms
}

/**
 * Returns multipliers for the work baseline.
 */
private fun generateWorkMultipliers(workers: Int): DoubleArray = DoubleArray(workers) { Random.nextDouble(1.0, MAX_WORK_MULTIPLIER) }

class ChannelProducerConsumerBenchmarkIterationMonteCarlo(withBalancing: Boolean,
                                                          withSelect: Boolean,
                                                          producerDispatcher: CoroutineDispatcher,
                                                          channelCreator: ChannelCreator,
                                                          producers: Int,
                                                          consumers: Int,
                                                          approximateBatchSize : Int)
    : ChannelProducerConsumerBenchmarkIteration(withSelect, producerDispatcher, channelCreator, producers, consumers, approximateBatchSize) {
    private val producerWorks : Array<Int>

    private val consumerWorks : Array<Int>

    init {
        if (withBalancing) {
            producerWorks = Array(producers) { Random.nextInt(BASELINE_WORK, (BASELINE_WORK * MAX_WORK_MULTIPLIER).toInt() + 1) }
            consumerWorks = Array(consumers) { Random.nextInt(BASELINE_WORK, (BASELINE_WORK * MAX_WORK_MULTIPLIER).toInt() + 1) }
        }
        else {
            val producerWorkMultipliers = generateWorkMultipliers(producers)
            val consumerWorkMultipliers = generateWorkMultipliers(consumers)

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

            producerWorks = producerWorkMultipliers.map { (it * producerWorkBaseline).toInt() }.toTypedArray()
            consumerWorks = consumerWorkMultipliers.map { (it * consumerWorkBaseline).toInt() }.toTypedArray()
        }
    }

    override fun doProducerWork(coroutineNumber: Int) = doGeomDistrWork(producerWorks[coroutineNumber])

    override fun doConsumerWork(coroutineNumber: Int) = doGeomDistrWork(consumerWorks[coroutineNumber])
}
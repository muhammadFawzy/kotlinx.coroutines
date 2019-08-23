@file:JvmName("ChannelProducerConsumerMonteCarloBenchmark")

package channel

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import java.io.Closeable
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Phaser
import java.util.concurrent.ThreadLocalRandom
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
val DISPATCHER_TYPES = listOf(DispatcherTypes.FORK_JOIN, DispatcherTypes.EXPERIMENTAL)
/**
 * Benchmark output file
 */
const val OUTPUT = "out/montecarlo.out"

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

    val benchmarksConfigurationsNumber = THREADS.size * CHANNEL_CREATORS.size
    var currentConfigurationNumber = 0

    val startTime = System.currentTimeMillis()

    for (channel in CHANNEL_CREATORS) {
        for (threads in THREADS) {
            for (loadMode in BENCHMARK_LOAD_MODE) {
                for (dispatcherType in DISPATCHER_TYPES) {
                    for (selectMode in BENCHMARK_SELECT_MODE) {
                        val descriptiveStatistics = DescriptiveStatistics()
                        print("\rchannel=$channel threads=$threads loadMode=$loadMode dispatcherType=$dispatcherType selectMode=$selectMode: warm-up phase... [${eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime)}]")

                        repeat(WARM_UP_ITERATIONS) {
                            run(threads, channel, loadMode, selectMode, dispatcherType)
                        }

                        var lastMean = -10000.0
                        var runIteration = 0
                        while (true) {
                            repeat(ITERATIONS_BETWEEN_THRESHOLD_CHECK) {
                                runIteration++
                                val executionTimeMs = run(threads, channel, loadMode, selectMode, dispatcherType)
                                descriptiveStatistics.addValue(executionTimeMs.toDouble())
                                val result = descriptiveStatistics.getMean().toInt()
                                val std = descriptiveStatistics.getStandardDeviation().toInt()
                                val eta = eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime)
                                print("\rchannel=$channel threads=$threads loadMode=$loadMode dispatcherType=$dispatcherType selectMode=$selectMode iteration=$runIteration result=$result std=$std [$eta]")
                            }
                            val curMean = descriptiveStatistics.getMean()
                            if (runIteration >= MAX_ITERATIONS || abs(curMean - lastMean) / curMean < BENCHMARK_RUN_STOP_PERCENTAGE_THRESHOLD) break
                            lastMean = curMean
                        }

                        val result = descriptiveStatistics.getMean().toInt()
                        val std = descriptiveStatistics.getStandardDeviation().toInt()
                        println("\rchannel=$channel threads=$threads loadMode=$loadMode dispatcherType=$dispatcherType selectMode=$selectMode result=$result std=$std iterations=$runIteration")
                        out.println("channel=$channel threads=$threads loadMode=$loadMode dispatcherType=$dispatcherType selectMode=$selectMode result=$result std=$std iterations=$runIteration")
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
        dispatcherType: DispatcherTypes): Long {
    // create workload
    val producers = Random.nextInt(1, max(threads, 2))
    val consumers = max(1, threads - producers)
    val (producerWorks, consumerWorks) = createWorkLoad(producers, consumers, loadMode)
    val producersDispatcher = dispatcherType.create(producers)
    val consumersDispatcher = dispatcherType.create(consumers)

    // start all threads
    val phaser = Phaser(producers + consumers + 1)
    val startTime = startWork(channelCreator, selectMode, phaser, producers, producerWorks, producersDispatcher,
            consumers, consumerWorks, consumersDispatcher)

    // wait until the total work is done
    phaser.arriveAndAwaitAdvance()
    val endTime = System.nanoTime()

    if (producersDispatcher is Closeable) {
        producersDispatcher.close()
    }
    if (consumersDispatcher is Closeable) {
        consumersDispatcher.close()
    }

    return (endTime - startTime) / 1_000_000 // ms
}

private fun startWork(channelCreator: ChannelCreator, selectMode: BenchmarkSelectMode, phaser: Phaser,
                      producers: Int, producerWorks: List<Int>, producersDispatcher: CoroutineDispatcher,
                      consumers: Int, consumerWorks: List<Int>, consumersDispatcher: CoroutineDispatcher): Long {
    val channel = channelCreator.create()
    val dummy = if (selectMode == BenchmarkSelectMode.WITH_SELECT) channelCreator.create() else null
    val totalMessagesCount = APPROXIMATE_BATCH_SIZE / (producers * consumers) * (producers * consumers)
    check(totalMessagesCount / producers * producers == totalMessagesCount)
    check(totalMessagesCount / consumers * consumers == totalMessagesCount)
    val startTime = System.nanoTime()

    // run producers
    repeat(producers) { prodId ->
        val work = producerWorks[prodId]
        CoroutineScope(producersDispatcher).launch {
            try {
                repeat(totalMessagesCount / producers) {
                    produce(channel, dummy, selectMode, it, work)
                }
            } finally {
                phaser.arrive()
            }
        }
    }

    // run consumers
    repeat(consumers) { consId ->
        val work = consumerWorks[consId]
        CoroutineScope(consumersDispatcher).launch {
            try {
                repeat(totalMessagesCount / consumers) {
                    consume(channel, dummy, selectMode, work)
                }
            } finally {
                phaser.arrive()
            }
        }
    }

    return startTime
}

private suspend fun produce(workingChannel: Channel<Int>, dummy: Channel<Int>?, withSelect : BenchmarkSelectMode, element: Int, work : Int) {
    when (withSelect) {
        BenchmarkSelectMode.WITH_SELECT -> select<Unit> {
            workingChannel.onSend(element) {}
            dummy!!.onReceive {}
        }
        BenchmarkSelectMode.WITHOUT_SELECT -> workingChannel.send(element)
    }
    doWork(work)
}

private suspend fun consume(workingChannel: Channel<Int>, dummy: Channel<Int>?, withSelect : BenchmarkSelectMode, work : Int) {
    when (withSelect) {
        BenchmarkSelectMode.WITH_SELECT -> select<Unit> {
            workingChannel.onReceive {}
            dummy!!.onReceive {}
        }
        BenchmarkSelectMode.WITHOUT_SELECT -> workingChannel.receive()
    }
    doWork(work)
}


fun createWorkLoad(producers: Int, consumers: Int, mode: BenchmarkLoadMode): Pair<List<Int>, List<Int>> {
    return when (mode) {
        BenchmarkLoadMode.WITH_BALANCING -> {
            createBalancedWorkLoad(producers, consumers)
        }
        BenchmarkLoadMode.WITHOUT_BALANCING -> {
            createUnbalancedWorkLoad(producers, consumers)
        }
    }
}

private fun createUnbalancedWorkLoad(producers: Int, consumers: Int): Pair<ArrayList<Int>, ArrayList<Int>> {
    val producerWorks = ArrayList<Int>()
    val consumerWorks = ArrayList<Int>()

    repeat(producers) {
        producerWorks.add(Random.nextInt(BASELINE_WORK, (BASELINE_WORK * MAX_WORK_MULTIPLIER).toInt() + 1))
    }
    repeat(consumers) {
        consumerWorks.add(Random.nextInt(BASELINE_WORK, (BASELINE_WORK * MAX_WORK_MULTIPLIER).toInt() + 1))
    }

    return Pair(producerWorks, consumerWorks)
}

private fun createBalancedWorkLoad(producers: Int, consumers: Int): Pair<List<Int>, List<Int>> {
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

    return Pair(producerWorks, consumerWorks)
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

/**
 * Spending some time on CPU using geometric distribution
 */
private fun doWork(loopSize: Int) {
    val probability = 1.0 / loopSize
    val random = ThreadLocalRandom.current()
    while (true) {
        if (random.nextDouble() < probability) break
    }
}

enum class BenchmarkLoadMode {
    WITH_BALANCING,
    WITHOUT_BALANCING
}

enum class BenchmarkSelectMode {
    WITH_SELECT,
    WITHOUT_SELECT
}

enum class DispatcherTypes(val create: (parallelism: Int) -> CoroutineDispatcher) {
    FORK_JOIN({ parallelism -> java.util.concurrent.ForkJoinPool(parallelism).asCoroutineDispatcher() }),
    EXPERIMENTAL({ parallelism -> kotlinx.coroutines.scheduling.ExperimentalCoroutineDispatcher(corePoolSize = parallelism, maxPoolSize = parallelism) })
}

enum class ChannelCreator(private val capacity: Int) {
    RENDEZVOUS(Channel.RENDEZVOUS),
    //    BUFFERED_1(1),
    BUFFERED_2(2),
    //    BUFFERED_4(4),
    BUFFERED_32(32),
    BUFFERED_128(128),
    BUFFERED_UNLIMITED(Channel.UNLIMITED);

    fun create(): Channel<Int> = Channel(capacity)
}
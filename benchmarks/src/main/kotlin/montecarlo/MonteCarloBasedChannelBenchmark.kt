@file:JvmName("MonteCarloBasedBenchmark")

package montecarlo

import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.Phaser
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

fun main() {
    Files.createDirectories(Paths.get(OUTPUT).parent)
    val out = PrintWriter(OUTPUT)

    val benchmarksConfigurationsNumber = THREADS.size * CHANNEL_TYPES.size
    var currentConfigurationNumber = 0
    val startTime = System.currentTimeMillis()

    for (channelType in CHANNEL_TYPES) {
        for (threads in THREADS) {
            print("\rchannelType=$channelType threads=$threads: warm-up phase... [${eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime)}]")

            repeat(WARM_UP_ITERATIONS) {
                run(threads, channelType)
            }

            val runExecutionTimesMs = ArrayList<Long>()
            var lastMean = -10000.0
            var runIteration = 0
            while (true) {
                repeat(ITERATIONS_BETWEEN_THRESHOLD_CHECK) {
                    runIteration++
                    runExecutionTimesMs += run(threads, channelType)
                    print("\rchannelType=$channelType threads=$threads iteration=$runIteration result=${runExecutionTimesMs.average().toInt()} std=${computeStandardDeviation(runExecutionTimesMs).toInt()} [${eta(currentConfigurationNumber, benchmarksConfigurationsNumber, startTime)}]")
                }
                val curMean = runExecutionTimesMs.average()
                if (runIteration >= MAX_ITERATIONS || abs(curMean - lastMean) / curMean < BENCHMARK_CONFIGURATION_STOP_THRESHOLD) break
                lastMean = curMean
            }

            println("\rchannelType=$channelType threads=$threads result=${runExecutionTimesMs.average().toInt()} std=${computeStandardDeviation(runExecutionTimesMs).toInt()} iterations=$runIteration")
            out.println("channelType=$channelType threads=$threads result=${runExecutionTimesMs.average().toInt()} std=${computeStandardDeviation(runExecutionTimesMs).toInt()} iterations=$runIteration")
            out.flush()
            currentConfigurationNumber++
        }
    }

    out.close()
}

fun computeStandardDeviation(list : List<Long>) : Double {
    var sum = 0.0
    var standardDeviation = 0.0
    for (num in list) {
        sum += num
    }
    val mean = sum / list.size
    for (num in list) {
        standardDeviation += (num - mean).pow(2.0)
    }
    return sqrt(standardDeviation / list.size)
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

fun run(threads: Int, channelType: ChannelType): Long {
    // create workload
    val producers = Random.nextInt(1, max(threads, 2))
    val consumers = max(1, threads - producers)
    val (producerWorks, consumerWorks) = createWorkLoad(producers, consumers)

    // start all threads
    val phaser = Phaser(producers + consumers + 1)
    val startTime = startWork(channelType, phaser, producers, producerWorks, consumers, consumerWorks)

    // wait until the total work is done
    phaser.arriveAndAwaitAdvance()
    val endTime = System.nanoTime()
    return (endTime - startTime) / 1_000_000 // ms
}

private fun startWork(channelType: ChannelType, phaser: Phaser,
                      producers: Int, producerWorks: List<Int>,
                      consumers: Int, consumerWorks: List<Int>) : Long {
    val channel = channelType.createChannel<Int>()
    val totalMessagesCount = APPROX_BATCH_SIZE / (producers * consumers) * (producers * consumers)
    check(totalMessagesCount / producers * producers == totalMessagesCount)
    check(totalMessagesCount / consumers * consumers == totalMessagesCount)
    val startTime = System.nanoTime()

    // run producers
    repeat(producers) { prodId ->
        val work = producerWorks[prodId]
        Thread {
            repeat(totalMessagesCount / producers) {
                runBlocking {
                    channel.send(it)
                }
                doWork(work)
            }
            phaser.arrive()
        }.start()
    }

    // run consumers
    repeat(consumers) { consId ->
        val work = consumerWorks[consId]
        Thread {
            repeat(totalMessagesCount / consumers) {
                runBlocking {
                    channel.receive()
                }
                doWork(work)
            }
            phaser.arrive()
        }.start()
    }

    return startTime
}

fun createWorkLoad(producers: Int, consumers: Int): Pair<List<Int>, List<Int>> {
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
 * There are [WORK_TYPES] different numbers, list size is [workers].
 * For example workers = 6, WORK_TYPES = 3. The arrayList could look like this: [1.97, 1.97, 2.13, 2.13, 2.13, 4.6]
 */
private fun createWorkMultipliers(workers: Int): ArrayList<Double> {
    val workMultipliers = ArrayList<Double>()
    randomNumbers(WORK_TYPES, workers).forEach {
        val multiplier = Random.nextDouble(1.0, MAX_WORK_MULTIPLIER)
        repeat(it) { workMultipliers += multiplier }
    }
    return workMultipliers
}

/**
 * Creates the list with [numbersCount] positive random numbers that sums to [totalSum]
 */
@Suppress("SameParameterValue")
private fun randomNumbers(numbersCount: Int, totalSum: Int): List<Int> {
    val bounds = List(numbersCount - 1) { Random.nextInt(totalSum) }.sorted()
    val randomNumbers = ArrayList<Int>()
    var lastBound = 0
    for (bound in bounds) {
        randomNumbers += bound - lastBound
        lastBound = bound
    }
    randomNumbers += totalSum - lastBound
    check(randomNumbers.sum() == totalSum)
    return randomNumbers
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
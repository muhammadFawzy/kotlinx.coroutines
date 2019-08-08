package montecarlo

import kotlinx.coroutines.channels.Channel

/**
 * Total amount of threads in a benchmark
 */
val THREADS = arrayOf(1, 2, 4, 8, 16, 18, 32, 36, 64, 72, 96, 108, 128, 144)
/**
 * Coroutines channels type.
 */
val CHANNEL_TYPES = listOf(ChannelType.BUFFERED_DEFAULT, ChannelType.RENDEZVOUS, ChannelType.UNLIMITED, ChannelType.BUFFERED_1, ChannelType.BUFFERED_16, ChannelType.BUFFERED_256)
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
 * The amount of different numbers of loop iterations threads can do on average
 */
const val WORK_TYPES = 3
/**
 * Approximate total number of sent/received messages
 */
const val APPROX_BATCH_SIZE = 750_000
/**
 *
 */
const val BENCHMARK_CONFIGURATION_STOP_THRESHOLD = 0.01
/**
 *
 */
const val ITERATIONS_BETWEEN_THRESHOLD_CHECK = 50
/**
 * Benchmark output file
 */
const val OUTPUT = "out/montecarlo.out"

enum class ChannelType {
    RENDEZVOUS {
        override fun <T> createChannel(): Channel<T> {
            return Channel(Channel.RENDEZVOUS)
        }
    },
    UNLIMITED {
        override fun <T> createChannel(): Channel<T> {
            return Channel(Channel.UNLIMITED)
        }
    },
    BUFFERED_DEFAULT {
        override fun <T> createChannel(): Channel<T> {
            return Channel(Channel.BUFFERED)
        }
    },
    BUFFERED_1 {
        override fun <T> createChannel(): Channel<T> {
            return Channel(1)
        }
    },
    BUFFERED_16 {
        override fun <T> createChannel(): Channel<T> {
            return Channel(16)
        }
    },
    BUFFERED_256 {
        override fun <T> createChannel(): Channel<T> {
            return Channel(256)
        }
    };

    abstract fun <T> createChannel() : Channel<T>
}
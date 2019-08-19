//@file:JvmName("InMemoryChatBenchmark")

package chat

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentLinkedQueue

fun main() {
    context = DispatcherTypes.EXPERIMENTAL.create(4)

    val list = ArrayList<Job>()
    val listCh = ConcurrentLinkedQueue<Channel<Int>>()
    repeat(10000) {
        val launch = CoroutineScope(context).launch {
            val ch = ChannelType.UNLIMITED.createChannel<Int>()
            listCh.add(ch)
            while (true) {
                ch.receiveOrClosed().valueOrNull ?: return@launch
            }
        }
        list.add(launch)
    }

    Thread.sleep(2000)
    for ((i, ch) in listCh.withIndex()) {
        ch.close()
    }

    runBlocking {
        for (job in list) {
            job.join()
        }
    }

    println("success")
}
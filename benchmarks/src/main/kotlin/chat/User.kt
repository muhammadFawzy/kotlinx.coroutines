package chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield
import java.util.*
import java.util.concurrent.ThreadLocalRandom

/**
 * An abstract class for server-side chat users.
 * Every user has its own channel through which users can get messages from other users [receiveAndProcessMessage],
 * users can send messages to their friends' channels as well [sendMessage].
 * User contains sent and received messages metrics.
 * To emulate real world chat servers, some work will be executed on CPU during sending and receiving messages. When user
 * connects to the server, the connection itself consumes some CPU time.
 * At the end of the benchmark execution [stopUser] should be called.
 * Because of the design of the coroutines tasks scheduler and channels, it is important to call [yield] sometimes to allow other
 * coroutines to work. This is necessary due to the fact that if a coroutine constantly has some work to do, like in this
 * case if a coroutine has an endless flow of messages, it will work without interruption, and other coroutines will have to
 * wait for this coroutine to end it's execution.
 */
abstract class User(val id: Long,
                    val activity: Double,
                    val messageChannel: Channel<Message>,
                    private val configuration: BenchmarkConfiguration) {
    var sentMessages = 0L
        protected set

    var receivedMessages = 0L
        protected set

    protected val random = Random(id)

    private var messagesToSent: Double = 0.0

    @Volatile
    private var stopped = false

    private val startTime = System.currentTimeMillis()

    lateinit var runCoroutine: Job

    fun startUser() {
        messagesToSent += activity
        var count = 0L
        runCoroutine = CoroutineScope(context).launch {
            try {
                while (!stopped) {
                    // receive messages while can
                    while (!stopped) {
                        if (id == 1L) {
                            println("polling at $id, stopped=$stopped")
                        }
                        val poll = messageChannel.poll()
                        if (poll == null) {
                            if (id == 1L) {
                                println("Is null at $id stopped=$stopped")
                            }
                            break
                        }

                        val message = poll!!
                        if (id == 1L) {
                            println("returned from poll at $id, stopped=$stopped")
                        }
                        receiveAndProcessMessage(message)
                    }
                    if (id == 1L) {
                        println("Polled at $id")
                    }
                    if (stopped) {
                        if (id == 1L) {
                            print("returning")
                        }
                        return@launch
                    }
                    if (id == 1L) {
                        println("checked return at $id")
                    }
                    // if we can send a message, send it, otherwise wait on receive and receive a message
                    if (messagesToSent >= 1) {
                        sendMessage()
                        messagesToSent--
                    } else {
                        if (stopped) {
                            if (id == 1L) {
                                println("breaking at 1")
                            }
                            break
                        }
                        if (id == 1L) {
                            println("start receive at 1")
                        }
                        val message : Message
                        try
                        {
                            message = messageChannel.receiveOrClosed().valueOrNull ?: break
                        }
                        catch (e : Exception) {
                            println("exception ${e.stackTrace}")
                            throw e
                        }
                        if (id == 1L) {
                            println("end receive at 1")
                        }
                        receiveAndProcessMessage(message)
                    }
                    if (id == 1L) {
                        println("sent or received at $id")
                    }
                    // hint described in the class' comment section
                    if (count++ % 61 == 5L) {
//                    println("yield $id, stopped=$stopped")
                        yield()
                    }
                    if (id == 1L) {
                        println("Yield at $id")
                    }
                }
                if (id == 1L) {
                    println("stopped $id")
                }
            }
            catch (e : Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun doSomeWorkOnCpu() {
        // We use geometric distribution here
        val p = 1.0 / configuration.averageWork
        val r = ThreadLocalRandom.current()
        while (true) {
            if (r.nextDouble() < p) break
        }
    }

    private suspend fun sendMessage() {
        if (id == 1L) {
            println("sending at $id, stopped=$stopped")
        }
//        println("sending at $id, stopped=$stopped")

        val userChannelToSend = chooseChannelToSend() ?: return
        val now = System.nanoTime()
        try {
            select<Unit> {
                userChannelToSend.onSend(Message(id, now)) {
                    if (!stopped) {
                        sentMessages++
                    }
                    doSomeWorkOnCpu()
                }
                messageChannel.onReceiveOrClosed { message ->
                    if (!message.isClosed) {
                        receiveAndProcessMessage(message.value)
                    }
                }
            }
        } catch (ignored: ClosedSendChannelException) {
        } catch (ignored: IllegalStateException) {
        }
    }

    private fun receiveAndProcessMessage(message: Message) {
        if (id == 1L) {
            println("processing receive at $id, stopped=$stopped")
        }
        messagesToSent += activity
        if (!stopped) {
            receivedMessages++
        }
        doSomeWorkOnCpu()
    }

    fun stopUser() {
        stopped = true
        messageChannel.close()
        if (id == 1L) {
            println("stopped user $id")
        }
    }

    abstract fun chooseChannelToSend(): Channel<Message>?
}

/**
 * A message from one of the users to another one
 */
class Message(private val userId: Long, val nanos: Long)
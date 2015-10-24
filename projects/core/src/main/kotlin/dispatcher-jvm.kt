/*
 * Copyright (c) 2015 Mark Platvoet<mplatvoet@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * THE SOFTWARE.
 */

package nl.komponents.kovenant

import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


public fun MutableDispatcherContext.jvmDispatcher(body: JvmDispatcherBuilder.() -> Unit) {
    dispatcher = buildJvmDispatcher(body)
}

public fun buildJvmDispatcher(body: JvmDispatcherBuilder.() -> Unit): Dispatcher = concreteBuildDispatcher(body)

internal fun concreteBuildDispatcher(body: JvmDispatcherBuilder.() -> Unit): Dispatcher {
    val builder = ConcreteDispatcherBuilder()
    builder.body()
    return builder.build()
}

public interface JvmDispatcherBuilder : DispatcherBuilder {
    var threadFactory: (target: Runnable, dispatcherName: String, id: Int) -> Thread
}

private class ConcreteDispatcherBuilder : JvmDispatcherBuilder {
    override var name = "kovenant-dispatcher"
    private var localNumberOfThreads = threadAdvice
    override var exceptionHandler: (Exception) -> Unit = { e -> e.printStackTrace(System.err) }
    override var errorHandler: (Throwable) -> Unit = { t -> t.printStackTrace(System.err) }

    override var workQueue: WorkQueue<() -> Unit> = NonBlockingWorkQueue()
    private val pollStrategyBuilder = ConcretePollStrategyBuilder()

    override var threadFactory: (target: Runnable, dispatcherName: String, id: Int) -> Thread
            = fun(target: Runnable, dispatcherName: String, id: Int): Thread {
        val thread = Thread(target, "$dispatcherName-$id")
        thread.isDaemon = false
        return thread
    }


    override var concurrentTasks: Int
        get() = localNumberOfThreads
        set(value) {
            if (value < 1) {
                throw ConfigurationException("concurrentTasks must be at least 1, but was $value")
            }
            localNumberOfThreads = value
        }


    override fun pollStrategy(body: PollStrategyBuilder.() -> Unit) {
        pollStrategyBuilder.clear()
        pollStrategyBuilder.body()
    }

    fun build(): Dispatcher {
        val localWorkQueue = workQueue

        return NonBlockingDispatcher(name = name,
                numberOfThreads = localNumberOfThreads,
                exceptionHandler = exceptionHandler,
                errorHandler = errorHandler,
                workQueue = localWorkQueue,
                pollStrategy = pollStrategyBuilder.build(localWorkQueue),
                threadFactory = threadFactory)
    }
}


class ConcretePollStrategyBuilder() : PollStrategyBuilder {
    private val factories = ArrayList<PollStrategyFactory<() -> Unit>>()

    fun clear() = factories.clear()

    override fun yielding(numberOfPolls: Int) {
        factories.add(YieldingPollStrategyFactory(attempts = numberOfPolls))
    }

    override fun sleeping(numberOfPolls: Int, sleepTimeInMs: Long) {
        factories.add(SleepingPollStrategyFactory(attempts = numberOfPolls, sleepTimeMs = sleepTimeInMs))
    }

    override fun busy(numberOfPolls: Int) {
        factories.add(BusyPollStrategyFactory(attempts = numberOfPolls))
    }

    override fun blocking() {
        factories.add(BlockingPollStrategyFactory())
    }

    override fun blockingSleep(numberOfPolls: Int, sleepTimeInMs: Long) {
        factories.add(BlockingSleepPollStrategyFactory(attempts = numberOfPolls, sleepTimeMs = sleepTimeInMs))
    }

    private fun buildDefaultStrategy(pollable: Pollable<() -> Unit>): PollStrategy<() -> Unit> {
        val defaultFactories = listOf(YieldingPollStrategyFactory<() -> Unit>(), SleepingPollStrategyFactory<() -> Unit>())
        return ChainPollStrategyFactory(defaultFactories).build(pollable)
    }

    public fun build(pollable: Pollable<() -> Unit>): PollStrategy<() -> Unit> = if (factories.isEmpty()) {
        buildDefaultStrategy(pollable)
    } else {
        ChainPollStrategyFactory(factories).build(pollable)
    }
}


private class NonBlockingDispatcher(val name: String,
                                    val numberOfThreads: Int,
                                    private val exceptionHandler: (Exception) -> Unit,
                                    private val errorHandler: (Throwable) -> Unit,
                                    private val workQueue: WorkQueue<() -> Unit>,
                                    private val pollStrategy: PollStrategy<() -> Unit>,
                                    private val threadFactory: (target: Runnable, dispatcherName: String, id: Int) -> Thread) : ProcessAwareDispatcher, PostponeDispatcher {

    init {
        if (numberOfThreads < 1) {
            throw IllegalArgumentException("numberOfThreads must be at least 1 but was $numberOfThreads")
        }
    }

    private val running = AtomicBoolean(true)
    private val threadId = AtomicInteger(0)
    private val contextCount = AtomicInteger(0)

    private val threadContexts = ConcurrentLinkedQueue<ThreadContext>()

    override fun runNext(): Boolean {
        val threadContext = currentThreadContext() ?: throw StateException("current thread does not belong to this context")
        return threadContext.runNext()
    }

    private fun currentThreadContext() = threadContexts.find { it.isCurrentThread() }

    override fun offer(task: () -> Unit): Boolean {
        if (running.get()) {
            workQueue.offer(task)
            val threadSize = contextCount.get()
            if (threadSize < numberOfThreads) {
                val threadNumber = contextCount.incrementAndGet()
                if (threadNumber <= numberOfThreads && workQueue.size() > 0) {
                    val newThreadContext = newThreadContext()
                    threadContexts.offer(newThreadContext)
                    if (!running.get()) {
                        //it can be the case that during initialization of the context the dispatcher has been shutdown
                        //and this newly created created is missed. So request shutdown again.
                        newThreadContext.interrupt()
                    }

                } else {
                    contextCount.decrementAndGet()
                }
            }
            return true
        }
        return false
    }

    override fun stop(force: Boolean, timeOutMs: Long, block: Boolean): List<() -> Unit> {
        if (running.compareAndSet(true, false)) {

            //Notify all thread to simply die as soon as possible
            threadContexts.forEach { it.kamikaze() }

            if (block) {
                val localTimeOutMs = if (force) 1 else timeOutMs
                val now = System.currentTimeMillis()
                val napTimeMs = Math.min(timeOutMs, 10L)

                fun allThreadsShutdown() = contextCount.get() <= 0
                fun keepWaiting() = localTimeOutMs < 1 || (System.currentTimeMillis() - now) >= localTimeOutMs

                var interrupted = false
                while (!allThreadsShutdown() && keepWaiting()) {
                    try {
                        Thread.sleep(napTimeMs)
                    } catch (e: InterruptedException) {
                        //ignoring for now since it would break the shutdown contract
                        //remember and interrupt later
                        interrupted = true
                    }
                }
                if (interrupted) {
                    //calling thread was interrupted during shutdown, set the interrupted flag again
                    Thread.currentThread().interrupt()
                }

                threadContexts.forEach { it.interrupt() }

                return depleteQueue()
            }
        }
        return ArrayList() //depleteQueue() also returns an ArrayList, returning this for consistency
    }

    override val terminated: Boolean get() = stopped && contextCount.get() < 0

    override val stopped: Boolean get() = !running.get()

    private fun depleteQueue(): List<() -> Unit> {
        val remains = ArrayList<() -> Unit>()
        do {
            val function = workQueue.poll() ?: return remains
            remains.add(function)
        } while (true)
        throw IllegalStateException("unreachable")
    }

    override fun tryCancel(task: () -> Unit): Boolean {
        if (workQueue.remove(task)) return true

        //try any of the running threadContexts
        threadContexts.forEach {
            if (it.cancel(task)) return true
        }
        return false
    }

    // implemented for completeness. not the best implementation out there, iteration is quite wasteful
    // on resources. Not used in primary processes though. Consider using a custom list implementation,
    // there is more then one already implemented
    override fun ownsCurrentProcess(): Boolean = currentThreadContext() != null

    private fun newThreadContext(): ThreadContext {
        return ThreadContext(threadId.incrementAndGet())
    }


    internal fun deRegisterRequest(context: ThreadContext, force: Boolean = false): Boolean {

        val succeeded = threadContexts.remove(context)
        if (!force && succeeded && threadContexts.isEmpty() && workQueue.isNotEmpty() && running.get()) {
            //that, hopefully rare, state where all threadContexts thought they had nothing to do
            //but the queue isn't empty. Reinstate anyone that notes this.
            threadContexts.add(context)
            return false
        }
        if (succeeded) {
            contextCount.decrementAndGet()
        }
        return true
    }

    //used for nested tasks
    private class TaskNode(val task: () -> Unit) {
        @Volatile var next: TaskNode? = null
        @Volatile var cancelled = false


        //only currentThread may update this
        //no need for volatile
        var prev: TaskNode? = null
    }

    private inline fun TaskNode?.iterate(cb: (TaskNode) -> Unit) {
        var head = this
        while (head != null) {
            cb(head)
            head = head.next
        }
    }


    private inner class ThreadContext(val id: Int) : Runnable {

        private val pending = 0
        private val polling = -1
        private val mutating = -2
        private val running = 1 // and higher indicates running tasks or nested running tasks

        private val state = AtomicInteger(pending)
        private val thread: Thread
        private @Volatile var alive = true
        private @Volatile var keepAlive: Boolean = true
        private @Volatile var pollResult: (() -> Unit)? = null

        //Used when runNext is used
        private @Volatile var subTasksHead: TaskNode? = null


        init {
            thread = threadFactory(this, name, id)
            if (!thread.isAlive) {
                thread.start()
            }
        }


        private fun changeState(expect: Int, update: Int) {
            while (state.compareAndSet(expect, update));
        }

        private fun tryChangeState(expect: Int, update: Int): Boolean = state.compareAndSet(expect, update)

        override fun run() {
            while (alive) {
                changeState(pending, polling)
                try {
                    pollResult = if (keepAlive) pollStrategy.get() else workQueue.poll(block = false)
                    if (!keepAlive || pollResult == null) {
                        // not interested in keeping alive and no work left. Die.
                        if (deRegisterRequest(this)) {
                            //de register succeeded, shutdown this context.
                            interrupt()
                        }
                    }

                } catch(e: InterruptedException) {
                    // we need to catch it for graceful shutdown and can ignore it
                    // because this can only mean polling has failed and therefor pollResult == null
                } finally {
                    changeState(polling, pending)
                    if (!keepAlive && alive) {
                        // if at this point keepAlive is false but the context itself is alive
                        // the thread might be in an interrupted state, we need to clear that because
                        // we are probably in graceful shutdown mode and we don't want to interfere with any
                        // tasks that need to be executed
                        Thread.interrupted()

                        if (!alive) {
                            // oh you concurrency, you tricky bastard. We might just cleared that interrupted flag
                            // but it can be the case that after checking all the flags this context was interrupted
                            // for a full shutdown and we just cleared that. So interrupt again.
                            thread.interrupt()
                        }
                    }
                }

                val fn = pollResult
                if (fn != null) {
                    try {
                        changeState(pending, running)
                        try {
                            fn()
                        } finally {
                            // Need to switch back to pending as soon as possible
                            // otherwise funny things might happen when we use cancel.
                            // cancel may only interrupt during running state.
                            changeState(running, pending)
                        }
                    } catch(e: InterruptedException) {
                        // we only want to report unexpected interrupted exception. The expected
                        // cases are cancellation of a task or a total shutdown of the dispatcher.
                        // `pollResult == null` means the task has been cancelled.
                        if (pollResult != null && alive) {
                            exceptionHandler(e)
                        }

                    } catch(e: Exception) {
                        exceptionHandler(e)
                    } catch(t: Throwable) {
                        // I think the StackOverFlowError is the only one we can reasonably recover from since the
                        // complete stack has been unwound at this point.
                        if (t !is StackOverflowError) {
                            // This can be anything. Most likely out of memory errors, so everything can go haywire
                            // from here. Let's *try* to gracefully dismiss this thread by un registering from the pool and
                            // die.
                            deRegisterRequest(this, force = true)
                        }

                        // Try to report it
                        errorHandler(t)


                    } finally {
                        // No matter what, we are going to try to clear the interrupted flag if any.
                        // this is because this thread can be in an interrupted state because of cancellation,
                        // the fact that we might be dead or simply by user code which has bluntly called interrupt().
                        // whatever reason we are going to clear it, because it interferes with polling for and running
                        // the next task.
                        Thread.interrupted()

                        // the only state that may keep the thread in interrupted state is when alive == false.
                        // but since we are the last statement the loop is going to end anyway because it checks
                        // whether we're still alive.
                    }
                }
            }
        }

        fun runNext(): Boolean {
            //can only be called by self!
            //So must be in a running or cancelling/mutating state.

            //get the current running state and set to mutating
            var parentState: Int
            do {
                parentState = state.get()
                if (parentState < running) continue
            } while (!tryChangeState(parentState, mutating))

            // don't use poll strategy, we are hoping more work
            // because we are waiting for something to finish.
            val fn = workQueue.poll(block = false)

            // can still be null if other threads have picked up the work
            //we are waiting for
            var hasRun = false
            if (fn != null) {
                val node = TaskNode(fn)

                if (subTasksHead == null) {
                    subTasksHead = node
                } else {
                    var tail = subTasksHead!!
                    while (tail.next != null) tail = tail.next!!
                    tail.next = node
                    node.prev = tail
                }

                changeState(mutating, parentState + 1)
                try {
                    fn()
                } catch(e: InterruptedException) {
                    // we only want to report unexpected interrupted exception. The expected
                    // cases are cancellation of a task or a total shutdown of the dispatcher.
                    if (!isCancelledInChain() && alive) {
                        exceptionHandler(e)
                    }
                } catch(e: Exception) {
                    exceptionHandler(e)
                } catch(t: Throwable) {
                    // I think the StackOverFlowError is the only one we can reasonably recover from since the
                    // complete stack has been unwound at this point.
                    if (t is StackOverflowError) {

                        errorHandler(t)
                    } else {
                        // rethrow and let the main runner handle this
                        throw t
                    }
                } finally {
                    // No matter what, we are going to try to clear the interrupted flag if any.
                    // this is because this thread can be in an interrupted state because of cancellation,
                    // the fact that we might be dead or simply by user code which has bluntly called interrupt().
                    // whatever reason we are going to clear it, because it interferes with polling for and running
                    // the next task.
                    Thread.interrupted()
                }

                // detach task node
                val prev = node.prev
                when (prev) {
                    null -> subTasksHead = null
                    else -> prev.next = null
                }

                changeState(parentState + 1, mutating)
                hasRun = true
            }

            // We might have cancelled some task and the head
            // task might be it. If so, interrupted the thread
            // for that task
            if (isHeadCancelled()) {
                thread.interrupt()
            }

            changeState(mutating, parentState)
            return hasRun
        }


        /*
        Become 'kamikaze' or just really suicidal. Try to bypass the pollStrategy for quick and clean death.
         */
        fun kamikaze() {
            keepAlive = false
            if (tryChangeState(polling, mutating)) {
                //this thread is in the polling state and we are going to interrupt it.
                //because our polling strategies might be blocked
                thread.interrupt()
                changeState(mutating, polling)
            }

        }

        fun isHeadCancelled(): Boolean {
            if (subTasksHead == null && pollResult == null) return true

            if (subTasksHead != null) {
                var tail = subTasksHead!!
                while (tail.next != null) tail = tail.next!!
                if (tail.cancelled) return true
            }
            return false
        }

        fun isCancelledInChain(): Boolean {
            if (pollResult == null) return true
            subTasksHead.iterate {
                if (it.cancelled) return true
            }
            return false
        }

        fun isTaskInChain(task: () -> Unit): Boolean {
            if (task === pollResult) return true
            return nodeForTask(task) != null
        }


        fun nodeForTask(task: () -> Unit): TaskNode? {
            subTasksHead.iterate {
                if (it.task === task) return it
            }
            return null
        }


        fun cancel(task: () -> Unit): Boolean {
            while (isTaskInChain(task)) {
                //Can't catch this at state pending or polling, loop while we are running

                //if we're in running state try interrupting it
                //so we need to claim the time for doing this by going from a running to
                //mutating state
                val currentState = state.get()
                if (currentState > 0 && tryChangeState(currentState, mutating)) {

                    //Though we successfully changed from running to mutating it might
                    //just be a complete different task already. check again
                    val cancelable = isTaskInChain(task)
                    if (cancelable) {
                        if (pollResult === task) {
                            //set pollResult to null to signal we cancelled this task.
                            //Avoid redundant InterruptedException callbacks
                            pollResult = null

                            //interrupt this thread if it's actually running
                            if (subTasksHead == null) {
                                thread.interrupt()
                            }


                        } else {
                            val node = nodeForTask(task)!!
                            node.cancelled = true
                            if (node.next == null) {
                                //tail node, thus running, thus interrupt
                                thread.interrupt()
                            }
                        }
                    }

                    // always change back to running
                    changeState(mutating, currentState)

                    // return whether we could cancel a task
                    return cancelable
                }
            }
            return false
        }

        fun interrupt() {
            alive = false
            thread.interrupt()
            deRegisterRequest(this, force = true)
        }

        fun isCurrentThread(): Boolean = Thread.currentThread() == thread
    }


}


public interface PollStrategy<V : Any> {
    fun get(): V?
}

private interface PollStrategyFactory<V : Any> {
    fun build(pollable: Pollable<V>): PollStrategy<V>
}

private class ChainPollStrategyFactory<V : Any>(private val factories: List<PollStrategyFactory<V>>) : PollStrategyFactory<V> {
    override fun build(pollable: Pollable<V>): PollStrategy<V> {
        val strategies = factories.map { it.build(pollable) }
        return ChainPollStrategy(strategies)
    }
}


private class ChainPollStrategy<V : Any>(private val strategies: List<PollStrategy<V>>) : PollStrategy<V> {
    override fun get(): V? {
        strategies.forEach { strategy ->
            val result = strategy.get()
            if (result != null) return result
        }
        return null
    }
}

private class YieldingPollStrategyFactory<V : Any>(private val attempts: Int = 1000) : PollStrategyFactory<V> {
    override fun build(pollable: Pollable<V>): PollStrategy<V> {
        return YieldingPollStrategy(pollable, attempts)
    }

}

private class YieldingPollStrategy<V : Any>(private val pollable: Pollable<V>,
                                            private val attempts: Int) : PollStrategy<V> {
    override fun get(): V? {
        for (i in 0..attempts) {
            val value = pollable.poll(block = false)
            if (value != null) return value
            Thread.yield()
            if (Thread.currentThread().isInterrupted) break
        }
        return null
    }
}


private class BusyPollStrategyFactory<V : Any>(private val attempts: Int = 1000) : PollStrategyFactory<V> {
    override fun build(pollable: Pollable<V>): PollStrategy<V> {
        return BusyPollStrategy(pollable, attempts)
    }

}

private class BusyPollStrategy<V : Any>(private val pollable: Pollable<V>,
                                        private val attempts: Int) : PollStrategy<V> {
    override fun get(): V? {
        for (i in 0..attempts) {
            val value = pollable.poll(block = false)
            if (value != null) return value
            if (Thread.currentThread().isInterrupted) break
        }
        return null
    }
}


private class SleepingPollStrategyFactory<V : Any>(private val attempts: Int = 100,
                                                   private val sleepTimeMs: Long = 10) : PollStrategyFactory<V> {
    override fun build(pollable: Pollable<V>): PollStrategy<V> {
        return SleepingPollStrategy(pollable, attempts, sleepTimeMs)
    }

}

private class SleepingPollStrategy<V : Any>(private val pollable: Pollable<V>,
                                            private val attempts: Int,
                                            private val sleepTimeMs: Long) : PollStrategy<V> {
    override fun get(): V? {
        for (i in 0..attempts) {
            val value = pollable.poll(block = false)
            if (value != null) return value
            Thread.sleep(sleepTimeMs)
        }
        return null
    }
}

private class BlockingSleepPollStrategyFactory<V : Any>(private val attempts: Int = 100,
                                                        private val sleepTimeMs: Long = 10) : PollStrategyFactory<V> {
    override fun build(pollable: Pollable<V>): PollStrategy<V> {
        return BlockingSleepPollStrategy(pollable, attempts, sleepTimeMs)
    }

}

private class BlockingSleepPollStrategy<V : Any>(private val pollable: Pollable<V>,
                                                 private val attempts: Int = 100,
                                                 private val sleepTimeMs: Long = 10) : PollStrategy<V> {
    override fun get(): V? {
        for (i in 0..attempts) {
            val value = pollable.poll(block = true, timeoutMs = sleepTimeMs)
            if (value != null) return value
        }
        return null
    }
}

private class BlockingPollStrategyFactory<V : Any>() : PollStrategyFactory<V> {
    override fun build(pollable: Pollable<V>): PollStrategy<V> {
        return BlockingPollStrategy(pollable)
    }
}

private class BlockingPollStrategy<V : Any>(private val pollable: Pollable<V>) : PollStrategy<V> {
    override fun get(): V? = pollable.poll(block = true)
}

//on multicores, leave one thread out so that
//the dispatcher thread can run on it's own core
private val threadAdvice: Int
    get() = Math.max(availableProcessors - 1, 1)

private val availableProcessors: Int
    get() = Runtime.getRuntime().availableProcessors()



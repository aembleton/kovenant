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


/**
 * a Dispatcher 'executes' a task somewhere in the future. Yes, I'd rather called this an Executor but
 * that is way to similar to java's Executors. This way it's more obvious to distinct the two types. The reason why
 * I don't use java's Executor directly is because it pollutes the API, I want to keep this platform agnostic.
 */
interface Dispatcher {

    /**
     *
     * @param task the task to be executed by this dispatcher
     * @return true if the task was scheduled, false if this dispatcher has shutdown or is shutting down
     */
    fun offer(task: () -> Unit): Boolean

    /**
     * Stops this dispatcher therefor stops accepting new tasks. This methods blocks and executes everything that
     * is still queued unless force or timeOutMs is used. Thus by default this method returns an empty list.
     * Any subsequent (concurrent) calls to this function will be ignored and just returns an empty list.
     *
     * @param force forces shutdown by cancelling all running tasks and killing threads as soon as possible
     * @param timeOutMs for every timeOutMs > 0 the dispatcher tries to shutdown gracefully. Meaning flushing the queue
     *                  until the timeOutMs is reached and then forcing shutdown.
     * @param block blocks until done if true, returns with an empty list otherwise
     *
     * @return tasks that where not yet started, does not include cancelled tasks
     */
    fun stop(force: Boolean = false, timeOutMs: Long = 0, block: Boolean = true): List<() -> Unit> = throw UnsupportedException("stop() is not implemented")

    /**
     * Cancels a previously scheduled task, Does, of course, not execute the provided task.
     * Note that this method cancels tasks by means of equality, so be careful when using method
     * references which effectively creates multiple Function instances and are there not equal.
     *
     * @return true if the task was cancelled, false otherwise
     */
    fun tryCancel(task: () -> Unit): Boolean = false


    /**
     * @return true if dispatcher is shutdown all threads have been shutdown, false otherwise
     */
    val terminated: Boolean get() = throw UnsupportedException()

    /**
     * @return true if shutdown has been invoked, false otherwise.
     */
    val stopped: Boolean get() = throw UnsupportedException()
}

/**
 * A ProcessAwareDispatcher knows about the tasks it executes and can be used to optimize some calls
 * by avoiding scheduling but using direct execution instead
 */
public interface ProcessAwareDispatcher : Dispatcher {
    /**
     * Determines whether the caller is operating on a process/thread/strand/fiber/etc owned by this
     * dispatcher.
     *
     * @return true if caller is operating on a process/thread/strand/fiber/etc owned by this dispatcher, false otherwise.
     */
    fun ownsCurrentProcess(): Boolean
}

public fun buildDispatcher(body: DispatcherBuilder.() -> Unit): Dispatcher = concreteBuildDispatcher(body)

public interface DispatcherBuilder {
    var name: String

    var concurrentTasks: Int
    var exceptionHandler: (Exception) -> Unit
    var errorHandler: (Throwable) -> Unit

    var workQueue: WorkQueue<() -> Unit>

    fun pollStrategy(body: PollStrategyBuilder.() -> Unit)
}

public interface PollStrategyBuilder {
    fun yielding(numberOfPolls: Int = 1000)
    fun busy(numberOfPolls: Int = 1000)
    fun blocking()
    fun sleeping(numberOfPolls: Int = 10, sleepTimeInMs: Long = 10)
    fun blockingSleep(numberOfPolls: Int = 10, sleepTimeInMs: Long = 10)
}


public class DirectDispatcher private constructor() : Dispatcher {
    companion object {
        public val instance: Dispatcher = DirectDispatcher()
    }

    override fun offer(task: () -> Unit): Boolean {
        task()
        return true
    }

    override fun stop(force: Boolean, timeOutMs: Long, block: Boolean): List<() -> Unit> {
        throw UnsupportedException()
    }

    override fun tryCancel(task: () -> Unit): Boolean {
        return false
    }

    override val terminated: Boolean
        get() = throw UnsupportedException()
    override val stopped: Boolean
        get() = throw UnsupportedException()

}



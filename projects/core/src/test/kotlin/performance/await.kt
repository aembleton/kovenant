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

package performance.await

import nl.komponents.kovenant.*
import support.fib
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

val numberOfWorkerThreads = Runtime.getRuntime().availableProcessors()
val attempts = 10
val warmupRounds = 100
val performanceRounds = 1000
val napTimeSeconds = 3L
val fibN = 13

val cbDispatch = buildDispatcher {
    concurrentTasks = 1
    pollStrategy {
        yielding()
        blocking()
    }
}
val workDispatch = buildDispatcher {
    pollStrategy {
        yielding()
        blocking()
    }
}

fun main(args: Array<String>) {
    println(
            """Performance test
- samples:      $attempts
- warmupRounds: $warmupRounds
- timingRounds: $performanceRounds
- workers:      $numberOfWorkerThreads
- sleep:        $napTimeSeconds seconds
""")

    Kovenant.context {
        callbackContext.dispatcher = cbDispatch
        workerContext.dispatcher = workDispatch
    }


    var awaitDeltas = ArrayList<Long>(attempts)
    var chainDeltas = ArrayList<Long>(attempts)

    for (i in 1..attempts) {
        validateAwait(warmupRounds)
        val startExc = System.currentTimeMillis()
        validateAwait(performanceRounds)
        val deltaExc = System.currentTimeMillis() - startExc

        println("Await attempt $i took $deltaExc ms")
        awaitDeltas.add(deltaExc)


    }
    napTime()

    for (i in 1..attempts) {
        validateChain(warmupRounds)
        val startDis = System.currentTimeMillis()
        validateChain(performanceRounds)
        val deltaDis = System.currentTimeMillis() - startDis

        println("Chain attempt $i took $deltaDis ms")
        chainDeltas.add(deltaDis)
        //napTime()

    }

    val quarter = attempts / 4
    if (quarter > 0) {
        fun ArrayList<Long>.firstQR(): ArrayList<Long> {
            sort { a, b -> (b - a).toInt() }
            val maxIdx = size - quarter
            return ArrayList(subList(quarter, maxIdx))
        }
        awaitDeltas = awaitDeltas.firstQR()
        chainDeltas = chainDeltas.firstQR()
    }

    fun ArrayList<Long>.avarage(): Long = sum() / size
    val averageMsPromise = chainDeltas.avarage()
    val averageMsFutures = awaitDeltas.avarage()
    val factor = averageMsFutures.toDouble() / averageMsPromise.toDouble()

    println("On average with ${awaitDeltas.size} samples of the 1QR, " +
            "Await is a factor ${fasterOrSlower(factor)}")

    workDispatch.stop()
    cbDispatch.stop()
}

private fun napTime() {
    if (napTimeSeconds > 0) {
        System.gc()
        Thread.sleep(TimeUnit.MILLISECONDS.convert(napTimeSeconds, TimeUnit.SECONDS))
    }
}


fun validateChain(n: Int) {
    val promises = Array(n) {
        async {
            fib(fibN)
        } then {
            it shl 1
        }
    }

    waitFor(*promises)
}

fun validateAwait(n: Int) {
    val promises = Array(n) {
        async {
            val fib12 = await(fibonacci(fibN))
            await(timesTwo(fib12))
        }
    }
    waitFor(*promises)
}

fun fibonacci(n: Int): Promise<Int, Exception> = async { fib(n) }
fun timesTwo(n: Int): Promise<Int, Exception> = async { n shl 1 }

private fun waitFor(vararg promises: Promise<*, *>) {
    val latch = CountDownLatch(promises.size)
    promises.forEach {
        p ->
        p always { latch.countDown() }
    }
    latch.await()
}

private fun Double.format(pattern: String): String = DecimalFormat(pattern).format(this)

private fun fasterOrSlower(value: Double): String {
    if (value == 0.0) {
        return "<undetermined>"
    }
    if (value < 1.0) {

        return "${(1.0 / value).format("##0.00")} slower"
    }
    return "${value.format("##0.00")} faster"
}
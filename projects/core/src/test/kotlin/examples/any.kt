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

package examples.any

import nl.komponents.kovenant.any
import nl.komponents.kovenant.async
import java.util.*

fun main(args: Array<String>) {
    val promises = Array(10) { n ->
        async {
            while (!Thread.currentThread().isInterrupted) {
                val luckyNumber = Random(System.currentTimeMillis() * (n + 1)).nextInt(100)
                if (luckyNumber == 7) break
            }
            "Promise number $n won!"
        }
    }

    any (*promises) success { msg ->
        println(msg)
        println()

        promises.forEachIndexed { n, p ->
            p.fail { println("promise[$n] was canceled") }
            p.success { println("promise[$n] finished") }
        }
    }
}


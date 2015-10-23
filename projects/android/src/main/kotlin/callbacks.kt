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
package nl.komponents.kovenant.android



import nl.komponents.kovenant.Context
import nl.komponents.kovenant.DirectDispatcherContext
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.Promise
import nl.komponents.kovenant.ui.KovenantUi
import nl.komponents.kovenant.ui.UiContext
import nl.komponents.kovenant.ui.dispatcherContextFor
import java.lang.ref.WeakReference
import nl.komponents.kovenant.ui.alwaysUi as newAlwaysUi
import nl.komponents.kovenant.ui.failUi as newFailUi
import nl.komponents.kovenant.ui.promiseOnUi as newPromiseOnUi
import nl.komponents.kovenant.ui.successUi as newSuccessUi

public fun <C : android.content.Context, V, E> C.successUi(promise: Promise<V, E>,
                                                           uiContext: UiContext = KovenantUi.uiContext,
                                                           context: Context = Kovenant.context,
                                                           body: C.(V) -> Unit): Promise<V, E> {
    val dispatcherContext = uiContext.dispatcherContextFor(context)
    if (promise.isDone()) {
        if (promise.isSuccess()) {
            try {
                val value = promise.get()
                body(value)
            } catch(e: Exception) {
                dispatcherContext.errorHandler(e)
            }
        }
        return promise
    }

    val activityRef = WeakReference<C?>(this)

    promise.success(DirectDispatcherContext) {
        val androidContext = activityRef.get()
        if (androidContext != null) {
            //TODO, need to do this upon actual scheduling.
        }
    }

    return promise
}


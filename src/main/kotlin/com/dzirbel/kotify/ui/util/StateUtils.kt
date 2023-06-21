package com.dzirbel.kotify.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Collects the [callback] value as a [State], initially null, which is assigned a value when [callback] returns.
 *
 * The result of [callback] is [remember]ed as long as [key] is unchanged and only called once as long as the caller is
 * in the composition.
 */
@Composable
fun <T> callbackAsState(
    key: Any,
    context: CoroutineContext = EmptyCoroutineContext,
    callback: suspend () -> T?,
): State<T?> {
    return produceState<T?>(initialValue = null, key1 = key) {
        if (context == EmptyCoroutineContext) {
            callback()?.let { this@produceState.value = it }
        } else {
            withContext(context) {
                callback()?.let { this@produceState.value = it }
            }
        }
    }
}

@Composable
fun <T> StateFlow<T>.collectAsStateSwitchable(key: Any?, context: CoroutineContext = Dispatchers.IO): State<T> {
    return collectAsStateSwitchable(initial = { value }, key = key, context = context)
}

/**
 * Collects values from this [Flow] and represents its latest value via [State].
 *
 * Unlike [collectAsState], this function is capable of switching between different input [Flow]s via [key]; that is, if
 * [key] ever changes, the [initial] value will be fetched again and used. [collectAsState] does not change its state
 * when the underlying [Flow] changes, only when it emits new values.
 */
@Composable
fun <T> Flow<T>.collectAsStateSwitchable(
    initial: () -> T,
    key: Any?,
    context: CoroutineContext = Dispatchers.IO,
): State<T> {
    // copied from internal compose code
    class ProduceStateScopeImpl<T>(state: MutableState<T>, override val coroutineContext: CoroutineContext) :
        ProduceStateScope<T>, MutableState<T> by state {

        override suspend fun awaitDispose(onDispose: () -> Unit): Nothing {
            try {
                suspendCancellableCoroutine<Nothing> { }
            } finally {
                onDispose()
            }
        }
    }

    val result = remember(key) { mutableStateOf(initial()) }
    LaunchedEffect(this, context) {
        ProduceStateScopeImpl(result, coroutineContext).run {
            if (context == EmptyCoroutineContext) {
                collect { value = it }
            } else {
                withContext(context) {
                    collect { value = it }
                }
            }
        }
    }
    return result
}

/**
 * Returns the value iteratively generated by [generate], which returns the next value to expose and a delay in
 * milliseconds before it should be called to generate the next value.
 */
@Composable
fun <T> iterativeState(key: Any? = null, generate: () -> Pair<T, Long>): T {
    val (initialValue, initialDelay) = remember(key) { generate() }

    return produceState(initialValue = initialValue, key1 = key) {
        delay(initialDelay)
        while (true) {
            val (result, delay) = generate()

            value = result

            delay(delay)
        }
    }.value
}

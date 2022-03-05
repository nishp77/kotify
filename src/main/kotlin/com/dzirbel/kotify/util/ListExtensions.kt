package com.dzirbel.kotify.util

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Returns a new [List] containing the elements of this [List] and the given [elements], inserted according to the
 * natural order of [selector].
 *
 * This [List] must be sorted according to [selector].
 */
fun <T, R : Comparable<R>> List<T>.plusSorted(elements: List<T>, selector: (T) -> R): List<T> {
    if (elements.isEmpty()) return this

    val result = ArrayList<T>(size + elements.size)
    result.addAll(this)

    for (element in elements) {
        var index = result.binarySearchBy(key = selector(element), selector = selector)
        if (index < 0) {
            index = -(index + 1)
        }
        result.add(index, element)
    }

    return result
}

/**
 * Returns a new [List] containing the elements of this [List] except for the element at [index].
 */
fun <T> List<T>.minusAt(index: Int): List<T> {
    require(index in indices)

    val result = ArrayList<T>(size - 1)
    forEachIndexed { i, element ->
        if (i != index) {
            result.add(element)
        }
    }
    return result
}

/**
 * Maps values in this [List] via [transform], computing each transformation in parallel.
 */
suspend fun <T, R> List<T>.mapParallel(transform: suspend (T) -> R): List<R> {
    return coroutineScope {
        map { element ->
            async { transform(element) }
        }
    }
        .map { it.await() }
}

/**
 * Flat maps values in this [List] via [transform], computing each transformation in parallel.
 */
suspend fun <T, R> List<T>.flatMapParallel(transform: suspend (T) -> List<R>): List<R> {
    return mapParallel(transform).flatten()
}

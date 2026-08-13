package app.gamenative.events

import kotlin.reflect.KClass
import timber.log.Timber

// written with the help of Claude 3.5
sealed interface Event<T>

class EventDispatcher {
    /**
     * Identity-keyed registry (spec 2026-08-12, M3 — C3). The key of every listener is the
     * LAMBDA INSTANCE itself, matched by `===` on [off]. The old `listener.toString()`
     * key failed whenever two different lambda instances produced the same toString()
     * (stable val handlers re-created per recomposition churn off/on — if one `off()`
     * failed its identity match, duplicate listeners accumulated forever).
     */
    val listeners = mutableMapOf<KClass<out Event<*>>, MutableList<Pair<Any, EventListener<Event<*>, *>>>>()

    open class EventListener<E : Event<T>, T>(
        val listener: (E) -> T,
        val once: Boolean = false,
    )

    inline fun <reified E : Event<T>, T> on(noinline listener: (E) -> T) {
        addListener<E, T>(listener, false)
    }

    inline fun <reified E : Event<T>, T> once(noinline listener: (E) -> T) {
        addListener<E, T>(listener, true)
    }

    @Suppress("UNCHECKED_CAST")
    inline fun <reified E : Event<T>, T> addListener(
        noinline listener: (E) -> T,
        once: Boolean,
    ) {
        val eventClass = E::class
        val typedListener = Pair(
            listener,
            EventListener<Event<T>, T>({ event ->
                // Log.d("EventDispatcher", "Dispatching event $event to $listener")
                listener(event as E)
            }, once),
        )
        // Log.d("EventDispatcher", "Putting $typedListener in $eventClass")
        listeners.getOrPut(eventClass) { mutableListOf() }.add(typedListener as Pair<Any, EventListener<Event<*>, *>>)
        Timber.d("EventBus: on %s count=%d", eventClass.simpleName, listeners[eventClass]?.size ?: 0)
    }

    inline fun <reified E : Event<T>, T> off(noinline listener: (E) -> T) {
        val eventClass = E::class
        val before = listeners[eventClass]?.size ?: 0
        listeners[eventClass]?.removeIf {
            // Log.d("EventDispatcher", "Removing if ${it.first} == $listener")
            it.first === listener
        }
        val after = listeners[eventClass]?.size ?: 0
        if (before != after) {
            Timber.d("EventBus: off %s count=%d", eventClass.simpleName, after)
        } else {
            // An off() that removed nothing is a red flag: a listener registered by a
            // DIFFERENT lambda instance than the one being removed (C3 symptom). The
            // identity registry makes this near-impossible; keep the log to catch strays.
            Timber.w("EventBus: off %s matched NOTHING (count=%d)", eventClass.simpleName, after)
        }
    }

    inline fun <reified E : Event<*>> clearAllListenersOf() {
        val currentKeys = listeners.keys.toList()
        for (key in currentKeys) {
            if (key is E) {
                listeners.remove(key)
            }
        }
    }
    fun clearAllListeners() {
        listeners.clear()
    }

    /**
     * Listener count per event class — the M8 instrumentation baseline (spec 2026-08-12):
     * V1 accepts that KeyEvent/MotionEvent counts stay constant (±0) across 20 open/close
     * cycles of menu + browser + edit mode.
     */
    fun listenerCount(): Map<String, Int> =
        listeners.map { (eventClass, list) ->
            (eventClass.simpleName ?: eventClass.toString()) to list.size
        }.toMap()

    inline fun <reified E : Event<T>, reified T> emit(event: E, noinline resultAggregator: ((Array<T>) -> T)? = null): T? {
        val eventClass = E::class
        // Log.d("EventDispatcher", "Emitting $eventClass")
        return listeners[eventClass]?.let { eventListeners ->
            // Create a new list for iteration to avoid concurrent modification
            val results = eventListeners.toList().map { eventListener ->
                eventListener.second.listener(event) as T
            }.toTypedArray()
            // Remove one-time listeners after execution
            eventListeners.removeIf { it.second.once }
            resultAggregator?.let { it(results) }
        }
    }

    // Java-friendly version that doesn't use reified generics
    @Suppress("UNCHECKED_CAST")
    fun emitJava(event: Event<*>): Any? {
        val eventClass = event::class
        return listeners[eventClass]?.let { eventListeners ->
            val results = eventListeners.toList().map { eventListener ->
                eventListener.second.listener(event)
            }.toTypedArray()
            // Remove one-time listeners after execution
            eventListeners.removeIf { it.second.once }
            results.firstOrNull()
        }
    }
}

package app.gamenative.events

import kotlin.reflect.KClass

// written with the help of Claude 3.5
sealed interface Event<T>

class EventDispatcher {
    val listeners = mutableMapOf<KClass<out Event<*>>, MutableList<Pair<String, EventListener<Event<*>, *>>>>()

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
            listener.toString(),
            EventListener<Event<T>, T>({ event ->
                // Log.d("EventDispatcher", "Dispatching event $event to $listener")
                listener(event as E)
            }, once),
        )
        // Log.d("EventDispatcher", "Putting $typedListener in $eventClass")
        listeners.getOrPut(eventClass) { mutableListOf() }.add(typedListener as Pair<String, EventListener<Event<*>, *>>)
    }

    inline fun <reified E : Event<T>, T> off(noinline listener: (E) -> T) {
        val eventClass = E::class
        listeners[eventClass]?.removeIf {
            // Log.d("EventDispatcher", "Removing if ${it.first} == $listener")
            it.first == listener.toString()
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

// Ported from donors/orchestration-java/EventRouter.java
// Original repo: TITANICBHAI/AI-ASSISTANT-INCOMPLETE
// Changes: coroutine-based fan-out (Dispatchers.Default), MutableSharedFlow-style
// subscriber registry, topic + wildcard subscriptions, no Android Handler/Looper.

package com.ariaagent.mobile.core.orchestration

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Event router for the orchestration layer.
 *
 * Each subscriber is registered against an event-type string (or [WILDCARD]
 * for "all events"). [publish] fans out to subscribers on a background
 * coroutine; [publishSync] runs them on the caller's thread for cases that
 * need ordering (state-machine transitions, tests).
 *
 * This is intentionally separate from [com.ariaagent.mobile.core.events.AgentEventBus]:
 * that bus is the agent's UI-facing event firehose; this router carries
 * lifecycle / orchestration events between system components.
 */
class EventRouter {

    /** subscriber callback type: receives the event, runs on Dispatchers.Default. */
    fun interface Subscriber {
        fun onEvent(event: OrchestrationEvent)
    }

    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<Subscriber>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun subscribe(eventType: String, subscriber: Subscriber) {
        subscribers.computeIfAbsent(eventType) { CopyOnWriteArrayList() }.add(subscriber)
        Log.d(TAG, "Subscriber added for event type: $eventType")
    }

    fun unsubscribe(eventType: String, subscriber: Subscriber) {
        subscribers[eventType]?.remove(subscriber)
    }

    /** Fire-and-forget publish onto Dispatchers.Default. */
    fun publish(event: OrchestrationEvent) {
        scope.launch { dispatch(event) }
    }

    /** Synchronous publish on the caller's thread. */
    fun publishSync(event: OrchestrationEvent) {
        dispatch(event)
    }

    private fun dispatch(event: OrchestrationEvent) {
        val typed = subscribers[event.eventType].orEmpty()
        val wildcard = subscribers[WILDCARD].orEmpty()

        if (typed.isEmpty() && wildcard.isEmpty()) {
            Log.d(TAG, "No subscribers for event: ${event.eventType}")
            return
        }
        Log.d(
            TAG,
            "Publishing event: ${event.eventType} from ${event.source} " +
                "to ${typed.size + wildcard.size} subscriber(s)",
        )
        for (sub in typed) safeDeliver(sub, event)
        for (sub in wildcard) safeDeliver(sub, event)
    }

    private fun safeDeliver(subscriber: Subscriber, event: OrchestrationEvent) {
        try {
            subscriber.onEvent(event)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in subscriber for event ${event.eventType}", t)
        }
    }

    fun shutdown() {
        scope.cancel()
        subscribers.clear()
    }

    companion object {
        private const val TAG = "EventRouter"
        const val WILDCARD = "*"
    }
}

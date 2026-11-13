package com.islandbar.data

import com.islandbar.model.LiveActivity
import com.islandbar.model.TransientEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide state shared between the notification listener (producer),
 * the overlay (consumer) and the settings screen.
 */
object IslandRepository {
    private val lock = Any()
    private val map = LinkedHashMap<String, LiveActivity>()

    private val _activities = MutableStateFlow<List<LiveActivity>>(emptyList())
    /** Ongoing activities, highest priority first. */
    val activities: StateFlow<List<LiveActivity>> = _activities.asStateFlow()

    private val _transient = MutableStateFlow<TransientEvent?>(null)
    val transient: StateFlow<TransientEvent?> = _transient.asStateFlow()

    val overlayRunning = MutableStateFlow(false)
    val listenerConnected = MutableStateFlow(false)

    fun get(key: String): LiveActivity? = synchronized(lock) { map[key] }

    fun upsert(activity: LiveActivity) = synchronized(lock) {
        map[activity.key] = activity
        publish()
    }

    fun remove(key: String) = synchronized(lock) {
        if (map.remove(key) != null) publish()
    }

    fun removeIf(predicate: (LiveActivity) -> Boolean) = synchronized(lock) {
        if (map.values.removeAll(predicate)) publish()
    }

    private fun publish() {
        _activities.value = map.values.sortedWith(
            compareBy<LiveActivity> { it.type.priority }.thenByDescending { it.postTime }
        )
    }

    fun showTransient(event: TransientEvent) {
        _transient.value = event
    }

    fun dismissTransient(id: Long) {
        _transient.update { if (it?.id == id) null else it }
    }
}

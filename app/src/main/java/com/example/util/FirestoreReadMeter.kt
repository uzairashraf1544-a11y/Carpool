package com.example.util

import android.os.Bundle
import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.QuerySnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Real usage telemetry and quota-exhaustion guard for Firestore.
 * Tracks server reads vs cache hits, document changes per listener, and records listener start counts per path.
 * Dispatches session summaries without consuming Firestore quota.
 */
object FirestoreReadMeter {
    private const val TAG = "FirestoreReadMeter"

    private val serverReadsByPath = ConcurrentHashMap<String, AtomicInteger>()
    private val cacheReadsByPath = ConcurrentHashMap<String, AtomicInteger>()
    private val listenerStartsByPath = ConcurrentHashMap<String, AtomicInteger>()
    private val writesByPath = ConcurrentHashMap<String, AtomicInteger>()

    // Quota Exhaustion & Backoff State (Task T8)
    @Volatile
    var isQuotaExhausted: Boolean = false
        private set

    private val lastQuotaExhaustedTimestamp = AtomicLong(0L)
    const val MIN_BACKOFF_MS = 5 * 60 * 1000L // 5 minutes backoff

    fun recordListenerStart(path: String) {
        listenerStartsByPath.computeIfAbsent(path) { AtomicInteger(0) }.incrementAndGet()
        Log.d(TAG, "LISTENER_START | path=$path | totalStarts=${listenerStartsByPath[path]?.get()}")
    }

    fun recordQuerySnapshot(path: String, snapshot: QuerySnapshot?) {
        if (snapshot == null) return
        if (snapshot.metadata.isFromCache) {
            val count = snapshot.size()
            cacheReadsByPath.computeIfAbsent(path) { AtomicInteger(0) }.addAndGet(count)
        } else {
            // Server read: bill initial doc count or changed docs
            val changesCount = if (snapshot.documentChanges.isNotEmpty()) {
                snapshot.documentChanges.size
            } else {
                snapshot.size()
            }
            serverReadsByPath.computeIfAbsent(path) { AtomicInteger(0) }.addAndGet(changesCount)
            Log.d(TAG, "SERVER_READ_QUERY | path=$path | docs=$changesCount | isFromCache=false")
        }
    }

    fun recordDocumentSnapshot(path: String, snapshot: DocumentSnapshot?) {
        if (snapshot == null) return
        if (snapshot.metadata.isFromCache) {
            cacheReadsByPath.computeIfAbsent(path) { AtomicInteger(0) }.incrementAndGet()
        } else {
            serverReadsByPath.computeIfAbsent(path) { AtomicInteger(0) }.incrementAndGet()
            Log.d(TAG, "SERVER_READ_DOC | path=$path | docs=1 | isFromCache=false")
        }
    }

    fun recordWrite(path: String, count: Int = 1) {
        writesByPath.computeIfAbsent(path) { AtomicInteger(0) }.addAndGet(count)
    }

    fun handleFirestoreError(path: String, error: Throwable?) {
        if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED) {
            isQuotaExhausted = true
            lastQuotaExhaustedTimestamp.set(System.currentTimeMillis())
            Log.e(TAG, "RESOURCE_EXHAUSTED on path=$path. Pausing live listeners, engaging 5-minute backoff.")
        }
    }

    fun shouldRetryAfterExhaustion(): Boolean {
        if (!isQuotaExhausted) return true
        val elapsed = System.currentTimeMillis() - lastQuotaExhaustedTimestamp.get()
        if (elapsed >= MIN_BACKOFF_MS) {
            isQuotaExhausted = false
            return true
        }
        return false
    }

    fun getTotalServerReads(): Int = serverReadsByPath.values.sumOf { it.get() }
    fun getTotalCacheReads(): Int = cacheReadsByPath.values.sumOf { it.get() }
    fun getTotalWrites(): Int = writesByPath.values.sumOf { it.get() }
    fun getListenerStarts(): Map<String, Int> = listenerStartsByPath.mapValues { it.value.get() }
    fun getServerReadsByPath(): Map<String, Int> = serverReadsByPath.mapValues { it.value.get() }

    fun logSessionAnalytics(analyticsLogger: ((eventName: String, params: Map<String, Any>) -> Unit)? = null) {
        val totalServer = getTotalServerReads()
        val totalCache = getTotalCacheReads()
        Log.i(TAG, "SESSION_SUMMARY | serverReads=$totalServer | cacheReads=$totalCache | writes=${getTotalWrites()}")
        analyticsLogger?.invoke(
            "fs_reads_session",
            mapOf(
                "server_reads" to totalServer,
                "cache_reads" to totalCache,
                "writes" to getTotalWrites(),
                "top_paths" to serverReadsByPath.entries.sortedByDescending { it.value.get() }.take(5).joinToString { "${it.key}:${it.value.get()}" }
            )
        )
    }

    fun reset() {
        serverReadsByPath.clear()
        cacheReadsByPath.clear()
        listenerStartsByPath.clear()
        writesByPath.clear()
        isQuotaExhausted = false
        lastQuotaExhaustedTimestamp.set(0L)
    }
}

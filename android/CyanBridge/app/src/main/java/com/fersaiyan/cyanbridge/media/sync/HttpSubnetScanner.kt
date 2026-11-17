package com.fersaiyan.cyanbridge.media.sync

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Port-80 scan of a list of hosts with bounded concurrency, preferring a host whose
 * `media.config` answers. Shared by the Custom resolver (v2.3.0 /24 scan, unchanged) and the
 * Adaptive flow's wrong-IP fallback.
 */
internal class HttpSubnetScanner(
    private val portOpen: (ip: String, timeoutMs: Int) -> Boolean,
    private val mediaConfigOk: (ip: String, timeoutMs: Int) -> Boolean,
) {
    data class Limits(
        val concurrency: Int,
        val connectTimeoutMs: Int,
        val verifyTimeoutMs: Int,
        val overallTimeoutMs: Long,
        /** When nothing serves media.config, return the first host with port 80 open instead. */
        val acceptOpenPortOnly: Boolean,
    )

    suspend fun scan(hosts: List<String>, limits: Limits): String? = supervisorScope {
        val permits = Semaphore(limits.concurrency)
        val found = CompletableDeferred<String?>()
        val firstOpenPortIp = AtomicReference<String?>(null)
        for (ip in hosts) {
            launch(Dispatchers.IO) {
                permits.withPermit {
                    if (found.isCompleted) return@withPermit
                    if (portOpen(ip, limits.connectTimeoutMs)) {
                        firstOpenPortIp.compareAndSet(null, ip)
                        if (mediaConfigOk(ip, limits.verifyTimeoutMs)) found.complete(ip)
                    }
                }
            }
        }
        val verified = withTimeoutOrNull(limits.overallTimeoutMs) { found.await() }
        val result = verified ?: firstOpenPortIp.get().takeIf { limits.acceptOpenPortOnly }
        coroutineContext.cancelChildren()
        result
    }

    companion object {
        /** .1 to .254 of [prefix] (`a.b.c.`), without [excluded] addresses. */
        fun hostsOf(prefix: String, excluded: Set<String> = emptySet()): List<String> =
            (1..254).map { "$prefix$it" }.filterNot { it in excluded }
    }
}

package com.akslabs.circletosearch

/**
 * Process-local ownership gate for one Android assistant invocation.
 *
 * On Android 14+ the voice service is notified before Android binds the session
 * service. If that bind stalls or fails, the voice service can recover through
 * Accessibility without racing a late session callback or opening two overlays.
 */
internal object AssistantInvocationGate {
    data class Lease(
        val sessionId: Long,
        val generation: Long,
    )

    private enum class Owner {
        PREPARING,
        SESSION,
        RECOVERY_CAPTURING,
        RECOVERY_COMMITTED,
        COMPLETE,
    }

    private var generation = 0L
    private var lease: Lease? = null
    private var owner: Owner? = null

    @Synchronized
    fun prepare(sessionId: Long): Lease {
        val current = lease
        if (
            current?.sessionId == sessionId &&
            owner != Owner.COMPLETE
        ) {
            return current
        }

        return Lease(
            sessionId = sessionId,
            generation = ++generation,
        ).also {
            lease = it
            owner = Owner.PREPARING
        }
    }

    /**
     * Resolves a failure callback without replacing a newer invocation. A new
     * lease is allowed only when process-local state was lost before Android
     * delivered the failure callback.
     */
    @Synchronized
    fun leaseForFailure(sessionId: Long): Lease? {
        val current = lease
        if (current != null) return current.takeIf { it.sessionId == sessionId }

        return Lease(
            sessionId = sessionId,
            generation = ++generation,
        ).also {
            lease = it
            owner = Owner.PREPARING
        }
    }

    /**
     * The real assistant session has priority over a recovery capture that has
     * started but has not published an overlay yet.
     */
    @Synchronized
    fun claimSession(sessionId: Long): Lease? {
        val current = lease
        if (current == null || sessionId > current.sessionId) {
            return Lease(
                sessionId = sessionId,
                generation = ++generation,
            ).also {
                lease = it
                owner = Owner.SESSION
            }
        }
        // A callback from an older framework session must not replace a newer
        // invocation that the isolated voice process has already announced.
        if (current.sessionId != sessionId) return null

        return when (owner) {
            Owner.PREPARING,
            Owner.RECOVERY_CAPTURING,
            Owner.SESSION -> current.also { owner = Owner.SESSION }
            Owner.RECOVERY_COMMITTED,
            Owner.COMPLETE,
            null -> null
        }
    }

    @Synchronized
    fun claimRecovery(expected: Lease): Boolean {
        if (lease != expected || owner != Owner.PREPARING) return false
        owner = Owner.RECOVERY_CAPTURING
        return true
    }

    /**
     * Starts recovery in the main process when the lightweight voice process
     * reports that Android did not create a session. Session ids are monotonic;
     * an older delayed watchdog must never replace a newer live invocation.
     */
    @Synchronized
    fun beginExternalRecovery(sessionId: Long): Lease? {
        val current = lease
        if (current?.sessionId == sessionId) {
            return when (owner) {
                Owner.PREPARING -> current.also { owner = Owner.RECOVERY_CAPTURING }
                Owner.SESSION,
                Owner.RECOVERY_CAPTURING,
                Owner.RECOVERY_COMMITTED,
                Owner.COMPLETE,
                null -> null
            }
        }
        if (current != null && sessionId < current.sessionId) return null

        return Lease(
            sessionId = sessionId,
            generation = ++generation,
        ).also {
            lease = it
            owner = Owner.RECOVERY_CAPTURING
        }
    }

    @Synchronized
    fun isSessionOwner(expected: Lease): Boolean =
        lease == expected && owner == Owner.SESSION

    @Synchronized
    fun isRecoveryCapturing(expected: Lease): Boolean =
        lease == expected && owner == Owner.RECOVERY_CAPTURING

    @Synchronized
    fun commitRecovery(expected: Lease): Boolean {
        if (lease != expected || owner != Owner.RECOVERY_CAPTURING) return false
        owner = Owner.RECOVERY_COMMITTED
        return true
    }

    @Synchronized
    fun isRecoveryCommitted(expected: Lease): Boolean =
        lease == expected && owner == Owner.RECOVERY_COMMITTED

    @Synchronized
    fun complete(expected: Lease): Boolean {
        if (
            lease != expected ||
            (owner != Owner.SESSION && owner != Owner.RECOVERY_COMMITTED)
        ) {
            return false
        }
        owner = Owner.COMPLETE
        return true
    }

    /**
     * Releases a failed or cancelled invocation without disturbing a newer
     * assistant request that may already have replaced it.
     */
    @Synchronized
    fun abandon(expected: Lease): Boolean {
        if (lease != expected) return false
        lease = null
        owner = null
        return true
    }

    /**
     * Releases work owned by the recovery receiver without allowing a late
     * recovery callback to clear a real session that already took over the
     * same lease.
     */
    @Synchronized
    fun abandonRecovery(expected: Lease): Boolean {
        if (
            lease != expected ||
            (owner != Owner.RECOVERY_CAPTURING && owner != Owner.RECOVERY_COMMITTED)
        ) {
            return false
        }
        lease = null
        owner = null
        return true
    }
}

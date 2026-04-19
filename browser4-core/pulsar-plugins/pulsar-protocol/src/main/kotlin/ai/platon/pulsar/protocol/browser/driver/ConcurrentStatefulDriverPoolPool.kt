package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.logging.ThrottlingLogger
import ai.platon.pulsar.common.warnForClose
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserId
import com.google.common.annotations.Beta
import java.time.Duration
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ConcurrentSkipListSet

/**
 * A concurrent pool of loading driver pools whose states are tracked.
 * */
class ConcurrentStatefulDriverPoolPool {
    private val logger = getLogger(this)
    private val throttlingLogger = ThrottlingLogger(logger, ttl = Duration.ofMinutes(5))

    private val _workingDriverPools = ConcurrentSkipListMap<BrowserId, LoadingWebDriverPool>()
    private val _retiredDriverPools = ConcurrentSkipListMap<BrowserId, LoadingWebDriverPool>()
    private val _closedDriverPools = ConcurrentSkipListSet<BrowserId>()
    private val _closeHistory = mutableListOf<BrowserId>()

    /**
     * Working driver pools
     * */
    val workingDriverPools: Map<BrowserId, LoadingWebDriverPool> get() = _workingDriverPools

    /**
     * Retired but not closed driver pools
     * */
    val retiredDriverPools: Map<BrowserId, LoadingWebDriverPool> get() = _retiredDriverPools

    /**
     * Closed driver pool ids
     * */
    val closedDriverPools: Set<BrowserId> get() = _closedDriverPools

    /**
     * Check if the browser has no possibility to provide a webdriver for new tasks.
     *
     * @param browserId The id of the browser and its corresponding driver pool.
     * @return True if the browser has no possibility to provide a webdriver for new tasks, false otherwise.
     */
    @Synchronized
    fun hasNoPossibility(browserId: BrowserId): Boolean {
        reassessClosedBrowserId(browserId)
        val result = closedDriverPools.contains(browserId) || retiredDriverPools.containsKey(browserId)

        if (result) {
            val driverPoolClosed = closedDriverPools.contains(browserId)
            val driverPoolRetired = retiredDriverPools.containsKey(browserId)
            val state = listOf("closed" to driverPoolClosed, "retired" to driverPoolRetired)
                .filter { it.second }
                .joinToString(",") { it.first }
            throttlingLogger.info("Browser can not offer any drivers, will be closed (hasNoPossibility) | {} | {}", state, browserId)
        }

        return result
    }
    /**
     * Check if the browser has possibility to provide a webdriver for new tasks.
     *
     * @param browserId The id of the browser and its corresponding driver pool.
     * @return True if the browser has possibility to provide a webdriver for new tasks, false otherwise.
     * */
    @Synchronized
    fun hasPossibility(browserId: BrowserId) = !hasNoPossibility(browserId)
    /**
     * Return the number of new drivers can offer by the pool at the calling time point.
     *
     * If the driver pool is retired or closed, no new driver can offer.
     * If the driver pool is not created yet, it can offer [capacity] drivers at top.
     *
     * @param browserId The id of the webdriver pool.
     * @param capacity The capacity of a driver pool.
     * @return The number of new drivers can offer by the pool at the calling time point.
     * */
    @Synchronized
    fun promisedDriverCount(browserId: BrowserId, capacity: Int): Int {
        reassessClosedBrowserId(browserId)

        if (browserId in closedDriverPools || browserId in retiredDriverPools) {
            return 0
        }

        // if the
        val pool = _workingDriverPools[browserId] ?: return capacity
        return pool.numAvailable
    }
    /**
     * Check if a webdriver pool is full capacity, so it can not provide a webdriver for new tasks.
     * Note that if a driver pool is retired or closed, it's not full capacity.
     *
     * @param browserId The id of the webdriver pool.
     * @return True if the webdriver pool is full capacity, false otherwise.
     * */
    @Synchronized
    fun isFullCapacity(browserId: BrowserId): Boolean {
        reassessClosedBrowserId(browserId)

        if (browserId in closedDriverPools || browserId in retiredDriverPools) {
            return false
        }

        val pool = _workingDriverPools[browserId] ?: return false
        return pool.numWorking + pool.numWaiting >= pool.capacity
    }

    @Synchronized
    fun isRetiredPool(browserId: BrowserId) = retiredDriverPools.contains(browserId)

    @Synchronized
    fun isActivePool(browserId: BrowserId) = _workingDriverPools[browserId]?.isActive == true

    @Beta
    @Synchronized
    fun subscribeDriver(browserId: BrowserId): WebDriver? {
        reassessClosedBrowserId(browserId)

        if (browserId in closedDriverPools || browserId in retiredDriverPools) {
            return null
        }

        val pool = _workingDriverPools[browserId] ?: return null
        val driver = pool.poll()
        // _subscribedDrivers.add(driver)
        return driver
    }

    @Beta
    @Synchronized
    fun subscribeDriver(): WebDriver? {
        val driverPool = _workingDriverPools.values.firstOrNull { it.numAvailable > 0 } ?: return null
        val driver = driverPool.poll()
        // _subscribedDrivers.add(driver)
        return driver
    }

    @Synchronized
    fun computeIfAbsent(browserId: BrowserId, mappingFunction: (BrowserId) -> LoadingWebDriverPool): LoadingWebDriverPool {
        return _workingDriverPools.computeIfAbsent(browserId, mappingFunction)
    }

    @Synchronized
    fun retire(driverPool: LoadingWebDriverPool) {
        val browserId = driverPool.browserId
        _workingDriverPools.remove(browserId)

        if (browserId in _closedDriverPools) {
            // the user can call browser.close() now, so the inconsistent is possible
            // logger.warn("Inconsistent driver pool state: retire pool who is already closed | {}", browserId)
        }

        driverPool.retire()
        _retiredDriverPools[browserId] = driverPool
    }

    @Synchronized
    fun retire(browserId: BrowserId): LoadingWebDriverPool? {
        val retiredDriverPool = _workingDriverPools.remove(browserId)
        if (retiredDriverPool != null) {
            retire(retiredDriverPool)
        }
        return retiredDriverPool
    }

    @Synchronized
    fun close(driverPool: LoadingWebDriverPool) {
        val browserId = driverPool.browserId

        _workingDriverPools.remove(browserId)
        _retiredDriverPools.remove(browserId)
        _closedDriverPools.add(browserId)
        _closeHistory.add(browserId)

        kotlin.runCatching { driverPool.close() }.onFailure { warnForClose(this, it) }
    }

    @Synchronized
    fun close() {
        val pools = workingDriverPools.values + retiredDriverPools.values
        pools.forEach { close(it) }
    }

    /**
     * A new browser with the same browser profile has been created, remove it from the closed pool set
     * this feature is use for the case that the browsers are created with data dirs chosen cyclically,
     * for example, privacy contexts generated by SequentialPrivacyAgentGenerator.
     * */
    private fun reassessClosedBrowserId(browserId: BrowserId) {
        _closedDriverPools.removeIf { it == browserId && it.createTime != browserId.createTime }
    }
}

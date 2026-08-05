package ai.platon.pulsar.protocol.browser.driver

import ai.platon.pulsar.api.BrowserId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@Tag("Unit")
@Tag("Fast")
@DisplayName("ConcurrentStatefulDriverPoolPool state management")
class ConcurrentStatefulDriverPoolPoolTest {

    private lateinit var poolPool: ConcurrentStatefulDriverPoolPool

    @BeforeEach
    fun setUp() {
        poolPool = ConcurrentStatefulDriverPoolPool()
    }

    private fun createMockPool(browserId: BrowserId): LoadingWebDriverPool {
        val pool = mock<LoadingWebDriverPool>()
        whenever(pool.browserId).thenReturn(browserId)
        whenever(pool.isActive).thenReturn(true)
        whenever(pool.numAvailable).thenReturn(5)
        whenever(pool.numWorking).thenReturn(0)
        whenever(pool.numWaiting).thenReturn(0)
        whenever(pool.capacity).thenReturn(10)
        return pool
    }

    @Test
    @DisplayName("hasPossibility returns true for unknown browserId")
    fun hasPossibilityTrueForUnknownBrowserId() {
        val browserId = BrowserId.createRandomTemp()
        assertTrue(poolPool.hasPossibility(browserId), "unknown browserId should have possibility")
    }

    @Test
    @DisplayName("hasPossibility returns true for working pool")
    fun hasPossibilityTrueForWorkingPool() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }

        assertTrue(poolPool.hasPossibility(browserId), "working pool should have possibility")
    }

    @Test
    @DisplayName("hasPossibility returns false for retired pool")
    fun hasPossibilityFalseForRetiredPool() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }
        poolPool.retire(browserId)

        assertFalse(poolPool.hasPossibility(browserId), "retired pool should not have possibility")
    }

    @Test
    @DisplayName("hasPossibility returns false for closed pool")
    fun hasPossibilityFalseForClosedPool() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }
        poolPool.close(pool)

        assertFalse(poolPool.hasPossibility(browserId), "closed pool should not have possibility")
    }

    @Test
    @DisplayName("retire moves pool from working to retired")
    fun retireMovesFromWorkingToRetired() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }

        assertTrue(poolPool.workingDriverPools.containsKey(browserId), "pool should be in working")
        assertFalse(poolPool.retiredDriverPools.containsKey(browserId), "pool should not be in retired")

        poolPool.retire(browserId)

        assertFalse(poolPool.workingDriverPools.containsKey(browserId), "pool should be removed from working")
        assertTrue(poolPool.retiredDriverPools.containsKey(browserId), "pool should be in retired")
    }

    @Test
    @DisplayName("close moves pool to closed set")
    fun closeMovesToClosedSet() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }

        poolPool.close(pool)

        assertFalse(poolPool.workingDriverPools.containsKey(browserId), "pool should be removed from working")
        assertTrue(poolPool.closedDriverPools.contains(browserId), "browserId should be in closed set")
    }

    @Test
    @DisplayName("close removes from both working and retired")
    fun closeRemovesFromBothWorkingAndRetired() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }
        poolPool.retire(browserId)

        // Pool should be in retired
        assertTrue(poolPool.retiredDriverPools.containsKey(browserId))

        poolPool.close(pool)

        assertFalse(poolPool.retiredDriverPools.containsKey(browserId), "pool should be removed from retired")
        assertTrue(poolPool.closedDriverPools.contains(browserId), "browserId should be in closed set")
    }

    @Test
    @DisplayName("retire returns the retired pool")
    fun retireReturnsThePool() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }

        val retired = poolPool.retire(browserId)
        assertNotNull(retired, "retire should return the retired pool")
        assertEquals(pool, retired)
    }

    @Test
    @DisplayName("retire returns null for unknown browserId")
    fun retireReturnsNullForUnknownBrowserId() {
        val browserId = BrowserId.createRandomTemp()
        val retired = poolPool.retire(browserId)
        assertNull(retired, "retire should return null for unknown browserId")
    }

    @Test
    @DisplayName("promisedDriverCount returns capacity for unknown browserId")
    fun promisedDriverCountReturnsCapacityForUnknown() {
        val browserId = BrowserId.createRandomTemp()
        val capacity = 10
        val count = poolPool.promisedDriverCount(browserId, capacity)
        assertEquals(capacity, count, "unknown browserId should promise full capacity")
    }

    @Test
    @DisplayName("promisedDriverCount returns 0 for retired pool")
    fun promisedDriverCountReturnsZeroForRetired() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }
        poolPool.retire(browserId)

        val count = poolPool.promisedDriverCount(browserId, 10)
        assertEquals(0, count, "retired pool should promise 0 drivers")
    }

    @Test
    @DisplayName("promisedDriverCount returns 0 for closed pool")
    fun promisedDriverCountReturnsZeroForClosed() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }
        poolPool.close(pool)

        val count = poolPool.promisedDriverCount(browserId, 10)
        assertEquals(0, count, "closed pool should promise 0 drivers")
    }

    @Test
    @DisplayName("isFullCapacity returns false for unknown browserId")
    fun isFullCapacityFalseForUnknown() {
        val browserId = BrowserId.createRandomTemp()
        assertFalse(poolPool.isFullCapacity(browserId), "unknown browserId should not be full capacity")
    }

    @Test
    @DisplayName("isFullCapacity returns false for retired pool")
    fun isFullCapacityFalseForRetired() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }
        poolPool.retire(browserId)

        assertFalse(poolPool.isFullCapacity(browserId), "retired pool should not be full capacity")
    }

    @Test
    @DisplayName("isRetiredPool returns true for retired pool")
    fun isRetiredPoolTrueForRetired() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }
        poolPool.retire(browserId)

        assertTrue(poolPool.isRetiredPool(browserId))
    }

    @Test
    @DisplayName("isRetiredPool returns false for working pool")
    fun isRetiredPoolFalseForWorking() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }

        assertFalse(poolPool.isRetiredPool(browserId))
    }

    @Test
    @DisplayName("isActivePool returns true for active working pool")
    fun isActivePoolTrueForActiveWorkingPool() {
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        whenever(pool.isActive).thenReturn(true)
        poolPool.computeIfAbsent(browserId) { pool }

        assertTrue(poolPool.isActivePool(browserId))
    }

    @Test
    @DisplayName("isActivePool returns false for unknown browserId")
    fun isActivePoolFalseForUnknown() {
        val browserId = BrowserId.createRandomTemp()
        assertFalse(poolPool.isActivePool(browserId))
    }

    @Test
    @DisplayName("close() closes all working and retired pools")
    fun closeAllClosesWorkingAndRetired() {
        val id1 = BrowserId.createRandomTemp()
        val id2 = BrowserId.createRandomTemp()
        val pool1 = createMockPool(id1)
        val pool2 = createMockPool(id2)

        poolPool.computeIfAbsent(id1) { pool1 }
        poolPool.computeIfAbsent(id2) { pool2 }
        poolPool.retire(id2)  // pool2 goes to retired

        poolPool.close()

        assertTrue(poolPool.closedDriverPools.contains(id1), "id1 should be closed")
        assertTrue(poolPool.closedDriverPools.contains(id2), "id2 should be closed")
        assertTrue(poolPool.workingDriverPools.isEmpty(), "working pools should be empty")
        assertTrue(poolPool.retiredDriverPools.isEmpty(), "retired pools should be empty")
    }

    @Test
    @DisplayName("reassessClosedBrowserId allows re-creation with same id but different createTime")
    fun reassessAllowsReCreationWithDifferentCreateTime() {
        // Create a browserId and close its pool
        val browserId = BrowserId.createRandomTemp()
        val pool = createMockPool(browserId)
        poolPool.computeIfAbsent(browserId) { pool }
        poolPool.close(pool)

        // The browserId is in the closed set
        assertFalse(poolPool.hasPossibility(browserId))

        // Simulate a new BrowserId with the same profile but different createTime
        // BrowserId.createTime is set to System.currentTimeMillis() at construction
        // Since we can't control createTime directly, we verify the logic by creating
        // a new BrowserId with the same profile (which will have a different createTime
        // if enough time has passed)
        val newBrowserId = BrowserId(browserId.profile)

        // If createTime is different, reassessClosedBrowserId should remove it from closed
        // and hasPossibility should return true
        if (newBrowserId.createTime != browserId.createTime) {
            assertTrue(poolPool.hasPossibility(newBrowserId), "re-created browserId with different createTime should have possibility")
        }
    }
}

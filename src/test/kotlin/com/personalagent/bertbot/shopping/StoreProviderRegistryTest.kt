package com.personalagent.bertbot.shopping

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StoreProviderRegistryTest {
    @Test
    fun `highest priority enabled provider is selected`() {
        val low = FakeStoreProvider("low", priority = 10, enabled = true)
        val high = FakeStoreProvider("high", priority = 50, enabled = true)
        val registry = StoreProviderRegistry(listOf(low, high))

        val selected = registry.highestPriorityEnabled()

        assertNotNull(selected)
        assertEquals("high", selected.providerId)
    }

    @Test
    fun `disabled providers are skipped`() {
        val disabled = FakeStoreProvider("disabled", priority = 200, enabled = false)
        val active = FakeStoreProvider("active", priority = 10, enabled = true)
        val registry = StoreProviderRegistry(listOf(disabled, active))

        val selected = registry.highestPriorityEnabled()

        assertNotNull(selected)
        assertEquals("active", selected.providerId)
    }

    @Test
    fun `returns null when no enabled providers exist`() {
        val registry = StoreProviderRegistry(listOf(FakeStoreProvider("p", priority = 1, enabled = false)))

        assertNull(registry.highestPriorityEnabled())
    }

    @Test
    fun `allEnabled returns providers sorted by priority descending`() {
        val a = FakeStoreProvider("a", priority = 5, enabled = true)
        val b = FakeStoreProvider("b", priority = 20, enabled = true)
        val c = FakeStoreProvider("c", priority = 15, enabled = true)
        val disabled = FakeStoreProvider("d", priority = 100, enabled = false)
        val registry = StoreProviderRegistry(listOf(a, b, c, disabled))

        val all = registry.allEnabled()

        assertEquals(listOf("b", "c", "a"), all.map { it.providerId })
    }

    @Test
    fun `default registry selects amazon provider`() {
        val registry = StoreProviderRegistry()

        val selected = registry.highestPriorityEnabled()

        assertNotNull(selected)
        assertEquals(AmazonStoreProvider.PROVIDER_ID, selected.providerId)
    }
}

internal class FakeStoreProvider(
    override val providerId: String,
    override val priority: Int,
    override val enabled: Boolean,
    private val products: List<NormalizedProduct> = emptyList(),
) : StoreProvider {
    override fun searchProducts(
        query: String,
        maxResults: Int,
    ): List<NormalizedProduct> = products

    override fun getProduct(productId: String): NormalizedProduct? =
        products.firstOrNull { it.productId == productId }
}

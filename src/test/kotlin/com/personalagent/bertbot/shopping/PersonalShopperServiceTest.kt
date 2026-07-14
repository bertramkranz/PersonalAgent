package com.personalagent.bertbot.shopping

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalShopperServiceTest {
    @Test
    fun `search returns empty result when no enabled providers`() {
        val registry = StoreProviderRegistry(emptyList())
        val service = PersonalShopperService(registry)

        val result = service.searchProducts("widget")

        assertEquals(emptyList(), result.products)
        assertEquals("none", result.providerId)
    }

    @Test
    fun `search delegates to highest priority enabled provider`() {
        val products =
            listOf(
                NormalizedProduct(productId = "p1", title = "Widget A"),
            )
        val provider = FakeStoreProvider("shop", priority = 10, enabled = true, products = products)
        val registry = StoreProviderRegistry(listOf(provider))
        val service = PersonalShopperService(registry)

        val result = service.searchProducts("widget")

        assertEquals(products, result.products)
        assertEquals("shop", result.providerId)
    }

    @Test
    fun `get product delegates to highest priority enabled provider`() {
        val product = NormalizedProduct(productId = "p1", title = "Widget A")
        val provider = FakeStoreProvider("shop", priority = 10, enabled = true, products = listOf(product))
        val registry = StoreProviderRegistry(listOf(provider))
        val service = PersonalShopperService(registry)

        val result = service.getProduct("p1")

        assertEquals(product, result)
    }

    @Test
    fun `get product returns null when no enabled providers`() {
        val registry = StoreProviderRegistry(emptyList())
        val service = PersonalShopperService(registry)

        assertNull(service.getProduct("p1"))
    }

    @Test
    fun `within budget returns true when no budget limit configured`() {
        val service = PersonalShopperService(StoreProviderRegistry(), ShoppingConfig(budgetLimit = null))
        val offer = buildOffer(price = 9999.99, currency = "USD")

        assertTrue(service.withinBudget(offer))
    }

    @Test
    fun `within budget returns true when price is at limit`() {
        val service = PersonalShopperService(StoreProviderRegistry(), ShoppingConfig(budgetLimit = 50.0, budgetCurrencyCode = "USD"))
        val offer = buildOffer(price = 50.0, currency = "USD")

        assertTrue(service.withinBudget(offer))
    }

    @Test
    fun `within budget returns false when price exceeds limit`() {
        val service = PersonalShopperService(StoreProviderRegistry(), ShoppingConfig(budgetLimit = 50.0, budgetCurrencyCode = "USD"))
        val offer = buildOffer(price = 50.01, currency = "USD")

        assertFalse(service.withinBudget(offer))
    }

    @Test
    fun `within budget returns false when currency does not match`() {
        val service = PersonalShopperService(StoreProviderRegistry(), ShoppingConfig(budgetLimit = 100.0, budgetCurrencyCode = "USD"))
        val offer = buildOffer(price = 50.0, currency = "EUR")

        assertFalse(service.withinBudget(offer))
    }

    @Test
    fun `meets seller threshold returns true when no min score configured`() {
        val service = PersonalShopperService(StoreProviderRegistry(), ShoppingConfig(minSellerScore = null))
        val seller = NormalizedSeller(sellerId = "s", name = "S", sellerScore = null, reviewCount = null)

        assertTrue(service.meetsSellerThreshold(seller))
    }

    @Test
    fun `meets seller threshold returns false when seller score is null and threshold set`() {
        val service = PersonalShopperService(StoreProviderRegistry(), ShoppingConfig(minSellerScore = 4.0))
        val seller = NormalizedSeller(sellerId = "s", name = "S", sellerScore = null, reviewCount = null)

        assertFalse(service.meetsSellerThreshold(seller))
    }

    @Test
    fun `meets seller threshold returns true when score meets minimum`() {
        val service = PersonalShopperService(StoreProviderRegistry(), ShoppingConfig(minSellerScore = 4.0))
        val seller = NormalizedSeller(sellerId = "s", name = "S", sellerScore = 4.0, reviewCount = null)

        assertTrue(service.meetsSellerThreshold(seller))
    }

    @Test
    fun `meets seller threshold returns false when score is below minimum`() {
        val service = PersonalShopperService(StoreProviderRegistry(), ShoppingConfig(minSellerScore = 4.0))
        val seller = NormalizedSeller(sellerId = "s", name = "S", sellerScore = 3.9, reviewCount = null)

        assertFalse(service.meetsSellerThreshold(seller))
    }

    @Test
    fun `search filters out offers exceeding budget`() {
        val cheapOffer = buildOffer(price = 20.0, currency = "USD", sellerId = "cheap")
        val expensiveOffer = buildOffer(price = 80.0, currency = "USD", sellerId = "expensive")
        val product =
            NormalizedProduct(productId = "p1", title = "Widget", offers = listOf(cheapOffer, expensiveOffer))
        val provider = FakeStoreProvider("shop", priority = 10, enabled = true, products = listOf(product))
        val registry = StoreProviderRegistry(listOf(provider))
        val service = PersonalShopperService(registry, ShoppingConfig(budgetLimit = 50.0))

        val result = service.searchProducts("widget")

        assertEquals(1, result.products.size)
        assertEquals(1, result.products.first().offers.size)
        assertEquals("cheap", result.products.first().offers.first().seller.sellerId)
    }

    @Test
    fun `search filters out offers below seller threshold`() {
        val goodSeller = buildOffer(price = 25.0, currency = "USD", sellerId = "good", sellerScore = 4.5)
        val badSeller = buildOffer(price = 15.0, currency = "USD", sellerId = "bad", sellerScore = 2.5)
        val product =
            NormalizedProduct(productId = "p1", title = "Widget", offers = listOf(goodSeller, badSeller))
        val provider = FakeStoreProvider("shop", priority = 10, enabled = true, products = listOf(product))
        val registry = StoreProviderRegistry(listOf(provider))
        val service = PersonalShopperService(registry, ShoppingConfig(minSellerScore = 4.0))

        val result = service.searchProducts("widget")

        assertEquals(1, result.products.size)
        assertEquals(1, result.products.first().offers.size)
        assertEquals("good", result.products.first().offers.first().seller.sellerId)
    }

    private fun buildOffer(
        price: Double,
        currency: String,
        sellerId: String = "s1",
        sellerScore: Double? = 4.5,
    ): NormalizedOffer =
        NormalizedOffer(
            price = price,
            currency = currency,
            seller = NormalizedSeller(sellerId = sellerId, name = "Seller", sellerScore = sellerScore, reviewCount = null),
            availability = OfferAvailability.IN_STOCK,
        )
}

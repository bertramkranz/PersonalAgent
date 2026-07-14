package com.personalagent.bertbot.shopping

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShoppingModelsTest {
    @Test
    fun `amazon raw product maps to normalized model preserving all fields`() {
        val raw =
            AmazonRawProduct(
                asin = "B001234567",
                title = "Test Widget",
                brand = "Acme",
                description = "A fine widget",
                imageUrl = "https://example.com/widget.jpg",
                offers =
                    listOf(
                        AmazonRawOffer(
                            price = 19.99,
                            currency = "USD",
                            sellerId = "seller-1",
                            sellerName = "Best Seller",
                            sellerScore = 4.7,
                            sellerReviewCount = 1200,
                            availabilityType = "IN_STOCK",
                            isPrime = true,
                        ),
                    ),
            )

        val normalized = raw.toNormalized()

        assertEquals("B001234567", normalized.productId)
        assertEquals("Test Widget", normalized.title)
        assertEquals("Acme", normalized.brand)
        assertEquals("A fine widget", normalized.description)
        assertEquals("https://example.com/widget.jpg", normalized.imageUrl)
        assertEquals(1, normalized.offers.size)

        val offer = normalized.offers.first()
        assertEquals(19.99, offer.price)
        assertEquals("USD", offer.currency)
        assertEquals("seller-1", offer.seller.sellerId)
        assertEquals("Best Seller", offer.seller.name)
        assertEquals(4.7, offer.seller.sellerScore)
        assertEquals(1200, offer.seller.reviewCount)
        assertEquals(OfferAvailability.IN_STOCK, offer.availability)
        assertEquals(true, offer.prime)
    }

    @Test
    fun `amazon raw offer maps IN_STOCK availability`() {
        val offer = buildRawOffer(availabilityType = "IN_STOCK")
        assertEquals(OfferAvailability.IN_STOCK, offer.toNormalized().availability)
    }

    @Test
    fun `amazon raw offer maps OUT_OF_STOCK availability`() {
        val offer = buildRawOffer(availabilityType = "OUT_OF_STOCK")
        assertEquals(OfferAvailability.OUT_OF_STOCK, offer.toNormalized().availability)
    }

    @Test
    fun `amazon raw offer maps LIMITED availability`() {
        val offer = buildRawOffer(availabilityType = "LIMITED")
        assertEquals(OfferAvailability.LIMITED, offer.toNormalized().availability)
    }

    @Test
    fun `amazon raw offer maps unknown availability string to UNKNOWN`() {
        val offer = buildRawOffer(availabilityType = "BACKORDER")
        assertEquals(OfferAvailability.UNKNOWN, offer.toNormalized().availability)
    }

    @Test
    fun `amazon raw offer preserves seller score and currency`() {
        val offer =
            buildRawOffer(
                price = 49.95,
                currency = "EUR",
                sellerScore = 3.8,
                sellerReviewCount = 250,
            )
        val normalized = offer.toNormalized()
        assertEquals(49.95, normalized.price)
        assertEquals("EUR", normalized.currency)
        assertEquals(3.8, normalized.seller.sellerScore)
        assertEquals(250, normalized.seller.reviewCount)
    }

    @Test
    fun `lowest price offer returns in-stock offer with smallest price`() {
        val product =
            NormalizedProduct(
                productId = "p1",
                title = "Item",
                offers =
                    listOf(
                        buildOffer(price = 30.0, availability = OfferAvailability.IN_STOCK),
                        buildOffer(price = 20.0, availability = OfferAvailability.OUT_OF_STOCK),
                        buildOffer(price = 25.0, availability = OfferAvailability.IN_STOCK),
                    ),
            )
        val lowest = product.lowestPriceOffer()
        assertEquals(25.0, lowest?.price)
    }

    @Test
    fun `lowest price offer returns null when no in-stock offers`() {
        val product =
            NormalizedProduct(
                productId = "p1",
                title = "Item",
                offers =
                    listOf(
                        buildOffer(price = 10.0, availability = OfferAvailability.OUT_OF_STOCK),
                    ),
            )
        assertNull(product.lowestPriceOffer())
    }

    @Test
    fun `normalized product with no offers has empty offers list`() {
        val raw = AmazonRawProduct(asin = "X", title = "Empty")
        val normalized = raw.toNormalized()
        assertEquals(emptyList(), normalized.offers)
    }

    private fun buildRawOffer(
        price: Double = 9.99,
        currency: String = "USD",
        sellerScore: Double? = 4.5,
        sellerReviewCount: Int? = 100,
        availabilityType: String = "IN_STOCK",
    ): AmazonRawOffer =
        AmazonRawOffer(
            price = price,
            currency = currency,
            sellerId = "s1",
            sellerName = "Seller One",
            sellerScore = sellerScore,
            sellerReviewCount = sellerReviewCount,
            availabilityType = availabilityType,
        )

    private fun buildOffer(
        price: Double,
        availability: OfferAvailability,
    ): NormalizedOffer =
        NormalizedOffer(
            price = price,
            currency = "USD",
            seller = NormalizedSeller(sellerId = "s", name = "S", sellerScore = 4.0, reviewCount = 10),
            availability = availability,
        )
}

package com.personalagent.bertbot.shopping

data class AmazonRawProduct(
    val asin: String,
    val title: String,
    val brand: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val offers: List<AmazonRawOffer> = emptyList(),
)

data class AmazonRawOffer(
    val price: Double,
    val currency: String = "USD",
    val sellerId: String,
    val sellerName: String,
    val sellerScore: Double? = null,
    val sellerReviewCount: Int? = null,
    val availabilityType: String = "IN_STOCK",
    val isPrime: Boolean = false,
    val conditionNote: String? = null,
)

class AmazonStoreProvider(
    override val priority: Int = 100,
    override val enabled: Boolean = true,
    private val catalog: List<AmazonRawProduct> = defaultAmazonCatalog(),
) : StoreProvider {
    override val providerId: String = PROVIDER_ID

    override fun searchProducts(
        query: String,
        maxResults: Int,
    ): List<NormalizedProduct> {
        if (query.isBlank()) return emptyList()
        val normalizedQuery = query.lowercase()
        return catalog
            .asSequence()
            .filter { product ->
                val searchableText = listOf(product.title, product.brand.orEmpty(), product.description.orEmpty()).joinToString(" ").lowercase()
                searchableText.contains(normalizedQuery)
            }.take(maxResults.coerceAtLeast(1))
            .map { product -> product.toNormalized() }
            .toList()
    }

    override fun getProduct(productId: String): NormalizedProduct? {
        return catalog.firstOrNull { product -> product.asin.equals(productId, ignoreCase = true) }?.toNormalized()
    }

    companion object {
        const val PROVIDER_ID = "amazon"
    }
}

private fun defaultAmazonCatalog(): List<AmazonRawProduct> =
    listOf(
        AmazonRawProduct(
            asin = "amz-laptop-pro-14",
            title = "Developer Pro Laptop 14",
            brand = "Nimbus",
            description = "Portable high-performance laptop for coding, AI workflows, and development tasks.",
            offers =
                listOf(
                    AmazonRawOffer(
                        price = 1499.0,
                        sellerId = "seller-nimbus-direct",
                        sellerName = "Nimbus Direct",
                        sellerScore = 0.95,
                        sellerReviewCount = 4281,
                        availabilityType = "IN_STOCK",
                        isPrime = true,
                    ),
                    AmazonRawOffer(
                        price = 1429.0,
                        sellerId = "seller-giga-electronics",
                        sellerName = "Giga Electronics",
                        sellerScore = 0.88,
                        sellerReviewCount = 1280,
                        availabilityType = "LIMITED",
                    ),
                ),
        ),
        AmazonRawProduct(
            asin = "amz-headphones-studio-x",
            title = "StudioX Noise Cancelling Headphones",
            brand = "StudioX",
            description = "Wireless headphones with ANC and long battery life for focused work.",
            offers =
                listOf(
                    AmazonRawOffer(
                        price = 249.0,
                        sellerId = "seller-studiox-official",
                        sellerName = "StudioX Official",
                        sellerScore = 0.93,
                        sellerReviewCount = 5400,
                        availabilityType = "IN_STOCK",
                        isPrime = true,
                    ),
                ),
        ),
        AmazonRawProduct(
            asin = "amz-monitor-ultra-32",
            title = "UltraView 32 4K Monitor",
            brand = "UltraView",
            description = "32-inch 4K IPS monitor suited for coding, design, and analytics dashboards.",
            offers =
                listOf(
                    AmazonRawOffer(
                        price = 599.0,
                        sellerId = "seller-ultraview-direct",
                        sellerName = "UltraView Direct",
                        sellerScore = 0.9,
                        sellerReviewCount = 2110,
                        availabilityType = "IN_STOCK",
                    ),
                ),
        ),
    )

internal fun AmazonRawProduct.toNormalized(): NormalizedProduct =
    NormalizedProduct(
        productId = asin,
        title = title,
        brand = brand,
        description = description,
        imageUrl = imageUrl,
        offers = offers.map { it.toNormalized() },
    )

internal fun AmazonRawOffer.toNormalized(): NormalizedOffer =
    NormalizedOffer(
        price = price,
        currency = currency,
        seller =
            NormalizedSeller(
                sellerId = sellerId,
                name = sellerName,
                sellerScore = sellerScore,
                reviewCount = sellerReviewCount,
            ),
        availability =
            when (availabilityType.uppercase()) {
                "IN_STOCK" -> OfferAvailability.IN_STOCK
                "OUT_OF_STOCK" -> OfferAvailability.OUT_OF_STOCK
                "LIMITED" -> OfferAvailability.LIMITED
                else -> OfferAvailability.UNKNOWN
            },
        prime = isPrime,
        conditionNote = conditionNote,
    )

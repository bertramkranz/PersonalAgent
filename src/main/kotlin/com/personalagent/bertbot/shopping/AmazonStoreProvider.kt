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
) : StoreProvider {
    override val providerId: String = PROVIDER_ID

    override fun searchProducts(
        query: String,
        maxResults: Int,
    ): List<NormalizedProduct> {
        // Amazon Product Advertising API integration placeholder
        return emptyList()
    }

    override fun getProduct(productId: String): NormalizedProduct? {
        // Amazon Product Advertising API integration placeholder
        return null
    }

    companion object {
        const val PROVIDER_ID = "amazon"
    }
}

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

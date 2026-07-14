package com.personalagent.bertbot.shopping

enum class OfferAvailability {
    IN_STOCK,
    OUT_OF_STOCK,
    LIMITED,
    UNKNOWN,
}

data class NormalizedSeller(
    val sellerId: String,
    val name: String,
    val sellerScore: Double?,
    val reviewCount: Int?,
)

data class NormalizedOffer(
    val price: Double,
    val currency: String,
    val seller: NormalizedSeller,
    val availability: OfferAvailability,
    val prime: Boolean = false,
    val conditionNote: String? = null,
)

data class NormalizedProduct(
    val productId: String,
    val title: String,
    val brand: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val offers: List<NormalizedOffer> = emptyList(),
) {
    fun lowestPriceOffer(): NormalizedOffer? =
        offers.filter { it.availability == OfferAvailability.IN_STOCK }.minByOrNull { it.price }
}

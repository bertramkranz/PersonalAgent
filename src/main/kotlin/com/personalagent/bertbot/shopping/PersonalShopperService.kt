package com.personalagent.bertbot.shopping

data class ShoppingConfig(
    val enabled: Boolean = false,
    val budgetCurrencyCode: String = "USD",
    val budgetLimit: Double? = null,
    val minSellerScore: Double? = null,
)

data class ProductSearchResult(
    val products: List<NormalizedProduct>,
    val providerId: String,
)

class PersonalShopperService(
    private val registry: StoreProviderRegistry,
    private val config: ShoppingConfig = ShoppingConfig(),
) {
    fun searchProducts(
        query: String,
        maxResults: Int = 10,
    ): ProductSearchResult {
        val provider =
            registry.highestPriorityEnabled()
                ?: return ProductSearchResult(emptyList(), "none")
        val products = provider.searchProducts(query, maxResults)
        val filtered = filterByConfig(products)
        return ProductSearchResult(filtered, provider.providerId)
    }

    fun getProduct(productId: String): NormalizedProduct? {
        val provider = registry.highestPriorityEnabled() ?: return null
        return provider.getProduct(productId)
    }

    fun withinBudget(offer: NormalizedOffer): Boolean {
        val limit = config.budgetLimit ?: return true
        return offer.price <= limit && offer.currency.uppercase() == config.budgetCurrencyCode.uppercase()
    }

    fun meetsSellerThreshold(seller: NormalizedSeller): Boolean {
        val minScore = config.minSellerScore ?: return true
        val score = seller.sellerScore ?: return false
        return score >= minScore
    }

    private fun filterByConfig(products: List<NormalizedProduct>): List<NormalizedProduct> {
        val hasFilters = config.budgetLimit != null || config.minSellerScore != null
        if (!hasFilters) return products
        return products.map { product ->
            product.copy(
                offers = product.offers.filter { offer -> withinBudget(offer) && meetsSellerThreshold(offer.seller) },
            )
        }
    }
}

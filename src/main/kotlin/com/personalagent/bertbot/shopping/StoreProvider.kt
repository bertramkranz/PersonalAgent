package com.personalagent.bertbot.shopping

interface StoreProvider {
    val providerId: String
    val priority: Int
    val enabled: Boolean

    fun searchProducts(
        query: String,
        maxResults: Int = 10,
    ): List<NormalizedProduct>

    fun getProduct(productId: String): NormalizedProduct?
}

class StoreProviderRegistry(
    private val providers: List<StoreProvider> = listOf(AmazonStoreProvider()),
) {
    fun highestPriorityEnabled(): StoreProvider? =
        providers.filter { it.enabled }.maxByOrNull { it.priority }

    fun allEnabled(): List<StoreProvider> =
        providers.filter { it.enabled }.sortedByDescending { it.priority }
}

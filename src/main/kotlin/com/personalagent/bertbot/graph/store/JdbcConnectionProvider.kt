package com.personalagent.bertbot.graph.store

import java.sql.Connection
import java.sql.DriverManager

internal fun interface JdbcConnectionProvider {
    fun open(): Connection
}

internal class DriverManagerJdbcConnectionProvider(
    private val jdbcUrl: String,
    private val username: String? = null,
    private val password: String? = null,
) : JdbcConnectionProvider {
    override fun open(): Connection =
        if (username != null) {
            DriverManager.getConnection(jdbcUrl, username, password)
        } else {
            DriverManager.getConnection(jdbcUrl)
        }
}

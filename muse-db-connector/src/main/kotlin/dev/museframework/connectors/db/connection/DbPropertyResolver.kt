package dev.museframework.connectors.db.connection

import java.util.Properties

internal class DbPropertyResolver(
    private val properties: Properties,
) {
    fun resolve(value: String): String =
        PLACEHOLDER.replace(value) { match ->
            find(match.groupValues[1]) ?: match.value
        }

    private fun find(name: String): String? {
        properties.getProperty(name)?.let { return it }
        val normalized = normalize(name)
        return properties.entries
            .firstOrNull { (key, _) -> normalize(key.toString()) == normalized }
            ?.value
            ?.toString()
    }

    private fun normalize(name: String): String = name.lowercase().filter(Char::isLetterOrDigit)

    private companion object {
        val PLACEHOLDER: Regex = Regex("""\$\{([^}]+)}""")
    }
}

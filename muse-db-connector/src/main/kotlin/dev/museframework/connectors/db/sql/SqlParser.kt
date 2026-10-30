package dev.museframework.connectors.db.sql

import dev.museframework.connectors.db.DbParameterException

internal data class ParsedSql(
    val jdbcSql: String,
    val parameterNames: List<String>,
)

/**
 * Rewrites colon-named parameters to JDBC `?` placeholders.
 *
 * Ordinary quote scanning follows Spring `NamedParameterUtils` /
 * Mule Database Connector 1.16 `SimpleQueryTemplateParser`: a `'` closes at
 * the next `'`, with no MySQL-style backslash escapes. Outside quotes,
 * `\:` becomes a literal `:`. PostgreSQL `::` casts, dollar-quotes, and
 * `E'...'` escape strings are Muse extensions beyond the Mule baseline.
 */
internal object NamedParameterSql {
    fun parse(sql: String): ParsedSql {
        val output = StringBuilder(sql.length)
        val names = mutableListOf<String>()
        var state = SqlState.NORMAL
        var dollarDelimiter: String? = null
        var index = 0

        while (index < sql.length) {
            val current = sql[index]
            val next = sql.getOrNull(index + 1)

            when (state) {
                SqlState.NORMAL -> {
                    val delimiter = if (current == '$') dollarDelimiterAt(sql, index) else null
                    when {
                        delimiter != null -> {
                            output.append(delimiter)
                            dollarDelimiter = delimiter
                            state = SqlState.DOLLAR_QUOTE
                            index += delimiter.length
                        }

                        isEscapeStringPrefix(sql, index) -> {
                            output.append(sql, index, index + 2)
                            state = SqlState.ESCAPE_STRING
                            index += 2
                        }

                        current == '\\' && next == ':' -> {
                            output.append(':')
                            index += 2
                        }

                        current == '\'' -> {
                            output.append(current)
                            state = SqlState.SINGLE_QUOTE
                            index++
                        }

                        current == '"' -> {
                            output.append(current)
                            state = SqlState.DOUBLE_QUOTE
                            index++
                        }

                        current == '`' -> {
                            output.append(current)
                            state = SqlState.BACKTICK_QUOTE
                            index++
                        }

                        current == '-' && next == '-' -> {
                            output.append("--")
                            state = SqlState.LINE_COMMENT
                            index += 2
                        }

                        current == '/' && next == '*' -> {
                            output.append("/*")
                            state = SqlState.BLOCK_COMMENT
                            index += 2
                        }

                        current == ':' && next != ':' && sql.getOrNull(index - 1) != ':' && next?.isParameterStart() == true -> {
                            var end = index + 2
                            while (end < sql.length && sql[end].isParameterPart()) end++
                            names += sql.substring(index + 1, end)
                            output.append('?')
                            index = end
                        }

                        else -> {
                            output.append(current)
                            index++
                        }
                    }
                }

                SqlState.SINGLE_QUOTE -> {
                    output.append(current)
                    when {
                        current == '\'' && next == '\'' -> {
                            output.append(next)
                            index += 2
                        }

                        current == '\'' -> {
                            state = SqlState.NORMAL
                            index++
                        }

                        else -> index++
                    }
                }

                SqlState.ESCAPE_STRING -> {
                    output.append(current)
                    when {
                        current == '\\' && next != null -> {
                            output.append(next)
                            index += 2
                        }

                        current == '\'' && next == '\'' -> {
                            output.append(next)
                            index += 2
                        }

                        current == '\'' -> {
                            state = SqlState.NORMAL
                            index++
                        }

                        else -> index++
                    }
                }

                SqlState.DOUBLE_QUOTE -> {
                    output.append(current)
                    if (current == '"' && next == '"') {
                        output.append(next)
                        index += 2
                    } else {
                        if (current == '"') state = SqlState.NORMAL
                        index++
                    }
                }

                SqlState.BACKTICK_QUOTE -> {
                    output.append(current)
                    if (current == '`' && next == '`') {
                        output.append(next)
                        index += 2
                    } else {
                        if (current == '`') state = SqlState.NORMAL
                        index++
                    }
                }

                SqlState.LINE_COMMENT -> {
                    output.append(current)
                    if (current == '\n' || current == '\r') state = SqlState.NORMAL
                    index++
                }

                SqlState.BLOCK_COMMENT -> {
                    output.append(current)
                    if (current == '*' && next == '/') {
                        output.append(next)
                        state = SqlState.NORMAL
                        index += 2
                    } else {
                        index++
                    }
                }

                SqlState.DOLLAR_QUOTE -> {
                    val delimiter = checkNotNull(dollarDelimiter)
                    if (sql.startsWith(delimiter, index)) {
                        output.append(delimiter)
                        state = SqlState.NORMAL
                        dollarDelimiter = null
                        index += delimiter.length
                    } else {
                        output.append(current)
                        index++
                    }
                }
            }
        }

        if (state == SqlState.BLOCK_COMMENT) {
            throw DbParameterException("SQL contains an unterminated block comment")
        }
        if (state == SqlState.DOLLAR_QUOTE) {
            throw DbParameterException("SQL contains an unterminated dollar-quoted string")
        }
        return ParsedSql(output.toString(), names)
    }
}

/**
 * Splits an execute-script body the way Mule Database Connector 1.16 does:
 * `String.split(";[\\r\\n]+")`, then trim and drop empties.
 *
 * Semicolons not followed by a newline stay inside the statement, including
 * those inside string literals on the same line. A `;\n` inside a procedure
 * body therefore splits, matching Mule rather than a SQL-aware parser.
 */
internal object SqlScriptSplitter {
    private val statementSeparator = Regex(";[\\r\\n]+")

    fun split(script: String): List<String> =
        script
            .trim()
            .split(statementSeparator)
            .map(String::trim)
            .filter(String::isNotEmpty)
}

private enum class SqlState {
    NORMAL,
    SINGLE_QUOTE,
    ESCAPE_STRING,
    DOUBLE_QUOTE,
    BACKTICK_QUOTE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    DOLLAR_QUOTE,
}

private fun Char.isParameterStart(): Boolean = this == '_' || isLetter()

private fun Char.isParameterPart(): Boolean = this == '_' || isLetterOrDigit()

private fun isEscapeStringPrefix(
    sql: String,
    index: Int,
): Boolean {
    if (index + 1 >= sql.length) return false
    if (sql[index] != 'E' && sql[index] != 'e') return false
    if (sql[index + 1] != '\'') return false
    if (index > 0) {
        val previous = sql[index - 1]
        if (previous.isLetterOrDigit() || previous == '_') return false
    }
    return true
}

private fun dollarDelimiterAt(
    sql: String,
    index: Int,
): String? {
    val end = sql.indexOf('$', index + 1)
    if (end < 0) return null
    val tag = sql.substring(index + 1, end)
    return if (tag.all { it == '_' || it.isLetterOrDigit() }) sql.substring(index, end + 1) else null
}

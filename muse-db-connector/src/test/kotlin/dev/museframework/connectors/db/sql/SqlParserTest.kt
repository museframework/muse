package dev.museframework.connectors.db.sql

import dev.museframework.connectors.db.DbParameterException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlParserTest {
    @Test
    fun `named parameters ignore literals comments casts and dollar quotes`() {
        val parsed =
            NamedParameterSql.parse(
                """
                SELECT :id, ':literal', value::text, "quoted:column", `legacy:name`
                FROM sample -- :line_comment
                WHERE note = ${'$'}tag${'$'}:dollar_literal${'$'}tag${'$'}
                  AND id = :id /* :block_comment */
                """.trimIndent(),
            )

        assertEquals(listOf("id", "id"), parsed.parameterNames)
        assertEquals(2, parsed.jdbcSql.count { it == '?' })
        assertEquals(1, Regex("value::text").findAll(parsed.jdbcSql).count())
        assertEquals(1, Regex(":dollar_literal").findAll(parsed.jdbcSql).count())
    }

    @Test
    fun `escaped colon outside strings is not a named parameter`() {
        val parsed = NamedParameterSql.parse("""SELECT price\:\:float8, :n FROM product""")

        assertEquals(listOf("n"), parsed.parameterNames)
        assertEquals("""SELECT price::float8, ? FROM product""", parsed.jdbcSql)
    }

    @Test
    fun `script splitter follows mule semicolon-newline boundaries`() {
        val script =
            """
            INSERT INTO sample(value) VALUES ('one;two');
            -- comment; stays with the next command
            CREATE PROCEDURE p() LANGUAGE plpgsql AS ${'$'}proc${'$'}
            BEGIN
              PERFORM 1;
            END
            ${'$'}proc${'$'};
            UPDATE sample SET value = 'done';
            """.trimIndent()

        val statements = SqlScriptSplitter.split(script)

        // Same separator as Mule Database Connector 1.16 BulkQueryFactory: ;[\r\n]+
        assertEquals(4, statements.size)
        assertEquals("INSERT INTO sample(value) VALUES ('one;two')", statements[0])
        assertEquals(true, statements[1].contains("PERFORM 1"))
        assertEquals(false, statements[1].contains("END"))
        assertEquals(true, statements[2].contains("END"))
        assertEquals("UPDATE sample SET value = 'done';", statements[3])
    }

    @Test
    fun `unterminated block comments fail before JDBC execution`() {
        assertFailsWith<DbParameterException> {
            NamedParameterSql.parse("SELECT 1 /* unfinished")
        }
    }

    @Test
    fun `ordinary string backslash does not hide following named parameter`() {
        val parsed = NamedParameterSql.parse("""SELECT 'tail\' AS literal, :n AS n""")

        assertEquals(listOf("n"), parsed.parameterNames)
        assertEquals("""SELECT 'tail\' AS literal, ? AS n""", parsed.jdbcSql)
    }

    @Test
    fun `mysql style backslash quote does not bind following named parameter like mule`() {
        val parsed = NamedParameterSql.parse("""SELECT 'it\'s', :n""")

        assertEquals(emptyList(), parsed.parameterNames)
        assertEquals("""SELECT 'it\'s', :n""", parsed.jdbcSql)
    }

    @Test
    fun `escape string prefix still treats backslash as escape`() {
        val parsed = NamedParameterSql.parse("""SELECT E'it\'s', :n""")

        assertEquals(listOf("n"), parsed.parameterNames)
        assertEquals("""SELECT E'it\'s', ?""", parsed.jdbcSql)
    }

    @Test
    fun `script splitter keeps ordinary backslash strings intact across statements`() {
        val statements =
            SqlScriptSplitter.split(
                """
                INSERT INTO t(v) VALUES ('tail\');
                SELECT :n;
                """.trimIndent(),
            )

        assertEquals(listOf("INSERT INTO t(v) VALUES ('tail\\')", "SELECT :n;"), statements)
    }

    @Test
    fun `script splitter follows mule boundaries for mysql style escaped quotes`() {
        val statements =
            SqlScriptSplitter.split(
                "INSERT INTO t(v) VALUES ('it\\'s');\nUPDATE t SET n=1;",
            )

        assertEquals(
            listOf("INSERT INTO t(v) VALUES ('it\\'s')", "UPDATE t SET n=1;"),
            statements,
        )
    }
}

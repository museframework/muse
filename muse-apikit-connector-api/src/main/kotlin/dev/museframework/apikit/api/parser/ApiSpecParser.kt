package dev.museframework.apikit.api.parser

import dev.museframework.apikit.api.model.ApiSpecification

interface ApiSpecParser {
    fun parse(content: String): ApiSpecification

    fun parseFile(path: String): ApiSpecification
}

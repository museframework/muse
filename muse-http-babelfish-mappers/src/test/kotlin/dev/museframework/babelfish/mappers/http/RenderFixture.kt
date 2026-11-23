package dev.museframework.babelfish.mappers.http

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec

internal fun renderFixture(code: CodeBlock): String =
    FileSpec.builder("com.test", "Fixture")
        .addFunction(FunSpec.builder("fixture").addCode("%L\n", code).build())
        .build()
        .toString()

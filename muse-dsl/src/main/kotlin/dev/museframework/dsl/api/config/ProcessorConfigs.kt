package dev.museframework.dsl.api.config

import dev.museframework.dsl.api.model.component.Processor

data class RaiseErrorConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val type: String,
    val description: String,
) : Processor

data class RemoveVariableConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val variableName: String,
) : Processor

data class SetVariableConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val variableName: String,
    val value: String,
    val mimeType: String? = null,
) : Processor

data class SetPayloadConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val value: String,
    val mimeType: String? = null,
    val encoding: String? = null,
) : Processor

typealias CustomCodeFunction = suspend (Any?) -> Any?

data class CustomCodeConfig(
    override val docName: String,
    override val docId: String,
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val code: CustomCodeFunction,
    val handleErrors: Boolean = true,
) : Processor

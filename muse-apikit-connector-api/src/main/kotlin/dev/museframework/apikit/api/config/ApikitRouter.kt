package dev.museframework.apikit.api.config

import dev.museframework.dsl.api.model.component.Router
import java.util.UUID

data class ApikitRouter(
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val configRef: ApikitRef,
) : Router

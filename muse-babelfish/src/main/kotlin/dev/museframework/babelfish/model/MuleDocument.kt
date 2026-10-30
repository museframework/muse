package dev.museframework.babelfish.model

import java.nio.file.Path

data class MuleDocument(
    val sourceFile: Path,
    val globalConfigs: List<RawConfig>,
    val flows: List<RawFlow>,
    val subFlows: List<RawFlow>
)

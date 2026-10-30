package dev.museframework.dsl.api.util

object CallSiteCapture {
    data class CallSite(
        val sourceFile: String?,
        val sourceLine: Int?,
        val sourceClass: String?,
    )

    fun capture(internalPackagePrefix: String = "dev.museframework"): CallSite {
        val frame =
            StackWalker
                .getInstance()
                .walk { frames ->
                    frames
                        .dropWhile { it.className == CallSiteCapture::class.java.name }
                        .filter { !it.className.startsWith(internalPackagePrefix) }
                        .findFirst()
                        .orElse(null)
                }
        return CallSite(
            sourceFile = frame?.fileName,
            sourceLine = frame?.lineNumber,
            sourceClass = frame?.className,
        )
    }
}

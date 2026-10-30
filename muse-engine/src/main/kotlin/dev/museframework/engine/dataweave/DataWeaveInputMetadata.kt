package dev.museframework.engine.dataweave

import org.mule.weave.v2.parser.module.MimeType
import org.mule.weave.v2.runtime.BindingValue
import scala.collection.JavaConverters
import java.nio.charset.Charset

internal data class DataWeaveInputMetadata(
    val mimeType: String,
    val readerProperties: scala.collection.immutable.Map<String, Any>,
    val charset: Charset?,
    val streaming: Boolean,
) {
    fun binding(value: Any?, defaultCharset: Charset): BindingValue =
        BindingValue.apply(value, mimeType, readerProperties, charset ?: defaultCharset)

    companion object {
        private val cache = object : LinkedHashMap<String, DataWeaveInputMetadata>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DataWeaveInputMetadata>): Boolean =
                size > 128
        }

        @Synchronized
        fun parse(contentType: String): DataWeaveInputMetadata = cache.getOrPut(contentType) {
            val mime = MimeType.fromSimpleString(contentType)
            var options: scala.collection.immutable.Map<String, Any> = scala.collection.immutable.HashMap()
            var charset: Charset? = null
            var streaming = false
            JavaConverters.mapAsJavaMap(mime.parameters()).forEach { (name, value) ->
                val decoded = value.removeSurrounding("\"")
                if (name.equals("charset", ignoreCase = true)) charset = Charset.forName(decoded)
                else {
                    options = options.updated(name, decoded)
                    if (name == "streaming") streaming = decoded.toBooleanStrictOrNull() == true
                }
            }
            DataWeaveInputMetadata(mime.toStringWithoutParameters(), options, charset, streaming)
        }
    }
}

package dev.museframework.babelfish.parser

import dev.museframework.babelfish.model.SourceLocation
import org.jdom2.Element
import org.jdom2.input.SAXBuilder
import org.jdom2.input.sax.SAXHandler
import org.jdom2.input.sax.SAXHandlerFactory
import org.xml.sax.Locator
import org.xml.sax.Attributes
import java.io.File

internal class LocationAwareSaxBuilder {

    val locations: MutableMap<Int, SourceLocation> = mutableMapOf()

    fun build(file: File): org.jdom2.Document {
        val builder = SAXBuilder()
        builder.saxHandlerFactory = LocationSaxHandlerFactory(locations)

        builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        builder.setFeature("http://xml.org/sax/features/external-general-entities", false)
        builder.setFeature("http://xml.org/sax/features/external-parameter-entities", false)

        return builder.build(file)
    }

    private class LocationSaxHandlerFactory(
        private val locations: MutableMap<Int, SourceLocation>
    ) : SAXHandlerFactory {
        override fun createSAXHandler(factory: org.jdom2.JDOMFactory): SAXHandler {
            return LocationSaxHandler(locations, factory)
        }
    }

    private class LocationSaxHandler(
        private val locations: MutableMap<Int, SourceLocation>,
        factory: org.jdom2.JDOMFactory
    ) : SAXHandler(factory) {

        private var locator: Locator? = null
        private val elementStack = mutableListOf<Element>()

        override fun setDocumentLocator(locator: Locator) {
            this.locator = locator
            super.setDocumentLocator(locator)
        }

        override fun startElement(uri: String, localName: String, qName: String, atts: Attributes) {
            val line = locator?.lineNumber ?: -1
            val col = locator?.columnNumber ?: -1

            super.startElement(uri, localName, qName, atts)

            val currentElement = this.currentElement
            if (currentElement != null) {
                elementStack.add(currentElement)
                locations[System.identityHashCode(currentElement)] = SourceLocation(line, col)
            }
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            super.endElement(uri, localName, qName)
            if (elementStack.isNotEmpty()) {
                elementStack.removeAt(elementStack.size - 1)
            }
        }
    }
}

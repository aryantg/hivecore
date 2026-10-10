package com.hivecore.node.docs

import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * SAX parsing shared by the OOXML extractors. Namespace awareness is OFF on purpose: handlers match on the
 * qualified names as written in the file (`w:p`, `a:t`, `p:sldId`) and read the `r:id` attribute by that name,
 * which is what every Office product writes. DTDs and external entities are disabled where the parser supports it.
 */
internal object SaxUtil {
    private val factory: SAXParserFactory by lazy {
        SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            val features = mapOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false
            )
            for ((f, v) in features) {
                try { setFeature(f, v) } catch (_: Exception) { }
            }
        }
    }

    fun parse(xml: ByteArray, handler: DefaultHandler) {
        val parser = factory.newSAXParser()
        try { parser.xmlReader.setProperty("http://javax.xml.XMLConstants/property/accessExternalDTD", "") } catch (_: Exception) { }
        parser.parse(InputSource(ByteArrayInputStream(xml)), handler)
    }
}

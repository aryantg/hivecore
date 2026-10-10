package com.hivecore.node.docs

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File

/**
 * One [TextUnit] per slide, in presentation order: `ppt/presentation.xml` lists `<p:sldId r:id="…"/>` in the order
 * the deck is shown, `ppt/_rels/presentation.xml.rels` maps each id to `slides/slideN.xml`, and the slide XML
 * yields every `<a:t>` in document order with an `<a:p>` boundary (and `<a:br/>`) as a newline. The locator is
 * "slide n" with n the 1-based position in the deck (not the number in the file name); empty slides keep their
 * number but are not returned.
 */
object PptxExtractor {
    const val PRESENTATION_XML = "ppt/presentation.xml"
    const val PRESENTATION_RELS = "ppt/_rels/presentation.xml.rels"

    fun extract(file: File): List<TextUnit> {
        val pres = DocZip.entry(file, PRESENTATION_XML) ?: throw IllegalArgumentException("${file.name}: no $PRESENTATION_XML (not a .pptx)")
        val rels = DocZip.entry(file, PRESENTATION_RELS) ?: throw IllegalArgumentException("${file.name}: no $PRESENTATION_RELS")
        val targets = relationships(rels)
        val out = ArrayList<TextUnit>()
        slideIds(pres).forEachIndexed { i, rid ->
            val target = targets[rid] ?: return@forEachIndexed
            val path = resolve(target)
            val xml = DocZip.entry(file, path) ?: return@forEachIndexed
            val text = slideText(xml)
            if (text.isNotEmpty()) out.add(TextUnit("slide ${i + 1}", text))
        }
        return out
    }

    /** `r:id` values of `<p:sldId>` in document order. */
    fun slideIds(presentationXml: ByteArray): List<String> {
        val ids = ArrayList<String>()
        SaxUtil.parse(presentationXml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                if (qName == "p:sldId") attributes.getValue("r:id")?.let { ids.add(it) }
            }
        })
        return ids
    }

    /** Relationship Id → Target from a `.rels` part. */
    fun relationships(relsXml: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        SaxUtil.parse(relsXml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                if (qName == "Relationship") {
                    val id = attributes.getValue("Id") ?: return
                    val target = attributes.getValue("Target") ?: return
                    map[id] = target
                }
            }
        })
        return map
    }

    /** All `<a:t>` text of one slide part, paragraphs separated by newlines. */
    fun slideText(slideXml: ByteArray): String {
        val buf = StringBuilder()
        var inText = false
        SaxUtil.parse(slideXml, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes?) {
                when (qName) {
                    "a:t" -> inText = true
                    "a:br" -> buf.append('\n')
                    "a:tab" -> buf.append('\t')
                }
            }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                when (qName) {
                    "a:t" -> inText = false
                    "a:p" -> buf.append('\n')
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText) buf.append(ch, start, length)
            }
        })
        return Normalize.block(buf.toString())
    }

    /** Relationship targets are relative to `ppt/` unless they start with "/". */
    internal fun resolve(target: String): String =
        if (target.startsWith("/")) target.trimStart('/') else "ppt/" + target.removePrefix("./")
}

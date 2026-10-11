package com.hivecore.engine.deck

import com.hivecore.core.Hashing
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [Deck] as a PresentationML package (.pptx) with nothing but the JDK's zip and string building: a 16:9
 * blank layout on one master and one theme, every slide drawn from text boxes, rectangles and pictures. Charts are
 * plain shapes (bars, labels, a baseline) so they open and stay editable everywhere PowerPoint, Keynote, Google
 * Slides or LibreOffice read a .pptx, without a chart part or an embedded workbook. The output is byte-identical for
 * the same deck and the same image bytes (fixed zip timestamps, stored entries), so the file hash is a fingerprint
 * of the content.
 */
object PptxWriter {
    /** 16:9 slide in EMU (13.333 × 7.5 in). */
    const val W = 12_192_000L
    const val H = 6_858_000L
    private const val IN = 914_400L

    // The deck's look: slate background, amber accent, light text (the app's own palette).
    private const val BG = "1A1D22"
    private const val BG_PANEL = "282D34"
    private const val ACCENT = "FFB956"
    private const val ACCENT_DEEP = "8A5A00"
    private const val TEXT = "E3E5E9"
    private const val MUTED = "C0C7D1"
    private const val UP = "7BD389"
    private const val DOWN = "FFB68E"
    private const val FONT = "Calibri"

    /** Writes [deck] to [out] (parent directories created) and returns the file's sha256. */
    fun write(deck: Deck, out: File): String {
        val bytes = bytes(deck)
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        return Hashing.sha256Hex(bytes)
    }

    /** The package bytes. */
    fun bytes(deck: Deck): ByteArray {
        require(deck.slides.isNotEmpty()) { "a deck needs at least one slide" }
        val parts = LinkedHashMap<String, ByteArray>()
        val images = LinkedHashMap<Int, ByteArray>()        // slide index → png
        deck.slides.forEachIndexed { i, s ->
            val f = s.image?.let { File(it) }
            if (f != null && f.isFile) images[i] = f.readBytes()
        }
        parts["[Content_Types].xml"] = contentTypes(deck.slides.size, images.keys).toByteArray()
        parts["_rels/.rels"] = ROOT_RELS.toByteArray()
        parts["docProps/core.xml"] = coreProps(deck.title).toByteArray()
        parts["docProps/app.xml"] = appProps(deck.slides.size).toByteArray()
        parts["ppt/presentation.xml"] = presentation(deck.slides.size).toByteArray()
        parts["ppt/_rels/presentation.xml.rels"] = presentationRels(deck.slides.size).toByteArray()
        parts["ppt/slideMasters/slideMaster1.xml"] = SLIDE_MASTER.toByteArray()
        parts["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = MASTER_RELS.toByteArray()
        parts["ppt/slideLayouts/slideLayout1.xml"] = SLIDE_LAYOUT.toByteArray()
        parts["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = LAYOUT_RELS.toByteArray()
        parts["ppt/theme/theme1.xml"] = THEME.toByteArray()
        deck.slides.forEachIndexed { i, s ->
            val n = i + 1
            val hasImage = i in images
            parts["ppt/slides/slide$n.xml"] = slide(s, i, deck.slides.size, hasImage).toByteArray()
            parts["ppt/slides/_rels/slide$n.xml.rels"] = slideRels(if (hasImage) "image$n.png" else null).toByteArray()
            images[i]?.let { parts["ppt/media/image$n.png"] = it }
        }
        return zip(parts)
    }

    // ------------------------------------------------------------------ slides

    private fun slide(s: Slide, index: Int, count: Int, hasImage: Boolean): String {
        val ids = Ids()
        val body = StringBuilder()
        when (s.kind) {
            SlideKind.TITLE -> {
                body.append(rect(ids.next(), "Accent", x = IN * 0.9, y = IN * 3.05, cx = IN * 1.2, cy = IN * 0.08, fill = ACCENT))
                body.append(text(ids.next(), "Title", s.title, x = IN * 0.9, y = IN * 1.6, cx = W - IN * 1.8, cy = IN * 1.4, size = 44, bold = true, color = TEXT))
                if (s.bullets.isNotEmpty())
                    body.append(text(ids.next(), "Subtitle", s.bullets.joinToString("\n"), x = IN * 0.9, y = IN * 3.3, cx = W - IN * 1.8, cy = IN * 1.6, size = 22, color = MUTED))
            }
            SlideKind.BULLETS -> {
                body.append(heading(ids, s.title))
                body.append(bullets(ids.next(), s.bullets, x = IN * 0.9, y = IN * 1.7, cx = W - IN * 1.8, cy = H - IN * 2.6, size = 24))
            }
            SlideKind.CHART -> {
                body.append(heading(ids, s.title))
                s.chart?.let { body.append(chart(ids, it, x = IN * 0.9, y = IN * 1.7, cx = W - IN * 1.8, cy = IN * 4.3)) }
                s.caption?.let { body.append(text(ids.next(), "Caption", it, x = IN * 0.9, y = H - IN * 1.05, cx = W - IN * 1.8, cy = IN * 0.6, size = 18, color = ACCENT)) }
            }
            SlideKind.IMAGE -> {
                body.append(heading(ids, s.title))
                val side = IN * 4.6
                val px = IN * 0.9; val py = IN * 1.6
                if (hasImage) body.append(picture(ids.next(), x = px, y = py, cx = side, cy = side))
                else body.append(rect(ids.next(), "Placeholder", x = px, y = py, cx = side, cy = side, fill = BG_PANEL))
                val tx = px + side + IN * 0.5
                if (s.bullets.isNotEmpty()) body.append(bullets(ids.next(), s.bullets, x = tx, y = py, cx = W - tx - IN * 0.9, cy = side, size = 22))
                s.caption?.let { body.append(text(ids.next(), "Caption", it, x = px, y = py + side + IN * 0.15, cx = W - IN * 1.8, cy = IN * 0.5, size = 14, color = MUTED)) }
            }
        }
        body.append(text(ids.next(), "Footer", "${index + 1} / $count", x = W - IN * 1.9, y = H - IN * 0.55, cx = IN * 1.0, cy = IN * 0.4, size = 11, color = MUTED, align = "r"))
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld $NS><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="$BG"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree>$TREE_HEAD$body</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }

    private fun heading(ids: Ids, title: String): String =
        text(ids.next(), "Title", title, x = IN * 0.9, y = IN * 0.55, cx = W - IN * 1.8, cy = IN * 1.0, size = 32, bold = true, color = TEXT) +
            rect(ids.next(), "Rule", x = IN * 0.9, y = IN * 1.5, cx = IN * 0.9, cy = IN * 0.06, fill = ACCENT)

    /**
     * Bars with the figure above each, the category below, the change from the previous bar in green or orange, and a
     * baseline; the tallest bar fills 78 % of the plot.
     */
    private fun chart(ids: Ids, c: Chart, x: Double, y: Double, cx: Double, cy: Double): String {
        val sb = StringBuilder()
        val n = c.categories.size
        if (n == 0) return ""
        val max = c.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val labelBand = IN * 0.55
        val plotTop = y + labelBand
        val baseline = y + cy - IN * 0.6
        val plotH = baseline - plotTop
        val slot = cx / n
        val barW = slot * 0.56
        for (i in 0 until n) {
            val v = c.values[i].coerceAtLeast(0.0)
            val h = (plotH * 0.78 * v / max).coerceAtLeast(IN * 0.04)
            val bx = x + slot * i + (slot - barW) / 2
            val by = baseline - h
            sb.append(rect(ids.next(), "Bar ${i + 1}", x = bx, y = by, cx = barW, cy = h, fill = if (i == n - 1) ACCENT else ACCENT_DEEP))
            val label = c.labels.getOrNull(i) ?: DeckFormat.compact(c.values[i], false)
            sb.append(text(ids.next(), "Value ${i + 1}", label, x = bx - slot * 0.22, y = by - IN * 0.5, cx = barW + slot * 0.44, cy = IN * 0.45, size = 18, bold = true, color = TEXT, align = "ctr"))
            c.deltas.getOrNull(i)?.let { d ->
                sb.append(text(ids.next(), "Delta ${i + 1}", d, x = bx - slot * 0.22, y = by - IN * 0.9, cx = barW + slot * 0.44, cy = IN * 0.4, size = 14, bold = true,
                    color = if (d.startsWith("-")) DOWN else UP, align = "ctr"))
            }
            sb.append(text(ids.next(), "Category ${i + 1}", c.categories[i], x = bx - slot * 0.22, y = baseline + IN * 0.08, cx = barW + slot * 0.44, cy = IN * 0.45, size = 16, color = MUTED, align = "ctr"))
        }
        sb.append(rect(ids.next(), "Baseline", x = x, y = baseline, cx = cx, cy = IN * 0.02, fill = MUTED))
        return sb.toString()
    }

    // ------------------------------------------------------------------ shapes

    private class Ids { private var n = 1; fun next(): Int = ++n }

    private fun xfrm(x: Double, y: Double, cx: Double, cy: Double) =
        """<a:xfrm><a:off x="${x.toLong()}" y="${y.toLong()}"/><a:ext cx="${cx.toLong()}" cy="${cy.toLong()}"/></a:xfrm>"""

    private fun rect(id: Int, name: String, x: Double, y: Double, cx: Double, cy: Double, fill: String): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${esc(name)}"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(x, y, cx, cy)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="$fill"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr rtlCol="0" anchor="ctr"/><a:lstStyle/><a:p><a:pPr algn="ctr"/><a:endParaRPr lang="en-US"/></a:p></p:txBody></p:sp>"""

    private fun text(id: Int, name: String, s: String, x: Double, y: Double, cx: Double, cy: Double, size: Int, color: String,
                     bold: Boolean = false, align: String = "l"): String {
        val paras = s.split('\n').joinToString("") { line -> """<a:p><a:pPr algn="$align"/>${run(line, size, bold, color)}</a:p>""" }
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${esc(name)}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(x, y, cx, cy)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" rtlCol="0" anchor="t"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paras</p:txBody></p:sp>"""
    }

    private fun bullets(id: Int, items: List<String>, x: Double, y: Double, cx: Double, cy: Double, size: Int): String {
        val paras = items.joinToString("") { b ->
            """<a:p><a:pPr marL="342900" indent="-342900"><a:spcBef><a:spcPts val="1200"/></a:spcBef><a:buClr><a:srgbClr val="$ACCENT"/></a:buClr><a:buFont typeface="Arial"/><a:buChar char="•"/></a:pPr>${run(b, size, false, TEXT)}</a:p>"""
        }.ifEmpty { """<a:p><a:endParaRPr lang="en-US"/></a:p>""" }
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Bullets"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr>${xfrm(x, y, cx, cy)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" rtlCol="0" anchor="t"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paras</p:txBody></p:sp>"""
    }

    private fun run(s: String, size: Int, bold: Boolean, color: String): String =
        """<a:r><a:rPr lang="en-US" sz="${size * 100}"${if (bold) """ b="1"""" else ""} dirty="0"><a:solidFill><a:srgbClr val="$color"/></a:solidFill><a:latin typeface="$FONT"/></a:rPr><a:t>${esc(s)}</a:t></a:r>"""

    private fun picture(id: Int, x: Double, y: Double, cx: Double, cy: Double): String =
        """<p:pic><p:nvPicPr><p:cNvPr id="$id" name="Picture"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>${xfrm(x, y, cx, cy)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""

    internal fun esc(s: String): String = buildString(s.length + 8) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;")
            '\t' -> append(' ')
            else -> if (ch.code >= 0x20 || ch == '\n') append(ch)
        }
    }

    // ------------------------------------------------------------------ package parts

    private const val NS = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
    private const val TREE_HEAD = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>"""
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val OD = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun contentTypes(slides: Int, imageSlides: Set<Int>): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        sb.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        sb.append("""<Default Extension="xml" ContentType="application/xml"/>""")
        if (imageSlides.isNotEmpty()) sb.append("""<Default Extension="png" ContentType="image/png"/>""")
        sb.append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        sb.append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
        sb.append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
        sb.append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
        for (n in 1..slides) sb.append("""<Override PartName="/ppt/slides/slide$n.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
        sb.append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        sb.append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
        sb.append("</Types>")
        return sb.toString()
    }

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$OD/officeDocument" Target="ppt/presentation.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="$OD/extended-properties" Target="docProps/app.xml"/></Relationships>"""

    private fun coreProps(title: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${esc(title)}</dc:title><dc:creator>HiveCore</dc:creator></cp:coreProperties>"""

    private fun appProps(slides: Int) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><Application>HiveCore</Application><Slides>$slides</Slides></Properties>"""

    private fun presentation(slides: Int): String {
        val ids = (1..slides).joinToString("") { n -> """<p:sldId id="${255 + n}" r:id="rId${1 + n}"/>""" }
        val lvls = (1..9).joinToString("") { l -> """<a:lvl${l}pPr marL="${(l - 1) * 457200}" algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1"><a:defRPr sz="1800" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/></a:defRPr></a:lvl${l}pPr>""" }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation $NS saveSubsetFonts="1"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>$ids</p:sldIdLst><p:sldSz cx="$W" cy="$H"/><p:notesSz cx="6858000" cy="9144000"/><p:defaultTextStyle><a:defPPr><a:defRPr lang="en-US"/></a:defPPr>$lvls</p:defaultTextStyle></p:presentation>"""
    }

    private fun presentationRels(slides: Int): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS">""")
        sb.append("""<Relationship Id="rId1" Type="$OD/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
        for (n in 1..slides) sb.append("""<Relationship Id="rId${1 + n}" Type="$OD/slide" Target="slides/slide$n.xml"/>""")
        sb.append("""<Relationship Id="rId${slides + 2}" Type="$OD/theme" Target="theme/theme1.xml"/>""")
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun slideRels(image: String?): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$OD/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""" +
            (image?.let { """<Relationship Id="rId2" Type="$OD/image" Target="../media/$it"/>""" } ?: "") + "</Relationships>"

    private const val MASTER_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$OD/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$OD/theme" Target="../theme/theme1.xml"/></Relationships>"""
    private const val LAYOUT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$OD/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""

    private const val TX_LVL = """<a:lvl1pPr marL="0" algn="l" defTabSz="914400" rtl="0" eaLnBrk="1" latinLnBrk="0" hangingPunct="1"><a:defRPr sz="1800" kern="1200"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/></a:defRPr></a:lvl1pPr>"""
    private const val SLIDE_MASTER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldMaster $NS><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>$TREE_HEAD</p:spTree></p:cSld><p:clrMap bg1="dk1" tx1="lt1" bg2="dk2" tx2="lt2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle>$TX_LVL</p:titleStyle><p:bodyStyle>$TX_LVL</p:bodyStyle><p:otherStyle>$TX_LVL</p:otherStyle></p:txStyles></p:sldMaster>"""
    private const val SLIDE_LAYOUT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldLayout $NS type="blank" preserve="1"><p:cSld name="Blank"><p:spTree>$TREE_HEAD</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private const val THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="HiveCore"><a:themeElements><a:clrScheme name="HiveCore"><a:dk1><a:srgbClr val="$BG"/></a:dk1><a:lt1><a:srgbClr val="$TEXT"/></a:lt1><a:dk2><a:srgbClr val="$BG_PANEL"/></a:dk2><a:lt2><a:srgbClr val="$MUTED"/></a:lt2><a:accent1><a:srgbClr val="$ACCENT"/></a:accent1><a:accent2><a:srgbClr val="$ACCENT_DEEP"/></a:accent2><a:accent3><a:srgbClr val="4DD9D7"/></a:accent3><a:accent4><a:srgbClr val="$UP"/></a:accent4><a:accent5><a:srgbClr val="$DOWN"/></a:accent5><a:accent6><a:srgbClr val="C3CBD6"/></a:accent6><a:hlink><a:srgbClr val="$ACCENT"/></a:hlink><a:folHlink><a:srgbClr val="$ACCENT_DEEP"/></a:folHlink></a:clrScheme><a:fontScheme name="HiveCore"><a:majorFont><a:latin typeface="$FONT"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="$FONT"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme><a:fmtScheme name="HiveCore"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""

    // ------------------------------------------------------------------ zip

    /** Stored (uncompressed) entries with one fixed timestamp, so the same content gives the same bytes. */
    private fun zip(parts: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream(parts.values.sumOf { it.size } + 4096)
        ZipOutputStream(bos).use { z ->
            for ((name, bytes) in parts) {
                val e = ZipEntry(name)
                e.method = ZipEntry.STORED
                e.size = bytes.size.toLong()
                e.compressedSize = bytes.size.toLong()
                e.crc = CRC32().apply { update(bytes) }.value
                e.time = FIXED_TIME
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 2026-01-01 00:00 local: zip times are DOS times without a zone, so the value only needs to be constant. */
    private val FIXED_TIME: Long = java.util.GregorianCalendar(2026, 0, 1, 0, 0, 0).timeInMillis
}

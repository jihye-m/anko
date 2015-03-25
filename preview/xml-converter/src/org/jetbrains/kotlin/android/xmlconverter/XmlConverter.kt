/*
 * Copyright 2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.android.xmlconverter

import java.io.File
import org.w3c.dom.Document
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader
import kotlin.dom.*
import org.w3c.dom.Element
import com.google.gson.Gson
import org.jetbrains.kotlin.android.attrs.readResource
import org.jetbrains.kotlin.android.attrs.Attrs
import java.util.regex.Pattern
import java.util.SortedSet
import java.util.TreeSet

private class Widget(val name: String, val attrs: List<KeyValuePair>, val layoutParams: String, val children: List<Widget>) {

    override fun toString(): String {
        fun KeyValuePair.render() = if (value.isNotEmpty()) "$key = $value" else key

        val attributes = attrs.map { it.render() }.joinToString("\n").indent(1)
        val childrenRendered = children.map { it.toString() }.joinToString("\n").indent(1)

        return if (attributes.isEmpty() && childrenRendered.isEmpty()) {
            "$name$layoutParams"
        } else {
            val beforeChildren = if (attributes.isNotEmpty() && childrenRendered.isNotEmpty()) "\n\n" else ""
            "$name {\n$attributes$beforeChildren$childrenRendered\n}$layoutParams"
        }
    }
}

private class XmlReference(val packageName: String, val type: String, val value: String)

public object XmlConverter {

    public fun convert(xml: String, options: Set<String> = setOf()): String {
        val layout = xml.parseXml()
        val root = layout.getDocumentElement()
        val ids = TreeSet<String>()
        val widget = parseView(root, null, ids)

        if ("raw" in options) return widget.toString()

        val imports = listOf(
                "android.app.*",
                "android.view.*",
                "android.widget.*",
                "kotlinx.android.koan.*",
                "android.os.Bundle"
        )

        val idsToProcess = ids.filter { it.startsWith("Ids.") }
        val idsObject = if (idsToProcess.isNotEmpty()) {
            "private object Ids {\n" +
                    idsToProcess.withIndex().map {
                        "val " + it.value.replace("Ids.", "") + " = " + (it.index + 1)
                    }.joinToString("\n").indent(1) + "\n}"
        } else ""

        return imports.map { "import $it" }.joinToString("\n", postfix = "\n\n") +
                "public class SomeActivity : Activity() {\n" +
                "    override fun onCreate(savedInstanceState: Bundle?) {\n" +
                "        super<Activity>.onCreate(savedInstanceState)\n\n" +
                widget.toString().indent(2) +
                "\n    }\n\n" + idsObject.indent(1) +
                "\n}"
    }

    private fun parseView(widget: Element, parentName: String?, idsCollector: SortedSet<String>): Widget {
        val name = widget.getTagName()
        val displayName = Character.toLowerCase(name[0]) + name.substring(1)

        val (attributes, layoutParams) = widget.getWidgetAttributes(name, parentName)
        val children = widget.childElements().map { parseView(it, name, idsCollector) }

        val (optimizedName, optimizedAttributes) = applyAttributeOptimizations(displayName, attributes)
        optimizedAttributes.firstOrNull { it.key == "id" }?.let { idsCollector.add(it.value) }
        return Widget(optimizedName, optimizedAttributes, layoutParams, children)
    }

    private fun applyAttributeOptimizations(displayName: String, attributes: List<KeyValuePair>): Pair<String, List<KeyValuePair>> {
        var last = displayName to attributes
        for (optimization in attributeOptimizations) {
            val f = optimization(last.first, last.second)
            if (f != null) last = f
        }
        return last
    }

    private fun Element.getWidgetAttributes(name: String, parentName: String?): Pair<List<KeyValuePair>, String> {
        val original = arrayListOf<KeyValuePair>()
        val attributesNodeMap = getAttributes()
        for (i in 0..(attributesNodeMap.getLength() - 1)) {
            val attribute = attributesNodeMap.item(i)
            original.add(attribute.getNodeName() * attribute.getNodeValue())
        }
        val (layoutAttributes, ordinaryAttributes) = original.partition { it.key.startsWith("android:layout_") }
        val layoutParams = if (parentName != null) renderLayoutAttributes(layoutAttributes, parentName) else ""

        return ordinaryAttributes.map { transformAttribute(name, it.key, it.value) }.filterNotNull() to layoutParams
    }

}

public fun main(args: Array<String>) {
    if (args.size() < 2) {
        println("Usage: <xml file> <kt file>")
        return
    }

    val xmlFile = File(args[0])
    val ktFile = File(args[1])
    val options = if (args.size() > 2) args[2].split(',').toSet() else setOf()

    if (!xmlFile.exists()) {
        println("$xmlFile does not exist. Aborting")
        return
    }

    val kt = XmlConverter.convert(xmlFile.readText(), options)
    ktFile.writeText(kt)
    println(kt)
}

fun String.parseXml(): Document {
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val inputSource = InputSource(StringReader(this))
    return builder.parse(inputSource)
}
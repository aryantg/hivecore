package com.hivecore.node.docai

import com.hivecore.node.data.AssetReader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One field of a document class (swarm plan §4A.3 METADATA). [type] is one of [FieldSpec.TYPES]:
 *  - `string`: non-empty text; [pattern] (a full-match regex) when set;
 *  - `date`: ISO `YYYY-MM-DD`;
 *  - `number`: a non-negative JSON number;
 *  - `integer`: a JSON integer within [min]..[max];
 *  - `enum`: one of [values] (case-insensitive in, canonical spelling out); [normalise] = "status" maps free-text
 *    payment status ("Settled") to the enum the way the invoice field path does;
 *  - `string_list`: a JSON array of strings.
 */
@Serializable data class FieldSpec(
    val name: String,
    val type: String,
    val values: List<String> = emptyList(),
    val pattern: String? = null,
    val min: Long? = null,
    val max: Long? = null,
    val normalise: String? = null,
    /** false = rules only: the LLM is never asked for this field (formats like GSTIN where a guess adds nothing) */
    val llm: Boolean = true,
) {
    companion object { val TYPES = setOf("string", "date", "number", "integer", "enum", "string_list") }
}

/**
 * The field schema of one document class (`swarm/docschemas/<class>.json`). [identifiers] = "redact_only" marks a
 * class whose identifier numbers are never extracted: any extracted value that contains an identifier ([Redactor])
 * is rejected, and [neverExtract] names the fields that must not appear in the schema.
 */
@Serializable data class DocSchema(
    @SerialName("class") val docClass: String,
    val version: String,
    val description: String,
    val fields: List<FieldSpec>,
    val identifiers: String? = null,
    @SerialName("never_extract") val neverExtract: List<String> = emptyList(),
) {
    val redactOnly: Boolean get() = identifiers == "redact_only"
    fun field(name: String): FieldSpec? = fields.firstOrNull { it.name == name }
}

/** Every class schema, keyed by class name, in [DocSchemas.CLASSES] order. */
class DocSchemas(val byClass: Map<String, DocSchema>) {

    operator fun get(docClass: String): DocSchema? = byClass[docClass]

    /** "invoice: a bill from a vendor asking for payment" (the chooser accepts the key before ": " as an answer). */
    fun label(docClass: String): String = byClass[docClass]?.let { "$docClass: ${it.description}" } ?: "$docClass: $OTHER_DESCRIPTION"

    /** Hash input for R4: class → version, in a fixed order. */
    val versions: String get() = CLASSES.joinToString(",") { "$it@${byClass[it]?.version ?: "-"}" }

    companion object {
        /** Directory of the schema files, both as a classpath resource of this module and as an app asset path. */
        const val SCHEMA_DIR = "swarm/docschemas"
        const val OTHER = "other"
        const val OTHER_DESCRIPTION = "none of the above"
        val CLASSES = listOf("invoice", "receipt", "purchase_order", "contract", "resume", "id_document", "letter", "report")
        /** The DOC_CLASSIFY answer space: the classes and "other". */
        val OPTIONS = CLASSES + OTHER

        private val json = Json { ignoreUnknownKeys = true }

        fun schemaPath(docClass: String) = "$SCHEMA_DIR/$docClass.json"

        fun parse(text: String): DocSchema {
            val s = json.decodeFromString(DocSchema.serializer(), text)
            require(s.docClass in CLASSES) { "unknown class ${s.docClass}" }
            require(s.fields.isNotEmpty()) { "${s.docClass}: no fields" }
            require(s.fields.map { it.name }.toSet().size == s.fields.size) { "${s.docClass}: duplicate field" }
            for (f in s.fields) {
                require(f.type in FieldSpec.TYPES) { "${s.docClass}.${f.name}: unknown type ${f.type}" }
                require(f.type != "enum" || f.values.isNotEmpty()) { "${s.docClass}.${f.name}: enum without values" }
                require(f.name !in s.neverExtract) { "${s.docClass}.${f.name}: listed in never_extract" }
                f.pattern?.let { Regex(it) }
            }
            return s
        }

        /** Loads every class schema through [read] (schema path → text). */
        fun load(read: (String) -> String): DocSchemas =
            DocSchemas(CLASSES.associateWith { c -> parse(read(schemaPath(c))).also { require(it.docClass == c) { "${schemaPath(c)} declares ${it.docClass}" } } })

        /** The schemas shipped with this module (`src/main/resources/swarm/docschemas`), read from the classpath. */
        fun builtin(): DocSchemas = load { path ->
            val stream = DocSchemas::class.java.classLoader?.getResourceAsStream(path) ?: error("missing schema resource $path")
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }

        fun fromAssets(assets: AssetReader): DocSchemas =
            load { path -> assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) } }
    }
}

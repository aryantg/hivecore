package com.hivecore.engine.guide

/**
 * What the guide knows about the swarm right now. The port builds it from the catalogs, the capability summary and
 * the pattern library; the demo builds it from its script. Nothing here is free text from a model.
 */
data class SwarmFacts(
    val phones: List<PhoneFacts>,
    /** some phone of the swarm runs the image model right now (GEN_IMAGE is registered) */
    val canDraw: Boolean,
    /** the text patterns the planner can choose, id → title */
    val patterns: Map<String, String> = emptyMap(),
    /** titles of the split-job recipes the Jobs page offers */
    val recipes: List<String> = emptyList(),
    /** ready-made requests (the Home suggestions) the guide quotes as examples */
    val examples: List<String> = emptyList(),
) {
    val deckExample: String? get() = examples.firstOrNull { DECK_WORDS.any { w -> w in SwarmGuide.words(it) } }
    val dataExample: String? get() = examples.firstOrNull { DECK_WORDS.none { w -> w in SwarmGuide.words(it) } }
    val canDeck: Boolean get() = patterns.keys.any { it.startsWith("deck") }

    private companion object { val DECK_WORDS = setOf("deck", "slides", "presentation") }
}

data class PhoneFacts(val name: String, val tables: List<String>, val docs: List<String> = emptyList(), val self: Boolean = false)

/**
 * Answers questions about the swarm itself ("what can you do", "what can you create an image of", "which tables do you
 * have") from [SwarmFacts], with no model and no plan, so the chat never meets a bare "that isn't a question over the
 * swarm's tables" for a question about the app. [answer] returns null for anything that is a request rather than a
 * question about the app, so "create an image of my team" still reaches the planner. The one exception is a request
 * for a picture *of a chart of the data*: charts are drawn from the real figures (R2), so the guide points at the deck.
 */
object SwarmGuide {
    private val WORD = Regex("[\\p{L}\\p{Nd}']+")

    /** Lowercase words of [text], punctuation dropped. */
    fun words(text: String): List<String> = WORD.findAll(text.lowercase()).map { it.value.trim('\'') }.filter { it.isNotEmpty() }.toList()

    /** Phrases a question about the app starts with. */
    private val META_LEADS = listOf(
        "what can you", "what can u", "what could you", "what all can you", "what else can you", "what are you able",
        "what do you do", "what do you know", "what does this app", "what does this do", "what does hivecore", "what is hivecore",
        "what is this app", "what is this", "what are you", "who are you", "what kind of", "what kinds of", "what sort of",
        "what type of", "what types of", "what can this", "what can the swarm", "what can i ask", "what should i ask",
        "what can i do", "what can we do", "how do i use", "how does this work", "how does it work", "how do you work",
        "how does the swarm", "what data", "what tables", "which tables", "what documents", "which documents", "which phones",
        "what phones", "how many phones", "who is in the swarm", "who is on the swarm", "tell me what you can",
        "show me what you can", "list what you can", "what are your", "what are the things",
    )
    private val GREETINGS = setOf("help", "hi", "hello", "hey", "menu", "options", "capabilities", "features", "commands")

    /** "can you draw" asks about the app; "can you draw a dog" is a request. These leads are checked for a bare object. */
    private val ABILITY_LEADS = listOf("can you", "can u", "could you", "are you able to", "do you", "will you", "is it possible to", "does this", "does it", "can it", "can this")
    private val VERBS = setOf("make", "create", "generate", "draw", "build", "do", "write", "read", "answer", "produce", "design",
        "render", "paint", "sketch", "handle", "support", "give", "show", "plan", "run", "process", "also", "even", "really", "please")
    private val GENERIC_OBJECTS = setOf("a", "an", "the", "some", "any", "images", "image", "pictures", "picture", "illustrations",
        "illustration", "drawings", "drawing", "art", "artwork", "decks", "deck", "slides", "slide", "presentations", "presentation",
        "charts", "chart", "graphs", "graph", "pics", "pic", "photos", "photo", "too", "also", "as", "well", "for", "me", "us", "with",
        "this", "that", "it", "things", "stuff", "anything", "something", "else", "data", "questions", "tables", "documents", "files",
        "of", "off", "my", "our", "your", "at", "all", "what", "kind", "kinds", "sort", "type", "types", "on", "here", "now", "yet")

    private val IMAGE_WORDS = setOf("image", "images", "picture", "pictures", "illustration", "illustrations", "draw", "drawing",
        "drawings", "art", "artwork", "logo", "logos", "poster", "posters", "sketch", "sketches", "visual", "visuals", "graphic",
        "graphics", "paint", "painting", "render", "renders", "illustrate", "pic", "pics")
    private val CHART_WORDS = setOf("chart", "charts", "graph", "graphs", "plot", "plots", "visualisation", "visualization", "visualise", "visualize")
    /** a picture request that names a chart but asks for art, not figures, goes to the planner as asked */
    private val ART_WORDS = setOf("abstract", "decorative", "stylised", "stylized", "artistic", "generic", "symbolic", "icon", "iconic")
    private val DECK_WORDS = setOf("deck", "decks", "slide", "slides", "presentation", "presentations", "pitch", "pptx", "powerpoint")
    private val DATA_WORDS = setOf("data", "table", "tables", "know", "hold", "holds", "have", "files", "documents", "document", "docs",
        "information", "info", "dataset", "datasets", "database", "access", "see", "read", "about", "contain", "contains", "stored")
    private val PHONE_WORDS = setOf("phone", "phones", "device", "devices", "swarm", "member", "members", "nodes", "node", "connected", "online")
    private val JOB_WORDS = setOf("jobs", "job", "ocr", "letters", "letter", "recipe", "recipes", "split", "bulk", "batch", "photos", "invoices")

    enum class Topic { OVERVIEW, IMAGES, CHART_IMAGE, DECK, DATA, PHONES, JOBS }

    /** The guide's answer, or null when the text is a request for the planner. */
    fun answer(question: String, facts: SwarmFacts): String? {
        val w = words(question)
        if (w.isEmpty()) return null
        val topic = topic(w)
        if (topic == Topic.CHART_IMAGE) return chartImage(facts)
        if (!isMeta(w)) return null
        return when (topic) {
            Topic.IMAGES -> images(facts)
            Topic.DECK -> deck(facts)
            Topic.DATA -> data(facts)
            Topic.PHONES -> phones(facts)
            Topic.JOBS -> jobs(facts)
            else -> overview(facts)
        }
    }

    /** One sentence for a refusal: what to ask instead. */
    fun hint(facts: SwarmFacts): String {
        val tables = facts.phones.flatMap { it.tables }.map(::pretty).distinct()
        val about = if (tables.isEmpty()) "the data on your phones" else "the data on your phones (${list(tables.take(6))})"
        val extras = listOfNotNull(if (facts.canDeck) "ask for a pitch deck" else null, if (facts.canDraw) "ask for an illustration" else null)
        return "Ask about $about" + (if (extras.isEmpty()) "" else ", " + extras.joinToString(" or ")) + ", or type \"what can you do\"."
    }

    /** Is [w] a question about the app rather than a request? */
    fun isMeta(w: List<String>): Boolean {
        if (w.isEmpty()) return false
        val joined = w.joinToString(" ")
        if (w.size <= 2 && w.all { it in GREETINGS || it in setOf("please", "there", "you") } && w.any { it in GREETINGS }) return true
        if (META_LEADS.any { joined == it || joined.startsWith("$it ") }) return true
        if (joined.startsWith("help ")) return true
        // "what jobs can you run", "which tables do you hold": a what/which/who question addressed to the app
        if (w[0] in setOf("what", "which", "who") && w.drop(1).take(4).any { it == "you" || it == "u" }) return true
        for (lead in ABILITY_LEADS) {
            if (joined != lead && !joined.startsWith("$lead ")) continue
            val rest = w.drop(lead.split(' ').size).dropWhile { it in VERBS }
            if (rest.all { it in GENERIC_OBJECTS }) return true
        }
        return false
    }

    fun topic(w: List<String>): Topic {
        val set = w.toSet()
        val image = set.any { it in IMAGE_WORDS }
        val chart = set.any { it in CHART_WORDS }
        return when {
            image && chart && set.none { it in ART_WORDS } -> Topic.CHART_IMAGE
            image -> Topic.IMAGES
            set.any { it in DECK_WORDS } -> Topic.DECK
            set.any { it in PHONE_WORDS } && (set.any { it in setOf("who", "which", "how", "many", "what") }) -> Topic.PHONES
            set.any { it in DATA_WORDS } -> Topic.DATA
            set.any { it in JOB_WORDS } -> Topic.JOBS
            else -> Topic.OVERVIEW
        }
    }

    // ------------------------------------------------------------------ the answers

    private fun images(f: SwarmFacts): String {
        val deckLine = if (f.canDeck) " Charts of your own figures come from a deck instead: ask for a pitch deck and the chart is built from the owner phone's table." else ""
        if (!f.canDraw) return "Not right now: no phone of the swarm has the image model ready. Drawing needs a phone on the charger with " +
            "Stable Diffusion loaded (the Models page shows which phone has it). Once one is ready, ask \"Create an image of …\" and it draws a " +
            "512-pixel illustration from your words." + deckLine
        return "I can draw an illustration of anything you describe in a sentence: a product, a place, a scene, a team at work, a mascot, " +
            "a poster or an abstract visual for a slide. Stable Diffusion runs on a phone of the swarm and makes a 512-pixel picture in about " +
            "half a minute; nothing leaves your phones. Two limits: it draws from your words, not from your photos or tables, so it cannot show " +
            "real people or real figures, and it will not write readable text." + deckLine +
            " Try: \"Create an image of a pharmacy counter at dawn\" or \"Draw a poster for the sales kickoff\"."
    }

    private fun chartImage(f: SwarmFacts): String {
        val example = f.deckExample?.let { " For example: \"$it\"." } ?: ""
        val deck = if (f.canDeck) "Charts here are drawn from your real figures, never imagined: ask for a pitch deck and the chart slide is " +
            "built from the owner phone's table, then presented, opened as .pptx or shared.$example" else
            "Charts here are drawn from your real figures, never imagined: ask about the amount you want charted and the owner phone works it out."
        val art = if (f.canDraw) " If you want a decorative illustration instead, say so: \"Draw an abstract rising chart in amber and slate\"." else ""
        return deck + art
    }

    private fun deck(f: SwarmFacts): String {
        if (!f.canDeck) return "Decks are not in this build's pattern library." + examplesLine(f)
        val picture = if (f.canDraw) ", a picture from the image model" else ""
        val example = f.deckExample?.let { " Try: \"$it\"." } ?: ""
        return "Yes. Ask for a pitch deck on an amount your phones hold and the swarm builds the slides: the figures from the owner phone's table, " +
            "the words from a phone's language model$picture, assembled here as a .pptx you can present, open or share. Every number on a slide " +
            "comes from the table, never from a model.$example"
    }

    private fun data(f: SwarmFacts): String {
        if (f.phones.isEmpty()) return "No phone has shared a catalog yet. Pair the phones on the Swarm page, then each one's tables and documents show up here."
        val lines = f.phones.map { p ->
            val parts = listOfNotNull(
                p.tables.takeIf { it.isNotEmpty() }?.let { "tables ${list(it.map(::pretty))}" },
                p.docs.takeIf { it.isNotEmpty() }?.let { "documents ${list(it.map(::docName))}" },
            )
            "${phoneName(p)}: " + (if (parts.isEmpty()) "nothing shared" else parts.joinToString(" and "))
        }
        val example = f.dataExample?.let { " Try: \"$it\"." } ?: ""
        return "Your phones hold: ${lines.joinToString("; ")}. Ask about any of them by name and the phone that holds the data works out its " +
            "part; only totals travel, never rows.$example"
    }

    private fun phones(f: SwarmFacts): String {
        if (f.phones.isEmpty()) return "No phone is in the swarm yet. Pair one on the Swarm page (QR in the same room, or an invite link)."
        val names = f.phones.map { phoneName(it) }
        return "The swarm is ${names.size} phone${if (names.size == 1) "" else "s"}: ${list(names)}. The Swarm page shows each one's battery, " +
            "temperature, loaded model and queue; a phone works for the others while it is asleep on the charger."
    }

    private fun jobs(f: SwarmFacts): String {
        val recipes = if (f.recipes.isEmpty()) "" else " The recipes on the Jobs page: ${list(f.recipes)}."
        return "Big work is split into chunks across every phone and merged here with the same result as one phone would give: read a folder of " +
            "photos, write one letter per row of a table, run a question set.$recipes Open Jobs to pick one and watch the chunk grid fill."
    }

    private fun overview(f: SwarmFacts): String {
        val names = f.phones.map { phoneName(it) }
        val who = if (names.isEmpty()) "I'm the swarm of your phones." else "I'm the swarm of your ${names.size} phone${if (names.size == 1) "" else "s"} (${list(names)})."
        val tables = f.phones.flatMap { it.tables }.map(::pretty).distinct()
        val about = if (tables.isEmpty()) "Ask about the data they hold" else "Ask about the data they hold (${list(tables.take(6))})"
        val abilities = f.patterns.keys.mapNotNull(::ability).distinct()
        val can = if (abilities.isEmpty()) "" else ": " + abilities.joinToString(", ")
        val extras = listOfNotNull(if (f.canDeck) "build a pitch deck" else null, if (f.canDraw) "draw an illustration from a description" else null,
            if (f.recipes.isNotEmpty()) "split big jobs across the phones on the Jobs page" else null)
        val also = if (extras.isEmpty()) "" else " I can also ${list(extras)}."
        return "$who $about and I plan the steps across them so nothing leaves the phones$can.$also" + examplesLine(f)
    }

    private fun examplesLine(f: SwarmFacts): String = f.examples.take(2).takeIf { it.isNotEmpty() }?.let { " Try: ${it.joinToString(" or ") { e -> "\"$e\"" }}." } ?: ""

    /** A short verb phrase per shipped pattern (unknown ids are left out of the overview). */
    fun ability(patternId: String): String? = when (patternId) {
        "per_unit" -> "work out one amount per another (sales per rep)"
        "compare_two" -> "compare two amounts"
        "growth_over_period" -> "follow an amount month by month"
        "top_k_by" -> "rank groups by an amount"
        "flag_vs_average" -> "flag groups above or below the average"
        "reconcile_counts" -> "check that two departments' counts agree"
        "ratio_then_rank" -> "rank groups by a ratio"
        "multi_doc_extract" -> "pull the key points from shared documents"
        else -> null
    }

    private fun phoneName(p: PhoneFacts) = if (p.self) "${p.name} (this phone)" else p.name
    private fun pretty(table: String) = table.replace('_', ' ')
    private fun docName(doc: String) = doc.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
    private fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}

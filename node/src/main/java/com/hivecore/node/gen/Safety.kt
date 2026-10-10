package com.hivecore.node.gen

// Swarm plan §4A.3 generation guardrails (and the CreativeML OpenRAIL-M use restrictions): a prompt blocklist
// runs BEFORE generation (AI Hub's SD has no safety checker), and a post-check hook runs on the PNG after it;
// a blocked output is dropped, never stored. The blocklist is deliberately conservative for a product-shot tool.

data class SafetyVerdict(val ok: Boolean, val category: String? = null, val term: String? = null) {
    companion object { val OK = SafetyVerdict(true) }
}

/** Post-generation check bound by the integrator (e.g. CLIP zero-shot against unsafe concepts): true = safe. */
fun interface ImageSafetyCheck { suspend fun safe(png: ByteArray): Boolean }

object PromptSafety {
    const val SEXUAL = "sexual"
    const val MINORS = "minors"
    const val VIOLENCE = "violence"
    const val REAL_PERSON = "real_person"
    const val WEAPONS = "weapons"

    private val sexual = setOf(
        "nude", "nudes", "naked", "nudity", "nsfw", "porn", "porno", "pornographic", "pornography", "sex", "sexual",
        "sexy", "erotic", "erotica", "explicit", "topless", "bottomless", "genitals", "genitalia", "penis", "vagina",
        "nipple", "nipples", "breasts", "boobs", "hentai", "xxx", "fetish", "orgasm", "lewd", "undressed", "unclothed",
        "stripper", "striptease", "onlyfans", "bdsm", "intercourse", "masturbation", "seductive", "sensual",
    ).map(::norm).toSet()
    private val sexualPhrases = listOf("no clothes", "without clothes", "see through", "see-through", "bare chest", "in bed with").map(::norm)

    private val minors = setOf(
        "child", "children", "kid", "kids", "boy", "boys", "girl", "girls", "teen", "teens", "teenager", "teenagers",
        "minor", "minors", "underage", "toddler", "toddlers", "baby", "babies", "infant", "infants", "schoolgirl",
        "schoolboy", "preteen", "loli", "shota", "young girl", "young boy", "little girl", "little boy",
    ).map(::norm).toSet()
    /** Combined with a minor term these block too (sexual terms block on their own anyway). */
    private val suggestive = setOf(
        "bikini", "lingerie", "swimsuit", "underwear", "panties", "bra", "provocative", "kissing", "flirting",
        "pinup", "pin up", "bath", "bathing", "shower", "posing",
    ).map(::norm).toSet()

    private val violence = setOf(
        "gore", "gory", "gruesome", "decapitated", "decapitation", "beheaded", "beheading", "dismembered",
        "dismemberment", "mutilated", "mutilation", "disembowel", "disemboweled", "entrails", "guts", "corpse",
        "corpses", "dead body", "dead bodies", "bloody", "blood splatter", "bloodbath", "torture", "tortured",
        "massacre", "murder", "murdered", "slaughter", "severed head", "severed", "execution", "suicide", "self harm",
        "self-harm", "stabbing", "mass shooting", "lynching", "genocide", "terrorist attack",
    ).map(::norm).toSet()

    private val weaponsHard = setOf(
        "pipe bomb", "ied", "molotov", "molotov cocktail", "nerve agent", "sarin", "vx gas", "anthrax", "ricin",
        "ghost gun", "3d printed gun", "napalm", "dirty bomb", "bomb making", "make a bomb", "build a bomb",
        "chemical weapon", "bioweapon", "explosive device",
    ).map(::norm).toSet()
    private val weapons = setOf(
        "bomb", "bombs", "explosive", "explosives", "gun", "guns", "rifle", "pistol", "firearm", "silencer",
        "suppressor", "detonator", "grenade", "ammunition", "weapon", "weapons",
    ).map(::norm).toSet()
    private val instructions = setOf(
        "how to", "instructions", "instruction", "step by step", "steps", "diagram", "schematic", "schematics",
        "blueprint", "blueprints", "tutorial", "recipe", "assemble", "assembly", "build", "make", "manufacture", "diy",
    ).map(::norm).toSet()

    /** Explicit real people (public figures most likely to be asked for); names are matched case-insensitively. */
    val realPeople: Set<String> = setOf(
        "taylor swift", "elon musk", "donald trump", "joe biden", "kamala harris", "barack obama", "michelle obama",
        "hillary clinton", "vladimir putin", "xi jinping", "narendra modi", "rahul gandhi", "volodymyr zelensky",
        "emmanuel macron", "rishi sunak", "keir starmer", "king charles", "queen elizabeth", "pope francis",
        "mark zuckerberg", "jeff bezos", "bill gates", "sundar pichai", "satya nadella", "tim cook", "sam altman",
        "shah rukh khan", "salman khan", "amitabh bachchan", "deepika padukone", "priyanka chopra", "alia bhatt",
        "virat kohli", "sachin tendulkar", "ms dhoni", "lionel messi", "cristiano ronaldo", "lebron james",
        "kim kardashian", "kanye west", "beyonce", "rihanna", "scarlett johansson", "emma watson", "tom cruise",
        "brad pitt", "angelina jolie", "leonardo dicaprio", "keanu reeves", "selena gomez", "ariana grande",
        "justin bieber", "billie eilish", "greta thunberg", "mukesh ambani", "gautam adani", "ratan tata",
    ).map(::norm).toSet()

    /** Common given names: a capitalised one followed by another capitalised word reads as a person's name. */
    private val firstNames = setOf(
        "james", "john", "robert", "michael", "william", "david", "richard", "joseph", "thomas", "charles", "daniel",
        "matthew", "anthony", "mark", "donald", "steven", "paul", "andrew", "joshua", "kevin", "brian", "george",
        "edward", "ronald", "timothy", "jason", "jeffrey", "ryan", "jacob", "gary", "nicholas", "eric", "jonathan",
        "stephen", "larry", "justin", "scott", "brandon", "benjamin", "samuel", "frank", "gregory", "raymond",
        "alexander", "patrick", "jack", "dennis", "jerry", "tyler", "aaron", "jose", "henry", "adam", "peter", "nathan",
        "mary", "patricia", "jennifer", "linda", "elizabeth", "barbara", "susan", "jessica", "sarah", "karen", "nancy",
        "lisa", "betty", "margaret", "sandra", "ashley", "kimberly", "emily", "donna", "michelle", "dorothy", "carol",
        "amanda", "melissa", "deborah", "stephanie", "rebecca", "sharon", "laura", "cynthia", "kathleen", "amy",
        "shirley", "angela", "helen", "anna", "brenda", "pamela", "nicole", "emma", "samantha", "katherine",
        "christine", "debra", "rachel", "catherine", "carolyn", "janet", "ruth", "maria", "heather", "diane",
        "virginia", "julie", "joyce", "victoria", "olivia", "kelly", "christina", "lauren", "joan", "evelyn",
        "judith", "megan", "cheryl", "andrea", "hannah", "martha", "jacqueline", "frances", "gloria", "ann", "teresa",
        "kathryn", "sara", "janice", "jean", "alice", "madison", "doris", "abigail", "julia", "judy", "grace",
        "denise", "amber", "marilyn", "beverly", "danielle", "theresa", "sophia", "marie", "diana", "brittany",
        "natalie", "isabella", "charlotte", "rose", "alexis", "kayla", "taylor", "elon", "barack", "joe", "kim",
        "rahul", "amit", "anil", "arjun", "aryan", "ravi", "raj", "rajesh", "suresh", "ramesh", "mahesh", "vijay",
        "sanjay", "sunil", "ajay", "deepak", "manoj", "rohit", "virat", "sachin", "narendra", "priya", "priyanka",
        "deepika", "anjali", "pooja", "neha", "sneha", "kavita", "sunita", "anita", "aishwarya", "kareena", "katrina",
        "salman", "shah", "aamir", "akshay", "hrithik", "ranbir", "ranveer", "amitabh", "mukesh", "ratan", "gautam",
        "mohammed", "muhammad", "ahmed", "ali", "omar", "fatima", "aisha", "wei", "li", "chen", "hiroshi", "yuki",
        "carlos", "juan", "luis", "miguel", "pedro", "sofia", "lucia", "pierre", "jean", "hans", "ivan", "olga",
        "vladimir", "dmitri", "lionel", "cristiano", "lebron", "kanye", "justin", "selena", "ariana", "billie",
    )
    /** "a portrait of Xxx Yyy": capitalised words right after one of these read as a named person. */
    private val personCues = listOf("photo of", "portrait of", "picture of", "image of", "face of", "selfie of",
        "selfie with", "headshot of", "likeness of", "in the style of", "posing with", "drawing of", "painting of", "starring")
    /** Capitalised words that are not names (places, brands, products a product shot might mention). */
    private val notNames = setOf(
        "new", "york", "san", "francisco", "los", "angeles", "las", "vegas", "hong", "kong", "united", "states",
        "kingdom", "north", "south", "east", "west", "mount", "lake", "river", "city", "street", "park", "eiffel",
        "tower", "golden", "gate", "bridge", "times", "square", "taj", "mahal", "great", "wall", "central",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march",
        "april", "may", "june", "july", "august", "september", "october", "november", "december", "christmas",
        "diwali", "easter", "halloween", "art", "deco", "nouveau", "renaissance", "baroque", "gothic", "victorian",
        "studio", "ghibli", "pixar", "disney", "lego", "coca", "cola", "apple", "samsung", "google", "nike", "adidas",
        "the", "a", "an", "and", "of", "in", "on", "with", "at", "by", "for", "red", "blue", "green", "black", "white",
        "hd", "4k", "8k", "dslr", "canon", "nikon", "sony", "leica", "unreal", "engine", "octane", "render",
    )

    /** Lower-case, undo common character substitutions, keep letters/digits/spaces only, single spaces. */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length + 2)
        for (ch in s.lowercase()) {
            val c = when (ch) { '0' -> 'o'; '1', '!' -> 'i'; '3' -> 'e'; '4', '@' -> 'a'; '5', '$' -> 's'; '7' -> 't'; else -> ch }
            sb.append(if (c.isLetterOrDigit()) c else ' ')
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun hit(padded: String, terms: Set<String>): String? = terms.firstOrNull { it.isNotEmpty() && padded.contains(" $it ") }

    /** Pre-generation check of [prompt] (and the negative prompt is not checked: it only removes concepts). */
    fun check(prompt: String): SafetyVerdict {
        val n = " " + norm(prompt) + " "
        // a literal pass too, so "3d printed gun" is not destroyed by the digit substitution
        val lit = " " + prompt.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim() + " "
        fun any(terms: Set<String>) = hit(n, terms) ?: hit(lit, terms)
        val minor = any(minors)
        any(sexual)?.let { return SafetyVerdict(false, if (minor != null) MINORS else SEXUAL, it) }
        sexualPhrases.firstOrNull { n.contains(" $it ") }?.let { return SafetyVerdict(false, if (minor != null) MINORS else SEXUAL, it) }
        if (minor != null) any(suggestive)?.let { return SafetyVerdict(false, MINORS, "$minor + $it") }
        any(violence)?.let { return SafetyVerdict(false, VIOLENCE, it) }
        any(weaponsHard)?.let { return SafetyVerdict(false, WEAPONS, it) }
        val w = any(weapons)
        if (w != null) any(instructions)?.let { return SafetyVerdict(false, WEAPONS, "$w + $it") }
        any(realPeople)?.let { return SafetyVerdict(false, REAL_PERSON, it) }
        capitalisedName(prompt)?.let { return SafetyVerdict(false, REAL_PERSON, it) }
        return SafetyVerdict.OK
    }

    /**
     * Name heuristic on the original casing: two or more consecutive Capitalised words that are not known
     * non-names, where the first is a common given name or the run follows a person cue ("portrait of").
     */
    fun capitalisedName(prompt: String): String? {
        val tokens = Regex("[\\p{L}][\\p{L}'.-]*").findAll(prompt).map { it.value to it.range.first }.toList()
        var i = 0
        while (i < tokens.size) {
            val run = ArrayList<Pair<String, Int>>()
            var j = i
            while (j < tokens.size && isCap(tokens[j].first) && tokens[j].first.lowercase().trimEnd('.') !in notNames) { run += tokens[j]; j++ }
            if (run.size >= 2) {
                val first = run[0].first.lowercase().trimEnd('.')
                val before = prompt.substring(0, run[0].second).lowercase().trimEnd()
                if (first in firstNames || personCues.any { before.endsWith(it) }) return run.joinToString(" ") { it.first }
            }
            i = if (j > i) j else i + 1
        }
        return null
    }

    private fun isCap(w: String) = w.length >= 2 && w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() }
}

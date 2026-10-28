package app.tvmate

import java.text.Normalizer

/** Turns a provider's cluttered live list into raw channels: one entry per channel, variants kept as fallbacks. */
object Clean {
    // PPV slots, event placeholders, 24/7 loops and "#####" separators: VOD-like, not channels.
    private val JUNK = Regex("""\bPPV\b|NO EVENT|24/7|\d{4}-\d{2}-\d{2}|\bvs\.?\s|@|\bREPLAY\b|\bVOD\b|#{3,}|^NEXT \|""", RegexOption.IGNORE_CASE)
    private val QUAL = Regex("""\b(F?HD|SD|UHD|4K|8K|HEVC|H\.?265|RAW|\d{2,3}\s?FPS|\d{3,4}P|LQ|HQ|VIP|BACKUP|DOLBY|AUDIO|ATMOS|MULTI|HDR|DVB-?T)\b""", RegexOption.IGNORE_CASE)

    fun junk(name: String, cat: String) = JUNK.containsMatchIn(name) || JUNK.containsMatchIn(cat)

    // NFKC turns the superscript tags providers love (ᴿᴬᵂ ᴴᴰ ⁶⁰ᶠᵖˢ) into plain RAW/HD/60fps.
    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)

    fun base(name: String): String {
        var n = plain(name)
        val colon = n.indexOf(':')
        if (colon in 1..13) n = n.substring(colon + 1)
        n = n.map { c ->
            when (Character.getType(c).toByte()) {
                Character.OTHER_SYMBOL, Character.MODIFIER_SYMBOL, Character.MODIFIER_LETTER, Character.OTHER_NUMBER -> ' '
                else -> if (c == '|') ' ' else c
            }
        }.joinToString("")
        n = n.replace(Regex("""\((backup|multi[^)]*)\)""", RegexOption.IGNORE_CASE), " ")
        return QUAL.replace(n, " ").replace(Regex("""\s+"""), " ").trim(' ', '-', '_', '.')
    }

    fun key(base: String) = base.lowercase().filter { it.isLetterOrDigit() }

    /** Lower plays first: plain HD starts fastest, 4K/HEVC are heavy, SD/LQ is the last resort. */
    fun rank(name: String): Int {
        val n = plain(name).uppercase()
        val heavy = Regex("""4K|UHD|8K|HEVC|H\.?265""").containsMatchIn(n)
        return when {
            Regex("""\b(SD|LQ)\b""").containsMatchIn(n) -> 3
            heavy -> 2
            Regex("""\bF?HD\b""").containsMatchIn(n) -> 0
            else -> 1
        }
    }

    class Searchable<T>(val key: String, val words: List<String>, val item: T)

    fun <T> searchable(name: String, item: T) =
        Searchable(key(name), plain(name).lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }, item)

    /** Every query word must match; exact name, then prefix, then word-start, then substring. Shorter names win ties. */
    fun <T> search(q: String, index: List<Searchable<T>>, limit: Int = 300): List<T> {
        val all = key(q)
        val terms = plain(q).lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (all.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Int, Searchable<T>>>()
        for (e in index) {
            val score = when {
                e.key == all -> 0
                e.key.startsWith(all) -> 1
                terms.all { t -> e.words.any { it.startsWith(t) } } -> 2
                e.key.contains(all) || terms.all { e.key.contains(it) } -> 3
                else -> continue
            }
            hits += score to e
        }
        return hits.sortedWith(compareBy({ it.first }, { it.second.key.length })).take(limit).map { it.second.item }
    }

    // Picons on bare-IP hosts are the ones that go dead; domain-hosted logos survive.
    fun weakLogo(url: String?) = url == null || Regex("""^https?://\d+\.\d+\.\d+\.\d+""").containsMatchIn(url)

    fun bestLogo(urls: List<String>) = urls.firstOrNull { !weakLogo(it) } ?: urls.firstOrNull()

    val GENRES = listOf("Sports", "News", "Movies", "Kids", "Documentary", "Music", "Entertainment")
    private val GENRE_RULES = listOf(
        "Sports" to """sport|spor\b|deport|espn|bein|dazn|tsn|eurosport|nba|nfl|nhl|mlb|golf|\bf1\b|racing|cricket|futbol|football|soccer|tennis|arena|setanta|ufc|wwe|fight|motor|liga|calcio|rugby""",
        "News" to """news|cnn|al ?jazeera|noticias|nachrichten|\binfo\b|actualit|haber|euronews|bloomberg|cnbc|msnbc""",
        "Kids" to """kid|junior|cartoon|disney|nick|baby|enfant|kinder|toon|boomerang|cbeebies|jim jam|pogo""",
        "Movies" to """cinema|movie|film|kino|\bcine|hbo|starz|showtime|paramount|mgm|\bamc\b|\btcm\b|cinemax""",
        "Documentary" to """docu|discovery|nat ?geo|history|animal|planet|science|investigation|nature|travel""",
        "Music" to """music|mtv|vh1|musique|musik|muzik|\bhits?\b|radio""",
    ).map { it.first to Regex(it.second, RegexOption.IGNORE_CASE) }

    fun genre(text: String) = GENRE_RULES.firstOrNull { it.second.containsMatchIn(text) }?.first ?: "Entertainment"

    private val COUNTRIES = mapOf(
        "AR" to "Arabic", "US" to "USA", "UK" to "United Kingdom", "ES" to "Spain", "DE" to "Germany", "NL" to "Netherlands",
        "ASIA" to "Asia", "FR" to "France", "SE" to "Sweden", "CA" to "Canada", "PL" to "Poland", "IT" to "Italy",
        "DK" to "Denmark", "AL" to "Albania", "TR" to "Turkey", "GR" to "Greece", "NO" to "Norway", "LA" to "Latin America",
        "PT" to "Portugal", "BE" to "Belgium", "AFR" to "Africa", "IE" to "Ireland", "FI" to "Finland", "AU" to "Australia",
        "CH" to "Switzerland", "KU" to "Kurdish", "BR" to "Brazil", "MX" to "Mexico", "AT" to "Austria", "IL" to "Israel",
        "CZ" to "Czechia", "IR" to "Iran", "HR" to "Croatia", "CY" to "Cyprus", "NZ" to "New Zealand", "MA" to "Morocco",
        "EXYU" to "Ex-Yu", "ID" to "Indonesia", "IS" to "Iceland", "HU" to "Hungary", "RO" to "Romania", "MK" to "Macedonia",
        "SI" to "Slovenia", "BG" to "Bulgaria", "KR" to "Korea", "RU" to "Russia", "JP" to "Japan", "PH" to "Philippines",
        "GE" to "Georgia", "AZ" to "Azerbaijan", "UZ" to "Uzbekistan", "AM" to "Armenia", "VE" to "Venezuela",
        "HK" to "Hong Kong", "TH" to "Thailand", "KZ" to "Kazakhstan", "LT" to "Lithuania", "LV" to "Latvia", "IN" to "India", "4K" to "4K & 8K", "8K" to "4K & 8K",
    )

    /** Providers prefix categories with a country code ("UK| SKY SPORTS"); that is the one reliable grouping. */
    fun country(cat: String): String {
        val code = cat.substringBefore('|', "").substringBefore(':').trim().uppercase()
        return if (code.isEmpty() || code.length > 5) "Other" else COUNTRIES[code] ?: code
    }
}

package ru.cs2a.helper

/**
 * Extracts promo-like tokens from Telegram message text.
 * Supports Latin/Cyrillic letters, digits and mixed tokens. Formatting entities are
 * flattened by TDLib into text; backtick-delimited snippets receive extra priority.
 */
object PromoCodeParser {
    private val tokenRegex = Regex("(?<![\\p{L}\\p{N}])[\\p{L}\\p{N}]{4,40}(?![\\p{L}\\p{N}])")
    private val numericRegex = Regex("(?<!\\d)\\d{8,32}(?!\\d)")
    private val ignoredWords = setOf("ПРОМОЧАТ", "ПРОМОКОД", "АКЦИЯ", "КУПИТЬ", "ПОДПИСКУ", "ПОДПИСКА", "КЕЙС", "БОНУС", "ДЕПОЗИТ", "ПОПОЛНЕНИЕ", "ПРОМО", "TELEGRAM", "HTTP", "HTTPS", "BOT")
    private val percentRegex = Regex("(?i)(?:депозит|deposit|пополнен|пополнить|бонус)[^\\n]{0,45}\\+?\\d{1,3}\\s*%|\\+\\s*\\d{1,3}\\s*%[^\\n]{0,45}(?:депозит|deposit|пополнен|бонус)")

    fun isExplicitDepositPercentBonus(message: String): Boolean =
        percentRegex.containsMatchIn(message)

    fun extract(message: String): List<String> = extract(message, emptyList())

    fun extract(message: String, entityTexts: List<String>): List<String> {
        if (message.isBlank() || isExplicitDepositPercentBonus(message)) return emptyList()
        val found = LinkedHashSet<String>()
        entityTexts.forEach { normalize(it)?.let(found::add) }

        // Telegram posts frequently format the code as inline/pre text. Keep these first.
        Regex("`([^`]{4,80})`").findAll(message).forEach { match ->
            normalize(match.groupValues[1])?.let(found::add)
        }

        // Currency-price lines such as "0.25$ — BLSTRIS1".
        message.lineSequence().forEach { line ->
            if (Regex("(?i)(?:\\$|€|₽|руб|usd)\\s*(?:[—–:-])").containsMatchIn(line) ||
                Regex("(?i)(?:[—–:-])\\s*(?:\\$|€|₽|руб|usd)").containsMatchIn(line)) {
                tokenRegex.findAll(line).forEach { normalize(it.value)?.let(found::add) }
            }
        }

        tokenRegex.findAll(message).forEach { match ->
            val raw = match.value
            val code = normalize(raw)
            // Avoid ordinary sentence words and channel headings. Unformatted candidates
            // must look like promo tokens: uppercase or alphanumeric.
            val looksLikeCode = raw.any(Char::isDigit) || raw == raw.uppercase()
            if (code != null && looksLikeCode && code !in ignoredWords) found.add(code)
        }
        return found.toList()
    }

    private fun normalize(raw: String): String? {
        val cleaned = raw.trim().trim { !it.isLetterOrDigit() }
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
            .uppercase()
        if (cleaned.length !in 4..40) return null
        if (!cleaned.any(Char::isLetter) && !numericRegex.matches(cleaned)) return null
        return cleaned
    }
}

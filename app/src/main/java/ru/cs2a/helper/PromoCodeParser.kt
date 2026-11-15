package ru.cs2a.helper

/**
 * Conservative promo parser.
 * Explicit Telegram code/pre formatting wins; otherwise only a code after a price/case
 * marker or a standalone uppercase/alphanumeric line is accepted.
 */
object PromoCodeParser {
    private val tokenRegex = Regex("""(?<![\p{L}\p{N}])[\p{L}\p{N}]{4,40}(?![\p{L}\p{N}])""")
    private val numericRegex = Regex("""\d{8,32}""")
    private val priceRegex = Regex(
        """(?iu)(?:\d+(?:[.,]\d+)?\s*(?:\$|€|₽|руб(?:\.|лей)?|usd)|(?:\$|€|₽)\s*\d+(?:[.,]\d+)?)"""
    )
    private val caseRegex = Regex("""(?iu)\b(?:КЕЙС|CASE)\b""")
    private val separatorRegex = Regex("""[—–:-]""")

    private val ignoredWords = setOf(
        "ПРОМО", "ПРОМОКОД", "ПРОМОЧАТ", "ПРОМОИЗПОСТА", "ИЗ", "ПОСТА",
        "КЕЙС", "CASE", "АКЦИЯ", "КУПИТЬ", "ПОДПИСКУ", "ПОДПИСКА",
        "БОНУС", "ДЕПОЗИТ", "ПОПОЛНЕНИЕ", "TELEGRAM", "БОТ", "BOT",
        "HTTP", "HTTPS", "COPY", "КОПИРОВАТЬ", "ЦЕНА", "ПОДАРОК",
        "СКИДКА", "ПОДПИШИСЬ", "ПОДПИСАТЬСЯ"
    )
    private val ignoredPhrases = listOf(
        "ПРОМО ИЗ ПОСТА", "PROMO FROM POST", "КУПИТЬ ПОДПИСКУ",
        "ПОДПИСКУ В TELEGRAM", "TELEGRAM-БОТ"
    )
    private val depositConditionRegex = Regex(
        """(?iu)депозит\w*|deposit\w*|пополн\w*|top[\s-]?up|minimum\s+(?:deposit|top[\s-]?up)"""
    )

    fun isDepositRelated(message: String): Boolean =
        depositConditionRegex.containsMatchIn(message)

    fun isValidCandidate(raw: String): Boolean = normalize(raw) != null

    fun extract(message: String): List<String> = extract(message, emptyList())

    fun extract(message: String, entityTexts: List<String>): List<String> {
        if (message.isBlank() || isDepositRelated(message)) return emptyList()

        // Telegram code/pre entities are explicit code signals.
        val explicit = LinkedHashSet<String>()
        entityTexts.forEach { raw -> normalize(raw)?.let(explicit::add) }
        if (explicit.isNotEmpty()) return explicit.toList()

        // Inline code is the strongest signal. Never scan surrounding prose if present.
        val markdownCodes = LinkedHashSet<String>()
        Regex("`([^`\n]{1,80})`").findAll(message).forEach { match ->
            normalize(match.groupValues[1])?.let(markdownCodes::add)
        }
        if (markdownCodes.isNotEmpty()) return markdownCodes.toList()

        val found = LinkedHashSet<String>()
        message.lineSequence().forEach { originalLine ->
            val line = originalLine.trim()
            if (line.isBlank()) return@forEach
            val upper = line.uppercase()
            if (ignoredPhrases.any { upper.contains(it) }) return@forEach
            if (upper.contains("TELEGRAM-BOT") || upper.contains("T.ME/") || upper.contains("HTTPS://")) {
                return@forEach
            }

            if (priceRegex.containsMatchIn(line) || caseRegex.containsMatchIn(line)) {
                val separator = separatorRegex.findAll(line).lastOrNull()
                if (separator != null) {
                    val suffix = line.substring(separator.range.last + 1)
                    tokenRegex.findAll(suffix).forEach { match ->
                        normalize(match.value)?.let(found::add)
                    }
                }
                return@forEach
            }

            // Also support codes printed alone without Telegram code formatting.
            if (tokenRegex.matches(line)) normalize(line)?.let(found::add)
        }
        return found.toList()
    }

    private fun normalize(raw: String): String? {
        val candidate = raw.trim().trim { !it.isLetterOrDigit() }
        if (candidate.isEmpty() || candidate.any(Char::isWhitespace)) return null
        if (!Regex("""[\p{L}\p{N}]{4,40}""").matches(candidate)) return null
        if (candidate != candidate.uppercase() && !candidate.any(Char::isDigit)) return null

        val normalized = candidate.uppercase()
        if (normalized in ignoredWords) return null
        if (normalized.contains("ПРОМОИЗПОСТА")) return null
        if (!normalized.any(Char::isLetter) && !numericRegex.matches(normalized)) return null
        return normalized
    }
}

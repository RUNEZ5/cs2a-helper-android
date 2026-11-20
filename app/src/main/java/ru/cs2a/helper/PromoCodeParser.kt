package ru.cs2a.helper

import java.util.Locale

/** Conservative parser: explicit code formatting or a price/case marker is preferred. */
object PromoCodeParser {
    private val tokenRegex = Regex("""(?<![\p{L}\p{N}])[\p{L}\p{N}]{3,40}(?![\p{L}\p{N}])""")
    private val candidateRegex = Regex("""[\p{L}\p{N}]{4,40}""")
    private val numericRegex = Regex("""\d{8,32}""")
    private val priceRegex = Regex(
        """(?iu)(?:\d+(?:[.,]\d+)?\s*(?:\$|€|₽|руб(?:\.|лей)?|usd)|(?:\$|€|₽)\s*\d+(?:[.,]\d+)?)"""
    )
    private val caseRegex = Regex("""(?iu)\b(?:КЕЙС|CASE)\b""")
    private val separatorRegex = Regex("""[—–:-]""")
    private val urlRegex = Regex("""(?iu)(?:https?://|www\.)[^\s]+|\bt\.me/[^\s]+""")

    private val ignoredWords = setOf(
        "ПРОМО", "ПРОМОКОД", "ПРОМОЧАТ", "ПРОМОИЗПОСТА", "ИЗ", "ПОСТА",
        "ФРИБЕТ", "ФРИБЕТЫ", "ФРИБЕТА", "FREEBET", "FREEBETS", "FREE_BET", "FREE_BETS",
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
        """(?iu)(?:\+\s*)?\d{1,3}\s*%\s*(?:к\s*)?(?:депозит\w*|deposit\w*)|(?:депозит\w*|deposit\w*)[^\n]{0,40}(?:\+\s*)?\d{1,3}\s*%"""
    )

    fun isDepositRelated(message: String): Boolean = depositConditionRegex.containsMatchIn(message)
    fun isValidCandidate(raw: String): Boolean = normalize(raw) != null
    fun extract(message: String): List<String> = extract(message, emptyList())

    fun extract(message: String, entityTexts: List<String>): List<String> {
        if (message.isBlank() || isDepositRelated(message)) return emptyList()

        val explicit = LinkedHashSet<String>()
        entityTexts.forEach { raw -> normalize(raw, allowThreeChar = true)?.let(explicit::add) }
        if (explicit.isNotEmpty()) return explicit.toList()

        val markdownCodes = LinkedHashSet<String>()
        Regex("[\\x60]([^\\x60\\n]{1,80})[\\x60]").findAll(message).forEach { match ->
            val codeText = match.groupValues[1]
            if (!urlRegex.containsMatchIn(codeText)) normalize(codeText, allowThreeChar = true)?.let(markdownCodes::add)
        }
        if (markdownCodes.isNotEmpty()) return markdownCodes.toList()

        val found = LinkedHashSet<String>()
        message.lineSequence().forEach { originalLine ->
            val line = originalLine.trim()
            if (line.isBlank()) return@forEach
            val upper = line.uppercase(Locale.ROOT)
            if (ignoredPhrases.any { upper.contains(it) }) return@forEach

            // Strip URLs first: domains and usernames inside links are not promo codes.
            val searchableLine = urlRegex.replace(line, " ")
            if (searchableLine.isBlank()) return@forEach

            val separator = separatorRegex.findAll(searchableLine).lastOrNull()
            val hasPriceOrCase = priceRegex.containsMatchIn(searchableLine) || caseRegex.containsMatchIn(searchableLine)
            // Some channel posts format the amount inconsistently (for example "0.5$ / 40P — CODE").
            // A numeric price prefix plus a dash is enough to inspect only the suffix for a code.
            val hasNumericPrefix = separator != null &&
                searchableLine.substring(0, separator.range.first).any(Char::isDigit)
            if (hasPriceOrCase || hasNumericPrefix) {
                if (separator != null) {
                    val suffix = searchableLine.substring(separator.range.last + 1)
                    tokenRegex.findAll(suffix).forEach { match -> normalize(match.value, allowThreeChar = true)?.let(found::add) }
                }
                return@forEach
            }

            // Do not classify ordinary lowercase prose as a standalone code.
            if (candidateRegex.matches(searchableLine)) {
                val hasDigit = searchableLine.any(Char::isDigit)
                val hasUppercase = searchableLine.any { it.isLetter() && it.isUpperCase() }
                if (hasDigit || hasUppercase) normalize(searchableLine, allowThreeChar = true)?.let(found::add)
            }
        }
        return found.toList()
    }

    private fun normalize(raw: String, allowThreeChar: Boolean = false): String? {
        val candidate = raw.trim().trim { !it.isLetterOrDigit() }
        if (candidate.isEmpty() || candidate.any(Char::isWhitespace)) return null
        val validShape = if (allowThreeChar) tokenRegex.matches(candidate) else candidateRegex.matches(candidate)
        if (!validShape) return null
        val normalized = candidate.uppercase(Locale.ROOT)
        if (normalized in ignoredWords) return null
        if (normalized.startsWith("ПРОМО") || normalized.contains("PROMO")) return null
        if (!normalized.any(Char::isLetter) && !numericRegex.matches(normalized)) return null
        return normalized
    }
}

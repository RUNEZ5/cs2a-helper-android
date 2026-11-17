package ru.cs2a.helper

import java.util.Locale

/**
 * Context-aware Telegram promo parser.
 * Explicit Telegram code entities and inline-code spans have priority. For plain text,
 * a candidate is accepted only when it follows a price or a recognizable promo label.
 */
object PromoCodeParser {
    private val tokenRegex = Regex("""(?<![\p{L}\p{N}])[\p{L}\p{N}]{4,40}(?![\p{L}\p{N}])""")
    private val candidateRegex = Regex("""[\p{L}\p{N}]{4,40}""")
    private val numericRegex = Regex("""\d{8,32}""")
    private val priceRegex = Regex(
        """(?iu)(?:\d+(?:[.,]\d+)?\s*(?:\$|€|₽|руб(?:\.|лей)?|usd)|(?:\$|€|₽)\s*\d+(?:[.,]\d+)?)"""
    )
    private val promoContextRegex = Regex(
        """(?iu)(?:\bКЕЙС\b|\bCASE\b|КОЛЕСО|ПРОМО\s*(?:НА|ТАЙМ|ЧАТ)?|PROMO|FREEBET|ФРИБЕТ|LEVEL|\bLVL\d*)"""
    )
    private val separatorRegex = Regex("""[—–:-]""")

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

    fun isDepositRelated(message: String): Boolean =
        depositConditionRegex.containsMatchIn(message)

    fun isValidCandidate(raw: String): Boolean = normalize(raw) != null

    fun extract(message: String): List<String> = extract(message, emptyList())

    fun extract(message: String, entityTexts: List<String>): List<String> {
        if (message.isBlank() || isDepositRelated(message)) return emptyList()

        val explicit = LinkedHashSet<String>()
        entityTexts.forEach { raw -> normalize(raw)?.let(explicit::add) }
        if (explicit.isNotEmpty()) return explicit.toList()

        val markdownCodes = LinkedHashSet<String>()
        Regex("\`([^\`\n]{1,80})\`").findAll(message).forEach { match ->
            normalize(match.groupValues[1])?.let(markdownCodes::add)
        }
        if (markdownCodes.isNotEmpty()) return markdownCodes.toList()

        val found = LinkedHashSet<String>()
        message.lineSequence().forEach { originalLine ->
            val line = originalLine.trim()
            if (line.isBlank()) return@forEach
            val upper = line.uppercase(Locale.ROOT)
            if (ignoredPhrases.any { upper.contains(it) }) return@forEach
            if (upper.contains("TELEGRAM-BOT") || upper.contains("T.ME/") || upper.contains("HTTPS://")) {
                return@forEach
            }

            val hasPrice = priceRegex.containsMatchIn(line)
            val hasPromoContext = promoContextRegex.containsMatchIn(line)
            if (hasPrice || hasPromoContext) {
                val separator = separatorRegex.findAll(line).lastOrNull()
                if (separator != null) {
                    val suffix = line.substring(separator.range.last + 1)
                    tokenRegex.findAll(suffix).forEach { match ->
                        normalize(match.value)?.let(found::add)
                    }
                }
                return@forEach
            }

            if (candidateRegex.matches(line)) {
                val hasDigit = line.any(Char::isDigit)
                val hasUppercase = line.any { it.isLetter() && it.isUpperCase() }
                if (hasDigit || hasUppercase) normalize(line)?.let(found::add)
            }
        }
        return found.toList()
    }

    private fun normalize(raw: String): String? {
        val candidate = raw.trim().trim { !it.isLetterOrDigit() }
        if (candidate.isEmpty() || candidate.any(Char::isWhitespace)) return null
        if (!candidateRegex.matches(candidate)) return null

        val normalized = candidate.uppercase(Locale.ROOT)
        if (normalized in ignoredWords) return null
        if (normalized.startsWith("ПРОМО") || normalized.contains("PROMO")) return null
        if (!normalized.any(Char::isLetter) && !numericRegex.matches(normalized)) return null
        return normalized
    }
}

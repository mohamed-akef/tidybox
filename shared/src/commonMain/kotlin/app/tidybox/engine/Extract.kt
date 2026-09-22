package app.tidybox.engine

import kotlinx.serialization.Serializable

enum class TxType {
    PURCHASE, REFUND, TRANSFER_IN, TRANSFER_OUT, TRANSFER_SELF, SALARY, DEPOSIT, ATM_OUT, BILL, GOV, INVESTMENT
}

/**
 * Why a message did not become a transaction. Never money. NO_TEMPLATE / NO_AMOUNT mean the
 * engine did not understand the wording; the others mean it did and chose not to.
 */
enum class Rejection { OTP, DECLINED, AUTH_HOLD, INFORMATIONAL, NO_TEMPLATE, NO_AMOUNT }

data class LocalDateTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int) {
    val isValid get() = month in 1..12 && day in 1..31 && hour in 0..23 && minute in 0..59
}

data class Transaction(
    val type: TxType,
    val amount: Double,
    val currency: String,
    val merchant: String?,
    val cardLast4: String?,
    val occurredAt: LocalDateTime?,
    /** FX purchases carry two amounts. This is what left the account, in the account currency. */
    val settled: Pair<Double, String>?,
)

sealed interface EngineResult {
    data class Parsed(val tx: Transaction) : EngineResult
    data class Rejected(val why: Rejection) : EngineResult
}

// ---------------------------------------------------------------- templates (data)

/**
 * The regex half of a rule pack (D3): every per-bank pattern. `{CUR}` and `{NUM}` inside a
 * pattern expand to [currency] and [number]. Extract.kt keeps only the algorithm, so a new
 * bank is a pack edit, not a release. Source of truth: `rulepacks/merchants.json`.
 */
@Serializable
data class Templates(
    val currency: String,
    val number: String,
    val defaultCurrency: String = "SAR",
    /** Spelling → ISO code (`SR` → `SAR`, `جم` → `EGP`). Compared upper-cased. */
    val currencyAliases: Map<String, String> = emptyMap(),
    /** A first line matching this is skipped when picking the line the type rules read. */
    val greeting: String = "(?!)",
    /** Ordered; first hit wins. `type` is a [TxType] name or `TRANSFER_BY_SENDER`; `reject` a [Rejection] name. */
    val types: List<TypeRule>,
    /** `TRANSFER_BY_SENDER`: incoming when a named sender line matches and no numeric one does. */
    val transferNamedSender: String = "(?!)",
    val transferNumericSender: String = "(?!)",
    /** Ordered; first hit in the text is the transaction amount. */
    val amount: List<AmountRule>,
    /** FX settlement: group 1 amount, group 2 currency. */
    val settled: String? = null,
    /** Types that carry a merchant / counterparty. */
    val merchantTypes: List<String> = emptyList(),
    val merchant: List<MerchantRule> = emptyList(),
    /** A merchant capture matching this is discarded (account numbers, masked digits). */
    val notMerchant: String = "(?!)",
    val card: List<Rule> = emptyList(),
    val dates: List<DateRule> = emptyList(),
) {
    /** `flags`: `i` ignore case, `m` multiline. */
    @Serializable data class Rule(val re: String, val flags: String = "")
    /** `scope`: `first` = first non-greeting line, `whole` = the full text. */
    @Serializable data class TypeRule(val re: String, val type: String? = null, val reject: String? = null, val scope: String = "first", val flags: String = "")
    /** `currencyFirst`: group order is (currency, number) instead of (number, currency). */
    @Serializable data class AmountRule(val re: String, val currencyFirst: Boolean = false, val flags: String = "")
    /** `scope`: `line` = a whole line must match, `whole` = first find in the text. */
    @Serializable data class MerchantRule(val re: String, val scope: String = "line", val flags: String = "")
    /** `order`: one letter per capture group — Y year, y two-digit year, M, D, h, m. */
    @Serializable data class DateRule(val re: String, val order: String)
}

// ---------------------------------------------------------------- templates (compiled)

private const val TRANSFER_BY_SENDER = "TRANSFER_BY_SENDER"

private fun opts(flags: String) = buildSet {
    if ('i' in flags) add(RegexOption.IGNORE_CASE)
    if ('m' in flags) add(RegexOption.MULTILINE)
}

/** Compiled once per pack. Constructing it validates every pattern and every enum name. */
internal class Engine(private val t: Templates) {
    private fun rx(re: String, flags: String = "") = Regex(re.replace("{CUR}", t.currency).replace("{NUM}", t.number), opts(flags))

    private val greeting = rx(t.greeting)
    private val types = t.types.map { r ->
        val type = r.type?.let { if (it == TRANSFER_BY_SENDER) null else TxType.valueOf(it) }
        val reject = r.reject?.let(Rejection::valueOf)
        require(r.scope == "first" || r.scope == "whole") { "type rule scope must be first|whole: ${r.scope}" }
        require((r.type != null) != (r.reject != null)) { "type rule needs exactly one of type/reject: ${r.re}" }
        Compiled(rx(r.re, r.flags), type, reject, whole = r.scope == "whole", bySender = r.type == TRANSFER_BY_SENDER)
    }
    private val namedSender = rx(t.transferNamedSender, "m")
    private val numericSender = rx(t.transferNumericSender, "m")
    private val amount = t.amount.map { rx(it.re, it.flags) to it.currencyFirst }
    private val settled = t.settled?.let { rx(it) }
    private val merchantTypes = t.merchantTypes.map(TxType::valueOf).toSet()
    private val merchant = t.merchant.map { r ->
        require(r.scope == "line" || r.scope == "whole") { "merchant rule scope must be line|whole: ${r.scope}" }
        rx(r.re, r.flags) to (r.scope == "line")
    }
    private val notMerchant = rx(t.notMerchant)
    private val card = t.card.map { rx(it.re, it.flags) }
    private val dates = t.dates.map { r ->
        require(r.order.all { it in "YyMDhm" }) { "date order letters must be YyMDhm: ${r.order}" }
        rx(r.re) to r.order
    }
    private val aliases = t.currencyAliases.mapKeys { it.key.uppercase() }
    private val defaultCurrency = t.defaultCurrency

    private class Compiled(val re: Regex, val type: TxType?, val reject: Rejection?, val whole: Boolean, val bySender: Boolean)

    private fun canonCurrency(c: String?): String {
        if (c == null) return defaultCurrency // ponytail: no-currency default is per pack, not per bank
        val u = c.uppercase()
        return aliases[u] ?: u
    }

    private fun parseDate(t: String): LocalDateTime? {
        for ((re, order) in dates) {
            val m = re.find(t) ?: continue
            val g = m.groupValues.drop(1).map { it.toInt() }
            fun at(letter: Char) = g[order.indexOf(letter)]
            val year = if ('Y' in order) at('Y') else 2000 + at('y')
            val d = LocalDateTime(year, at('M'), at('D'), at('h'), at('m'))
            if (d.isValid) return d
        }
        return null
    }

    fun extract(raw: String): EngineResult {
        val t = normalize(raw)
        val lines = t.split('\n')
        val first = lines.firstOrNull { !greeting.containsMatchIn(it) } ?: lines.first()

        var type: TxType? = null
        var bySender = false
        for (r in types) {
            if (!r.re.containsMatchIn(if (r.whole) t else first)) continue
            if (r.reject != null) return EngineResult.Rejected(r.reject)
            if (r.bySender) bySender = true else type = r.type
            break
        }
        if (bySender) {
            type = if (namedSender.containsMatchIn(t) && !numericSender.containsMatchIn(t)) TxType.TRANSFER_IN else TxType.TRANSFER_OUT
        }
        if (type == null) return EngineResult.Rejected(Rejection.NO_TEMPLATE)

        var amountValue: Double? = null
        var currency: String? = null
        for ((re, curFirst) in amount) {
            val m = re.find(t) ?: continue
            val g = m.groupValues.drop(1)
            val num = if (curFirst) g[1] else g[0]
            currency = if (g.size > 1) (if (curFirst) g[0] else g[1]) else null
            amountValue = num.replace(",", "").toDouble()
            break
        }
        if (amountValue == null) return EngineResult.Rejected(Rejection.NO_AMOUNT)

        var merchantName: String? = null
        if (type in merchantTypes) {
            outer@ for ((re, perLine) in merchant) {
                if (perLine) {
                    for (ln in lines) {
                        val v = re.matchEntire(ln)?.groupValues?.get(1) ?: continue
                        if (notMerchant.matches(v)) continue
                        merchantName = v.trim(' ', '.', ';', ':')
                        break@outer
                    }
                } else {
                    val v = re.find(t)?.groupValues?.get(1) ?: continue
                    if (notMerchant.matches(v)) continue
                    merchantName = v.trim(' ', '.', ';', ':')
                    break@outer
                }
            }
        }
        val settledValue = settled?.find(t)?.let { it.groupValues[1].toDouble() to canonCurrency(it.groupValues[2]) }
        return EngineResult.Parsed(
            Transaction(
                type = type,
                amount = amountValue,
                currency = canonCurrency(currency),
                merchant = merchantName,
                cardLast4 = card.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1) },
                occurredAt = parseDate(t),
                settled = settledValue,
            )
        )
    }
}

/** The engine's one entry point. Pure: same text and pack in, same result out, no clock, no I/O. */
fun extract(raw: String, pack: RulePack = RulePack.bundled): EngineResult = pack.engine.extract(raw)

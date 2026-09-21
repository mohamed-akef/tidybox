package co.raseed.engine

enum class TxType {
    PURCHASE, REFUND, TRANSFER_IN, TRANSFER_OUT, TRANSFER_SELF, SALARY, DEPOSIT, ATM_OUT, BILL, GOV, INVESTMENT
}

/** Why a message did not become a transaction. Surfaced in the UI as "informational", never as money. */
enum class Rejection { OTP, DECLINED, AUTH_HOLD, NO_TEMPLATE, NO_AMOUNT }

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

// ---------------------------------------------------------------- templates
// ponytail: templates live in Kotlin for the first slice. Externalizing them to the signed
// rule pack (D3) is its own PR; the shapes below are what that JSON will describe.

private const val CUR = "(?:SAR|SR|USD|AED|MKD|EUR|GBP|ريال سعودي|ريال)"
private const val NUM = "(\\d[\\d,]*(?:\\.\\d+)?)"

/** (regex, currency-group-first). Original amount; FX settlement is captured separately. */
private val AMOUNT = listOf(
    Regex("(?:المبلغ|مبلغ|بمبلغ|بقيمة)\\s*:?\\s*($CUR)\\s*$NUM") to true,
    Regex("(?:المبلغ|مبلغ|بمبلغ|بقيمة)\\s*:?\\s*$NUM\\s*($CUR)") to false,
    Regex("(?<!\\S)بـ?\\s*($CUR)\\s*$NUM") to true,
    Regex("(?<!\\S)بـ?\\s*$NUM\\s*($CUR)") to false,
    Regex("(?:شراء انترنت|شراء إنترنت|شراء)\\s+$NUM\\s*($CUR)") to false,
    Regex("بسعر\\s*$NUM\\s*($CUR)") to false,
    Regex("(?:اضافة|إضافة)\\s+$NUM\\s*($CUR)") to false,
    Regex("(?:المبلغ|مبلغ|بمبلغ)\\s*:?\\s*$NUM(?!\\S)") to false, // no currency at all
)
private val SETTLED = Regex("(?:اجمالي|إجمالي) المبلغ المستحق\\s*:?\\s*$NUM\\s*($CUR)")

/** Ordered. First hit on the first non-greeting line wins; some scan the whole text. */
private val TYPE_RULES: List<Triple<Regex, TxType?, Rejection?>> = listOf(
    Triple(Regex("مرفوض|لا يكفي|لم يتم تنفيذ|عدم وجود رصيد"), null, Rejection.DECLINED),
    Triple(Regex("رمز|كلمة مرور|التفعيل|التحقق"), null, Rejection.OTP),
    Triple(Regex("^تفويض"), null, Rejection.AUTH_HOLD),
    Triple(Regex("استرداد|عكسية|استرجاع"), TxType.REFUND, null),
    Triple(Regex("راتب"), TxType.SALARY, null),
    Triple(Regex("سحب"), TxType.ATM_OUT, null),
    Triple(Regex("سداد فاتورة|مدفوعات سوا"), TxType.BILL, null),
    Triple(Regex("مدفوعات وزارة|الجهة:"), TxType.GOV, null),
    Triple(Regex("تنفيذ عملية شراء شركة|سهم"), TxType.INVESTMENT, null),
    Triple(Regex("حوالة بين حساباتك"), TxType.TRANSFER_SELF, null),
    Triple(Regex("حوالة.*(وارد|واردة)|وارد.*حوالة|استلام حوالة"), TxType.TRANSFER_IN, null),
    Triple(Regex("حوالة.*(صادر|صادرة)"), TxType.TRANSFER_OUT, null),
    Triple(Regex("حوالة"), null, null), // direction decided by who is named — see below
    Triple(Regex("ايداع|إيداع|قيد مبلغ"), TxType.DEPOSIT, null),
    Triple(Regex("شراء|مشتريات|خصم من التفويض|عملية شراء"), TxType.PURCHASE, null),
)
private val WHOLE_TEXT_RULES = setOf(Rejection.DECLINED, Rejection.OTP)
private val GREETING = Regex("^(هلا|عميلنا العزيز|عزيزي|عزيزنا)")
private val NAMED_SENDER = Regex("^من\\s*:\\s*\\S", RegexOption.MULTILINE)
private val NUMERIC_SENDER = Regex("^من\\s*:?\\s*\\d{4}$", RegexOption.MULTILINE)

/** Priority order. `من:` is sender, own account, or merchant depending on the bank — hence the filter. */
private val MERCHANT = listOf(
    Regex("^لدي\\s*:?\\s*(.+)$"),
    Regex("^من البائع\\s*:?\\s*(.+)$"),
    Regex("^الخدمة\\s*:?\\s*(.+)$"),
    Regex("^الجهة\\s*:?\\s*(.+)$"),
    Regex("^مكان السحب\\s*:?\\s*(.+)$"),
    Regex("^لـ(.+)$"),
    Regex("^من\\s*:?\\s*(.+)$"),
)
private val NOT_MERCHANT = Regex("^[\\s:]*(?:[\\d*x#]+|حساب.*|البائع.*)$")
private val MERCHANT_TYPES = setOf(TxType.PURCHASE, TxType.REFUND, TxType.ATM_OUT, TxType.BILL, TxType.GOV)

private val CARD = Regex("(?:بطاقة|البطاقة|عبر|ببطاقة مدي|بطاقة مدي|لبطاقة مدي)[^\\d\\n]*?(\\d{4})")

private val DATES: List<Pair<Regex, (List<Int>) -> LocalDateTime>> = listOf(
    Regex("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T]+(\\d{1,2}):(\\d{2})") to { g -> LocalDateTime(g[0], g[1], g[2], g[3], g[4]) },
    Regex("(\\d{1,2}):(\\d{2})\\s+(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})") to { g -> LocalDateTime(g[2], g[3], g[4], g[0], g[1]) },
    Regex("(\\d{1,2}):(\\d{2})\\s+(\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})") to { g -> LocalDateTime(2000 + g[2], g[3], g[4], g[0], g[1]) },
    Regex("(\\d{2})[-/\\\\](\\d{1,2})[-/\\\\](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})") to { g -> LocalDateTime(2000 + g[0], g[1], g[2], g[3], g[4]) },
    // ponytail: d/m/yy vs yy-m-d is ambiguous for day<=12; per-bank date format belongs in the rule pack
    Regex("(\\d{1,2})[-/\\\\](\\d{1,2})[-/\\\\](\\d{2})\\s+(\\d{1,2}):(\\d{2})") to { g -> LocalDateTime(2000 + g[2], g[1], g[0], g[3], g[4]) },
)

private fun parseDate(t: String): LocalDateTime? {
    for ((re, build) in DATES) {
        val m = re.find(t) ?: continue
        val d = build(m.groupValues.drop(1).map { it.toInt() })
        if (d.isValid) return d
    }
    return null
}

private fun canonCurrency(c: String?) = when (c) {
    null, "SR", "ريال", "ريال سعودي" -> "SAR"
    else -> c
}

/** The engine's one entry point. Pure: same text in, same result out, no clock, no I/O. */
fun extract(raw: String): EngineResult {
    val t = normalize(raw)
    val lines = t.split('\n')
    val first = lines.firstOrNull { !GREETING.containsMatchIn(it) } ?: lines.first()

    var type: TxType? = null
    var undecidedTransfer = false
    for ((re, tx, rej) in TYPE_RULES) {
        val hit = re.containsMatchIn(first) || (rej in WHOLE_TEXT_RULES && re.containsMatchIn(t)) ||
            (tx == TxType.INVESTMENT && re.containsMatchIn(t))
        if (!hit) continue
        if (rej != null) return EngineResult.Rejected(rej)
        if (tx == null) undecidedTransfer = true else type = tx
        break
    }
    if (undecidedTransfer) {
        type = if (NAMED_SENDER.containsMatchIn(t) && !NUMERIC_SENDER.containsMatchIn(t)) TxType.TRANSFER_IN else TxType.TRANSFER_OUT
    }
    if (type == null) return EngineResult.Rejected(Rejection.NO_TEMPLATE)

    var amount: Double? = null
    var currency: String? = null
    for ((re, curFirst) in AMOUNT) {
        val m = re.find(t) ?: continue
        val g = m.groupValues.drop(1)
        val num = if (curFirst) g[1] else g[0]
        currency = if (g.size > 1) (if (curFirst) g[0] else g[1]) else null
        amount = num.replace(",", "").toDouble()
        break
    }
    if (amount == null) return EngineResult.Rejected(Rejection.NO_AMOUNT)

    var merchant: String? = null
    if (type in MERCHANT_TYPES) {
        outer@ for (re in MERCHANT) {
            for (ln in lines) {
                val m = re.matchEntire(ln) ?: continue
                val v = m.groupValues[1]
                if (NOT_MERCHANT.matches(v)) continue
                merchant = v.trim(' ', '.', ';', ':')
                break@outer
            }
        }
    }
    val settled = SETTLED.find(t)?.let { it.groupValues[1].toDouble() to canonCurrency(it.groupValues[2]) }
    return EngineResult.Parsed(
        Transaction(
            type = type,
            amount = amount,
            currency = canonCurrency(currency),
            merchant = merchant,
            cardLast4 = CARD.find(t)?.groupValues?.get(1),
            occurredAt = parseDate(t),
            settled = settled,
        )
    )
}

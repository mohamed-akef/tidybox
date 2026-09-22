package app.tidybox.engine

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

private const val CUR = "(?:SAR|SR|USD|AED|MKD|EUR|GBP|EGP|KWD|QAR|BHD|OMR|JOD|INR|TRY|L\\.?E\\.?|ريال سعودي|ريال|جنيه مصري|جنيه|جم)"
private const val NUM = "(\\d[\\d,]*(?:\\.\\d+)?|\\.\\d+)"

/** (regex, currency-group-first). Original amount; FX settlement is captured separately. */
private val AMOUNT = listOf(
    Regex("(?:المبلغ|مبلغ|بمبلغ|بقيمة)\\s*:?\\s*($CUR)\\s*$NUM") to true,
    Regex("(?:المبلغ|مبلغ|بمبلغ|بقيمة)\\s*:?\\s*$NUM\\s*($CUR)") to false,
    Regex("(?<!\\S)بـ?\\s*($CUR)\\s*$NUM") to true,
    Regex("(?<!\\S)بـ?\\s*$NUM\\s*($CUR)") to false,
    Regex("(?:شراء انترنت|شراء إنترنت|شراء)\\s+$NUM\\s*($CUR)") to false,
    Regex("بسعر\\s*$NUM\\s*($CUR)") to false,
    Regex("(?:اضافة|إضافة)\\s+$NUM\\s*($CUR)") to false,
    Regex("خصم\\s+$NUM\\s*($CUR)") to false,
    // English, any bank: "for EGP 118.00", "with USD 15.99", "of 50 EGP". First amount in the
    // text is the transaction; balances and limits come later in every format seen so far.
    Regex("\\b($CUR)\\s*$NUM", RegexOption.IGNORE_CASE) to true,
    Regex("$NUM\\s*($CUR)\\b", RegexOption.IGNORE_CASE) to false,
    Regex("(?:المبلغ|مبلغ|بمبلغ)\\s*:?\\s*$NUM(?!\\S)") to false, // no currency at all
)
private val SETTLED = Regex("(?:اجمالي|إجمالي) المبلغ المستحق\\s*:?\\s*$NUM\\s*($CUR)")

/** Ordered. First hit on the first non-greeting line wins; some scan the whole text. */
private val TYPE_RULES: List<Triple<Regex, TxType?, Rejection?>> = listOf(
    Triple(Regex("مرفوض|لا يكفي|لم يتم تنفيذ|عدم وجود رصيد"), null, Rejection.DECLINED),
    Triple(Regex("رمز|كلمة مرور|التفعيل|التحقق|الرقم السري"), null, Rejection.OTP),
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
    Triple(Regex("تم (?:إضافة|اضافة) تحويل"), TxType.TRANSFER_IN, null),
    Triple(Regex("ايداع|إيداع|قيد مبلغ|تم (?:إضافة|اضافة)"), TxType.DEPOSIT, null),
    Triple(Regex("تم خصم"), TxType.PURCHASE, null),
    Triple(Regex("شراء|مشتريات|خصم من التفويض|عملية شراء"), TxType.PURCHASE, null),
)
private val WHOLE_TEXT_RULES = setOf(Rejection.DECLINED, Rejection.OTP)

/** English shapes, any bank; whole text, case-insensitive. Sources: CIB Egypt, generic wording. */
private val EN_RULES: List<Triple<Regex, TxType?, Rejection?>> = listOf(
    Regex("declined|insufficient|unsuccessful|could not be (?:processed|completed)|has failed", RegexOption.IGNORE_CASE) to null to Rejection.DECLINED,
    Regex("\\bOTP\\b|one[- ]time (?:password|code)|verification code|passcode|security code", RegexOption.IGNORE_CASE) to null to Rejection.OTP,
    // Promos quote amounts ("min purchase EGP 500"); nothing moved. Before every money rule.
    Regex("cashback|discount|% ?off|\\boffer\\b|terms and conditions|\\benjoy\\b|promo", RegexOption.IGNORE_CASE) to null to Rejection.NO_TEMPLATE,
    Regex("refund|revers(?:ed|al)", RegexOption.IGNORE_CASE) to TxType.REFUND to null,
    Regex("salary|payroll", RegexOption.IGNORE_CASE) to TxType.SALARY to null,
    Regex("\\bATM\\b|cash withdrawal|withdrawn", RegexOption.IGNORE_CASE) to TxType.ATM_OUT to null,
    Regex("bill payment|paid (?:your|the) bill|recharged?\\b|top[- ]?up", RegexOption.IGNORE_CASE) to TxType.BILL to null,
    Regex("received .{0,40}from|credited .{0,40}(?:from|by)|incoming transfer|transfer .{0,30}(?:received|credited)", RegexOption.IGNORE_CASE) to TxType.TRANSFER_IN to null,
    Regex("(?:sent|transferred) .{0,40}\\bto\\b|outgoing transfer", RegexOption.IGNORE_CASE) to TxType.TRANSFER_OUT to null,
    Regex("deposit|credited", RegexOption.IGNORE_CASE) to TxType.DEPOSIT to null,
    Regex("was charged|charged for|purchase|debited|\\bspent\\b|\\bpaid\\b|(?:was|has been) used", RegexOption.IGNORE_CASE) to TxType.PURCHASE to null,
).map { (a, b) -> Triple(a.first, a.second, b) }
private val EN_MERCHANT = listOf(
    Regex("\\bat\\s+(.+?)\\s+on\\s+\\d", RegexOption.IGNORE_CASE),
    Regex("\\bfrom\\s+(.+?)\\s+with\\s+$CUR", RegexOption.IGNORE_CASE),
    Regex("\\bat\\s+(.+?)[.,]?$", RegexOption.IGNORE_CASE),
)
private val EG_MERCHANT = Regex("عند\\s*(.+?)\\s+يوم")
private val EN_CARD = Regex("(?:card|account)(?: ending)?(?: with| in)?\\s*#?\\s*(\\d{4})", RegexOption.IGNORE_CASE)
private val EG_CARD = Regex("رقم\\s*(\\d{4})")
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
    Regex("(\\d{1,2})/(\\d{1,2})/(\\d{2})\\s*الساعة\\s*(\\d{1,2}):(\\d{2})") to { g -> LocalDateTime(2000 + g[2], g[1], g[0], g[3], g[4]) }, // NBE: "يوم03/09/26 الساعة21:13"
    // ponytail: d/m/yy vs yy-m-d is ambiguous for day<=12; per-bank date format belongs in the rule pack
    Regex("(\\d{1,2})[-/\\\\](\\d{1,2})[-/\\\\](\\d{2})\\s+(?:at\\s+)?(\\d{1,2}):(\\d{2})") to { g -> LocalDateTime(2000 + g[2], g[1], g[0], g[3], g[4]) },
)

private fun parseDate(t: String): LocalDateTime? {
    for ((re, build) in DATES) {
        val m = re.find(t) ?: continue
        val d = build(m.groupValues.drop(1).map { it.toInt() })
        if (d.isValid) return d
    }
    return null
}

private fun canonCurrency(c: String?) = when (c?.uppercase()) {
    null, "SR", "ريال", "ريال سعودي" -> "SAR" // ponytail: no-currency default is Saudi; per-bank default belongs in the rule pack
    "جم", "جنيه", "جنيه مصري", "LE", "L.E", "L.E." -> "EGP"
    else -> c.uppercase()
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
    if (type == null) for ((re, tx, rej) in EN_RULES) {
        if (!re.containsMatchIn(t)) continue
        if (rej != null) return EngineResult.Rejected(rej)
        type = tx
        break
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
        if (merchant == null) merchant = (EN_MERCHANT.asSequence().mapNotNull { it.find(t)?.groupValues?.get(1) }.firstOrNull() ?: EG_MERCHANT.find(t)?.groupValues?.get(1))
            ?.trim(' ', '.', ';', ':')?.takeUnless { NOT_MERCHANT.matches(it) }
    }
    val settled = SETTLED.find(t)?.let { it.groupValues[1].toDouble() to canonCurrency(it.groupValues[2]) }
    return EngineResult.Parsed(
        Transaction(
            type = type,
            amount = amount,
            currency = canonCurrency(currency),
            merchant = merchant,
            cardLast4 = (CARD.find(t) ?: EN_CARD.find(t) ?: EG_CARD.find(t))?.groupValues?.get(1),
            occurredAt = parseDate(t),
            settled = settled,
        )
    )
}

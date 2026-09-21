"""Throwaway spike. Measures M1/M2/M3 from docs/spike/README.md on spike/corpus.json.

Run:  .venv/bin/python spike.py
Everything here is disposable; only the numbers matter.
"""
import hashlib
import json
import re
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime

# ---------------------------------------------------------------- truth labels
# (type, amount, currency, merchant-or-None, category)
# category "?" = information is NOT in the string; abstaining is the only correct answer.
# Types: purchase refund transfer_in transfer_out salary deposit atm_out bill gov investment
T = {
    0: ("purchase", 5.00, "SAR", "BK - Kaust", "?"),
    6: ("transfer_in", 300, "SAR", None, "Transfer"),
    9: ("transfer_out", 300, "SAR", None, "Transfer"),
    10: ("transfer_in", 50, "SAR", None, "Transfer"),
    11: ("purchase", 8, "SAR", "Sada AlHadel", "?"),
    13: ("purchase", 65, "SAR", "ABDULAH MOHD", "?"),
    14: ("transfer_in", 350, "SAR", None, "Transfer"),
    15: ("transfer_out", 100, "SAR", None, "Transfer"),
    16: ("transfer_in", 100, "SAR", None, "Transfer"),
    17: ("purchase", 5.50, "SAR", "HAYA ALBEISHI", "?"),
    18: ("transfer_in", 400, "SAR", None, "Transfer"),
    21: ("purchase", 23, "SAR", "Mobily", "Telecom"),
    22: ("purchase", 1.00, "USD", "DIGITALOCEAN.COM", "Software"),
    28: ("bill", 230, "SAR", "موبايلي", "Telecom"),
    30: ("bill", 23, "SAR", None, "Telecom"),
    31: ("transfer_in", 100, "SAR", None, "Transfer"),
    32: ("purchase", 10, "SAR", "ALAMAL ALGADEM", "?"),
    33: ("refund", 92, "SAR", "HAWSABAH COMPAN", "?"),
    34: ("purchase", 41.31, "SAR", "BRANCH OF MODER", "?"),
    35: ("transfer_in", 150, "SAR", None, "Transfer"),
    36: ("purchase", 26.35, "SAR", "E206 Tamimi", "Groceries"),
    37: ("deposit", 513, "SAR", None, "Income"),
    38: ("purchase", 9, "SAR", "THATI LIMITED", "?"),
    39: ("purchase", 3.50, "SAR", "THATI LIM", "?"),
    41: ("purchase", 98.50, "SAR", "Bss-ballot", "?"),
    42: ("purchase", 120.00, "MKD", "MARKET JOVCE-1", "Groceries"),
    44: ("transfer_out", 100, "SAR", None, "Transfer"),
    46: ("purchase", 28.75, "SAR", "Mobily", "Telecom"),
    47: ("deposit", 303, "SAR", None, "Income"),
    48: ("atm_out", 300, "SAR", None, "Cash"),
    49: ("deposit", 2000, "SAR", None, "Income"),
    50: ("purchase", 41.40, "SAR", "The World", "?"),
    53: ("salary", 7000, "SAR", None, "Income"),
    54: ("bill", 100, "SAR", "STC PAY", "Transfer"),
    55: ("gov", 400, "SAR", "اصدار رخصة قيادة", "Government"),
    60: ("transfer_out", 15, "SAR", None, "Transfer"),
    62: ("purchase", 7.00, "AED", "DUBAI AIRPORT", "Travel"),
    63: ("purchase", 15.42, "SAR", "Careem", "Transport"),
    65: ("purchase", 1, "SAR", "Keeta", "Food"),
    66: ("refund", 1, "SAR", "Keeta", "Food"),
    67: ("transfer_in", 2000, "SAR", None, "Transfer"),
    68: ("transfer_out", 700, "SAR", None, "Transfer"),
    74: ("transfer_in", 1000, "SAR", None, "Transfer"),
    79: ("transfer_out", 1000, "SAR", None, "Transfer"),
    80: ("transfer_out", 1060, "SAR", None, "Transfer"),
    81: ("transfer_out", 1000, "SAR", None, "Transfer"),
    82: ("transfer_in", 100, "SAR", None, "Transfer"),
    85: ("deposit", 100, "SAR", None, "Income"),
    86: ("transfer_in", 200, "SAR", None, "Transfer"),
    87: ("purchase", 98, "SAR", "PETROLY C", "Fuel"),
    88: ("transfer_self", 100, "SAR", None, "Transfer"),
    90: ("transfer_in", 50, "SAR", None, "Transfer"),
    91: ("purchase", 115, "SAR", None, "?"),
    103: ("transfer_in", 400, "SAR", None, "Transfer"),
    104: ("purchase", 33, "SAR", "ALBAIK", "Food"),
    105: ("purchase", 7.50, "SAR", "mtam shtyrty ltqdym al", "Food"),
    109: ("transfer_out", 300, "SAR", None, "Transfer"),
    111: ("transfer_in", 700, "SAR", None, "Transfer"),
    112: ("salary", 7000, "SAR", None, "Income"),
    116: ("purchase", 1, "SAR", "Keeta", "Food"),
    117: ("refund", 1, "SAR", "Keeta", "Food"),
    118: ("purchase", 12, "SAR", "ALBAIK", "Food"),
    119: ("transfer_in", 1000, "SAR", None, "Transfer"),
    121: ("purchase", 1, "SAR", "Investment App", "Finance"),
    123: ("purchase", 47.80, "SAR", "ADVANCED TECHNOLOGY CO", "?"),
    124: ("purchase", 12.50, "SAR", "FAIRWAY", "?"),
    125: ("purchase", 57, "SAR", "NEBRAS ALJANOBEYA LAUN", "Services"),
    126: ("deposit", 1000.25, "SAR", None, "Income"),
    127: ("purchase", 86, "SAR", "Suhail Star cafe", "Food"),
    128: ("purchase", 16.45, "SAR", "Amazon Saudi Arabia", "Shopping"),
    129: ("purchase", 160, "SAR", "MAHTA MOSLM ALSAIRE", "Fuel"),
    130: ("purchase", 102, "SAR", "Mtaam Ns Sfry Ltqdym", "Food"),
    132: ("transfer_out", 165.00, "SAR", None, "Transfer"),
    135: ("salary", 7000, "SAR", None, "Income"),
    142: ("transfer_out", 100, "SAR", None, "Transfer"),
    143: ("transfer_in", 100, "SAR", None, "Transfer"),
    144: ("investment", 26.44, "SAR", None, "Finance"),
    146: ("transfer_in", 6000.00, "SAR", None, "Transfer"),
    147: ("purchase", 7.00, "SAR", "IKEA DHAHRAN", "Shopping"),
    148: ("refund", 32.00, "SAR", "HungerStation", "Food"),
    149: ("transfer_in", 990.00, "SAR", None, "Transfer"),
    151: ("purchase", 56.00, "SAR", "TANOOR ALTAHI REST×", "Food"),
    152: ("purchase", 126.28, "SAR", "AMAZON SA××AL Madin", "Shopping"),
    153: ("transfer_out", 200.00, "SAR", None, "Transfer"),
    154: ("transfer_in", 75.00, "SAR", None, "Transfer"),
    155: ("salary", 12345.67, "SAR", None, "Income"),
    156: ("purchase", 50, "SAR", "Establishment Name", "?"),
    157: ("purchase", 3, "SAR", "Meed Express", "?"),
    158: ("purchase", 125.50, "SAR", "Commercial Self-Technolog", "?"),
    159: ("purchase", 75.25, "SAR", "Establishment Name", "?"),
    160: ("transfer_out", 75, "SAR", None, "Transfer"),
    161: ("transfer_out", 10, "SAR", None, "Transfer"),
    162: ("transfer_in", 50, "SAR", None, "Transfer"),
    163: ("atm_out", 50, "SAR", None, "Cash"),
    164: ("purchase", 12.99, "SAR", "APPLE.COM/BILL", "Software"),
    165: ("purchase", 23.00, "USD", "CLAUDE.AI SUBSCRIPTION", "Software"),
    166: ("purchase", 3, "SAR", "ANWAR ALB", "?"),
    167: ("purchase", 17, "SAR", "Dar Alwjb", "?"),
    168: ("purchase", 15, "SAR", "MODAWAR S", "?"),
    169: ("purchase", 23, "USD", "ANTHROPIC", "Software"),
    170: ("refund", 7.59, "SAR", None, "Income"),
    171: ("refund", 215.00, "SAR", None, "Income"),
    172: ("purchase", 12.00, "SAR", "Some merchant I paid with a credit card", "?"),
    173: ("purchase", 34.00, "SAR", "Some restaurant I paid with a credit card", "Food"),
}
# Messages that mention money but must NOT become a transaction.
REJECT = {8, 19, 20, 26, 40, 43, 45, 59, 64, 108, 110, 115, 120, 131, 150}

# ---------------------------------------------------------------- normalize
BIDI = "‎‏؜‪‫‬‭‮⁦⁧⁨⁩"
AR_DIGITS = {ord(c): str(i) for i, c in enumerate("٠١٢٣٤٥٦٧٨٩")}
AR_DIGITS.update({ord(c): str(i) for i, c in enumerate("۰۱۲۳۴۵۶۷۸۹")})


def normalize(s):
    s = unicodedata.normalize("NFKC", s)  # folds presentation forms ﺣواﻟة -> حوالة
    s = s.translate(AR_DIGITS)
    s = "".join(ch for ch in s if ch not in BIDI and not (0x064B <= ord(ch) <= 0x0652))  # tatweel kept: بـ / لـ are template markers
    s = re.sub(r"[أإآٱ]", "ا", s).replace("ى", "ي")
    s = s.replace(" ", " ")
    return "\n".join(re.sub(r"[ \t]+", " ", ln).strip() for ln in s.split("\n") if ln.strip())


# ---------------------------------------------------------------- extract
CUR = r"(?:SAR|SR|USD|AED|MKD|EUR|GBP|ريال سعودي|ريال)"
NUM = r"(\d[\d,]*(?:\.\d+)?)"
AMOUNT_PATS = [  # original amount; FX settlement captured separately (SETTLED_PAT)
    rf"(?:المبلغ|مبلغ|بمبلغ|بقيمة)\s*:?\s*({CUR})\s*{NUM}",
    rf"(?:المبلغ|مبلغ|بمبلغ|بقيمة)\s*:?\s*{NUM}\s*({CUR})",
    rf"(?<!\S)بـ?\s*({CUR})\s*{NUM}",
    rf"(?<!\S)بـ?\s*{NUM}\s*({CUR})",
    rf"(?:شراء انترنت|شراء إنترنت|شراء)\s+{NUM}\s*({CUR})",
    rf"بسعر\s*{NUM}\s*({CUR})",
    rf"(?:اضافة|إضافة)\s+{NUM}\s*({CUR})",
    rf"(?:المبلغ|مبلغ|بمبلغ)\s*:?\s*{NUM}(?!\S)",  # amount with no currency at all
]
SETTLED_PAT = rf"(?:اجمالي|إجمالي) المبلغ المستحق\s*:?\s*{NUM}\s*({CUR})"
TYPE_RULES = [  # first line / whole text -> type. Order matters.
    (r"مرفوض|لا يكفي|لم يتم تنفيذ|عدم وجود رصيد", "declined"),
    (r"رمز|كلمة مرور|التفعيل|التحقق", "otp"),
    (r"^تفويض", "auth_hold"),
    (r"استرداد|عكسية|استرجاع", "refund"),
    (r"راتب", "salary"),
    (r"سحب", "atm_out"),
    (r"سداد فاتورة|مدفوعات سوا", "bill"),
    (r"مدفوعات وزارة|الجهة:", "gov"),
    (r"تنفيذ عملية شراء شركة|سهم", "investment"),
    (r"حوالة بين حساباتك", "transfer_self"),
    (r"حوالة.*(وارد|واردة)|وارد.*حوالة|استلام حوالة", "transfer_in"),
    (r"حوالة.*(صادر|صادرة)", "transfer_out"),
    (r"حوالة", "transfer_any"),
    (r"ايداع|إيداع|قيد مبلغ", "deposit"),
    (r"شراء|مشتريات|خصم من التفويض|عملية شراء", "purchase"),
]
MERCHANT_PATS = [  # priority order; a candidate line is a merchant only if it survives NOT_MERCHANT
    r"^لدي\s*:?\s*(.+)$",
    r"^من البائع\s*:?\s*(.+)$",
    r"^الخدمة\s*:?\s*(.+)$",
    r"^الجهة\s*:?\s*(.+)$",
    r"^مكان السحب\s*:?\s*(.+)$",
    r"^لـ(.+)$",  # alrajhi short form: لـPETROLY C
    r"^من\s*:?\s*(.+)$",  # alinma purchases: من: ALBAIK  (also matches sender/account lines; filtered)
]
NOT_MERCHANT = r"^[\s:]*(?:[\d*x#]+|حساب.*|البائع.*)$"
CARD_PAT = r"(?:بطاقة|البطاقة|عبر|ببطاقة مدي|بطاقة مدي|لبطاقة مدي)[^\d\n]*?(\d{4})"
DATE_PATS = [
    (r"(\d{4})[-/](\d{1,2})[-/](\d{1,2})[ T]+(\d{1,2}):(\d{2})", "ymd"),
    (r"(\d{1,2}):(\d{2})\s+(\d{4})[-/](\d{1,2})[-/](\d{1,2})", "hm_Ymd"),
    (r"(\d{1,2}):(\d{2})\s+(\d{2})[-/](\d{1,2})[-/](\d{1,2})", "hm_ymd"),
    (r"(\d{2})[-/\\](\d{1,2})[-/\\](\d{1,2})\s+(\d{1,2}):(\d{2})", "ymd2"),
    (r"(\d{1,2})[-/\\](\d{1,2})[-/\\](\d{2})\s+(\d{1,2}):(\d{2})", "dmy"),
]


def parse_date(text):
    for pat, kind in DATE_PATS:
        m = re.search(pat, text)
        if not m:
            continue
        g = [int(x) for x in m.groups()]
        try:
            if kind == "ymd":
                return datetime(g[0], g[1], g[2], g[3], g[4])
            if kind == "hm_Ymd":
                return datetime(g[2], g[3], g[4], g[0], g[1])
            if kind == "hm_ymd":
                return datetime(2000 + g[2], g[3], g[4], g[0], g[1])
            if kind == "ymd2":
                return datetime(2000 + g[0], g[1], g[2], g[3], g[4])
            if kind == "dmy":  # alrajhi 2026 format: d/m/yy — ambiguous with yy-m-d; 2-digit first group + slash = dmy
                return datetime(2000 + g[2], g[1], g[0], g[3], g[4])
        except ValueError:
            continue
    return None


def extract(text):
    t = normalize(text)
    lines = t.split("\n")
    first = next((ln for ln in lines if not re.match(r"^(هلا|عميلنا العزيز|عزيزي|عزيزنا)", ln)), lines[0])
    ttype = None
    for pat, name in TYPE_RULES:
        if re.search(pat, first) or (name in ("declined", "otp", "investment") and re.search(pat, t)):
            ttype = name
            break
    if ttype in (None, "otp", "declined", "auth_hold"):
        return {"type": ttype or "unknown", "tx": False}
    if ttype == "transfer_any":  # decide direction from presence of من:<name> vs الي:<name>
        ttype = "transfer_in" if re.search(r"^من\s*:\s*\S", t, re.M) and not re.search(r"^من\s*:?\s*\d{4}$", t, re.M) else "transfer_out"
    amount = cur = None
    for pat in AMOUNT_PATS:
        m = re.search(pat, t, re.M)
        if m:
            a, b = m.group(1), m.group(2) if m.lastindex and m.lastindex >= 2 else None
            if a and re.match(r"^\d", a):
                amount, cur = a, b
            else:
                amount, cur = b, a
            break
    if amount is None:
        return {"type": ttype, "tx": True, "amount": None}
    amount = float(amount.replace(",", ""))
    cur = {"SR": "SAR", "ريال": "SAR", "ريال سعودي": "SAR", None: "SAR"}.get(cur, cur)
    merchant = None
    if ttype in ("purchase", "refund", "atm_out", "bill", "gov"):
        for pat in MERCHANT_PATS:
            for ln in lines:
                m = re.match(pat, ln)
                if m and not re.match(NOT_MERCHANT, m.group(1)):
                    merchant = m.group(1).strip(" .;:")
                    break
            if merchant:
                break
    card = re.search(CARD_PAT, t)
    settled = re.search(SETTLED_PAT, t)
    return {"type": ttype, "tx": True, "amount": amount, "currency": cur, "merchant": merchant,
            "card": card.group(1) if card else None, "date": parse_date(t),
            "settled": (float(settled.group(1)), settled.group(2)) if settled else None}


# ---------------------------------------------------------------- categorize
# Seed dictionary written from general Saudi knowledge BEFORE looking at corpus merchants.
SEED = {
    "Food": ["albaik", "al baik", "herfy", "kudu", "mcdonald", "kfc", "burger king", "hardee", "dunkin", "starbucks",
             "barn", "shawarmer", "hungerstation", "jahez", "keeta", "talabat", "toyou", "mrsool", "pizza hut",
             "dominos", "subway", "maestro", "kababji", "tazaj", "hamburgini", "half million", "dose cafe"],
    "Groceries": ["panda", "tamimi", "othaim", "danube", "carrefour", "lulu", "bin dawood", "nesto", "farm superstores",
                  "manuel", "sadhan", "raya", "abdullah al othaim"],
    "Shopping": ["amazon", "noon", "jarir", "extra", "ikea", "saco", "shein", "namshi", "centrepoint", "max", "h&m",
                 "zara", "redtag", "lc waikiki"],
    "Transport": ["careem", "uber", "kaiian", "jeeny", "sapn", "riyadh metro"],
    "Fuel": ["aldrees", "sasco", "naft", "petromin", "petroly", "adnoc", "tas'helat", "liter"],
    "Telecom": ["mobily", "stc", "zain", "sawa", "lebara", "virgin mobile", "salam", "موبايلي", "سوا", "زين"],
    "Software": ["apple.com/bill", "apple.com", "google", "netflix", "spotify", "shahid", "osn", "anghami",
                 "anthropic", "claude.ai", "openai", "chatgpt", "digitalocean", "github", "microsoft", "adobe",
                 "steam", "playstation"],
    "Health": ["nahdi", "dawaa", "al dawaa", "whites", "kunooz", "al-dawaa", "sulaiman al habib", "mouwasat"],
    "Travel": ["airport", "saudia", "flynas", "flyadeal", "booking.com", "airbnb", "almosafer", "hilton", "marriott"],
    "Government": ["absher", "muqeem", "tamm", "vat", "zakat", "moi", "رخص القيادة"],
    "Finance": ["investment", "capital", "tadawul", "derayah", "alinma capital", "stc pay"],
    "Services": ["laundry", "car wash", "salon", "barber"],
}
KEYWORDS = [  # substring/keyword -> category, only where the meaning is IN the string
    (r"\b(rest|restaurant|resto|cafe|caffe|coffee|bakery|kitchen|grill|shawarma|burger|pizza|mtaam|mataam|matam|mtam)\b", "Food"),
    (r"مطعم|كافيه|كوفي|مخبز|شاورما|بوفيه|بوفيت", "Food"),
    (r"\b(market|supermarket|hyper|grocery|mart|super)\b|بقالة|تموينات|سوبر ماركت|هايبر", "Groceries"),
    (r"\bpharm|pharmacy\b|صيدلية", "Health"),
    (r"\b(gas station|petrol|fuel|station|mahta|mahatta)\b|محطة|بنزين|وقود", "Fuel"),
    (r"\b(laun|laundry|wash)\b|مغسلة", "Services"),
    (r"\b(hotel|airline|airways|airport)\b|فندق|طيران", "Travel"),
    (r"\bsubscription\b", "Software"),
]
TYPE_CATEGORY = {"transfer_in": "Transfer", "transfer_out": "Transfer", "transfer_self": "Transfer", "salary": "Income", "deposit": "Income",
                 "atm_out": "Cash", "gov": "Government", "investment": "Finance"}
CATEGORY_TEXT = {  # what the embedding compares against; EN + AR descriptions
    "Food": "restaurant cafe coffee shop fast food burger pizza bakery food delivery مطعم كافيه مقهى وجبات سريعة",
    "Groceries": "supermarket grocery store hypermarket market بقالة سوبرماركت تموينات",
    "Shopping": "retail store shopping mall electronics clothing furniture online store متجر تسوق ملابس اثاث",
    "Transport": "taxi ride hailing car ride bus metro transport مواصلات تاكسي توصيل",
    "Fuel": "gas station petrol fuel محطة وقود بنزين",
    "Telecom": "mobile operator telecom internet provider phone bill اتصالات جوال انترنت",
    "Software": "software subscription app cloud service streaming online subscription برامج اشتراك",
    "Health": "pharmacy hospital clinic doctor medical صيدلية مستشفى عيادة",
    "Travel": "airline hotel airport travel booking flight فندق طيران سفر مطار",
    "Government": "government fee ministry license traffic fine رسوم حكومية وزارة رخصة",
    "Finance": "investment brokerage trading stocks wallet top up استثمار تداول محفظة",
    "Services": "laundry car wash salon barber repair maintenance مغسلة صالون حلاق صيانة",
}
CATS = list(CATEGORY_TEXT)


def norm_merchant(m):
    return re.sub(r"[^a-z0-9؀-ۿ ]+", " ", (m or "").lower().replace("ـ", "")).strip()


def rule_categorize(ttype, merchant, overrides):
    """Returns (category or None, reason)."""
    if ttype in TYPE_CATEGORY:
        return TYPE_CATEGORY[ttype], "type"
    if ttype == "refund" and not merchant:
        return "Income", "type"
    nm = norm_merchant(merchant)
    if not nm:
        return None, "no-merchant"
    if nm in overrides:
        return overrides[nm], "override"
    for cat, names in SEED.items():
        for n in names:
            if nm == norm_merchant(n):
                return cat, "dict"
    for cat, names in SEED.items():  # fuzzy: dictionary name appears as a token/substring
        for n in names:
            if len(n) >= 4 and (re.search(rf"\b{re.escape(norm_merchant(n))}\b", nm) or (len(n) >= 6 and norm_merchant(n) in nm)):
                return cat, "fuzzy"
    for pat, cat in KEYWORDS:
        if re.search(pat, nm):
            return cat, "keyword"
    return None, "unknown"


class Embedder:
    def __init__(self):
        from sentence_transformers import SentenceTransformer, util  # noqa
        self.m = SentenceTransformer("paraphrase-multilingual-MiniLM-L12-v2")
        self.util = util
        self.cat_vecs = self.m.encode([CATEGORY_TEXT[c] for c in CATS], normalize_embeddings=True)

    def best(self, merchant):
        v = self.m.encode([merchant], normalize_embeddings=True)
        sims = (self.cat_vecs @ v.T).ravel()
        i = int(sims.argmax())
        return CATS[i], float(sims[i])


# ---------------------------------------------------------------- run
def main():
    corpus = json.load(open("corpus.json"))
    # ---- M1 + rejects
    m1_ok = m1_total = 0
    reject_ok = 0
    m1_fail = []
    parsed = {}
    for i, m in enumerate(corpus):
        r = extract(m["text"])
        parsed[i] = r
        if i in REJECT:
            reject_ok += not r["tx"]
            if r["tx"]:
                m1_fail.append((i, "REJECT became tx", r))
            continue
        if i not in T:
            if r["tx"]:
                m1_fail.append((i, "non-money became tx", r))
            continue
        m1_total += 1
        ttype, amt, cur, merch, _ = T[i]
        ok = (r["tx"] and r["type"] == ttype and r.get("amount") == amt and r.get("currency") == cur
              and (merch is None or norm_merchant(r.get("merchant")) == norm_merchant(merch))
              and (ttype != "purchase" or r.get("date") is not None))
        m1_ok += ok
        if not ok:
            m1_fail.append((i, "M1", r))
    print(f"M1 extraction: {m1_ok}/{m1_total} = {100*m1_ok/m1_total:.1f}%   rejects held: {reject_ok}/{len(REJECT)}")
    for f in m1_fail:
        print("   FAIL", f[0], f[1], {k: v for k, v in f[2].items() if k != "date"}, "| truth:", T.get(f[0]))

    # ---- M2: purchases/refunds with a merchant are the only interesting rows
    rows = [(i, T[i]) for i in T if T[i][0] in ("purchase", "refund") and T[i][3]]
    # merchant-level hold-out, deterministic
    def bucket(m):
        return int(hashlib.md5(norm_merchant(m).encode()).hexdigest(), 16) % 5  # 0 = held out (20%)
    tune = [(i, t) for i, t in rows if bucket(t[3]) != 0]
    held = [(i, t) for i, t in rows if bucket(t[3]) == 0]
    print(f"\nM2 rows: {len(rows)} purchase/refund with merchant; tune {len(tune)} / held-out {len(held)}; "
          f"'?' (not in string) = {sum(1 for _, t in rows if t[4] == '?')}")

    emb = Embedder()

    def score(rowset, threshold, use_emb):
        c = Counter()
        detail = []
        for i, (ttype, _, _, merch, truth) in rowset:
            cat, reason = rule_categorize(ttype, merch, {})
            if cat is None and use_emb:
                ecat, sim = emb.best(merch)
                if sim >= threshold:
                    cat, reason = ecat, f"emb {sim:.2f}"
            if cat is None:
                out = "abstain" if truth == "?" else "abstained"  # both count as not-wrong
                c["abstain"] += 1
            elif truth == "?":
                out = "WRONG(guessed)"; c["wrong"] += 1
            elif cat == truth:
                out = "correct"; c["correct"] += 1
            else:
                out = "WRONG"; c["wrong"] += 1
            detail.append((i, merch, truth, cat, reason, out))
        n = len(rowset)
        return {k: 100 * c[k] / n for k in ("correct", "wrong", "abstain")}, detail

    # freeze threshold on tune set: max correct subject to wrong <= 2 points above rules-only wrong
    base, _ = score(tune, 9, False)
    best_t, best_c = None, -1
    for t in [x / 100 for x in range(30, 91, 2)]:
        s, _ = score(tune, t, True)
        if s["wrong"] <= base["wrong"] + 2.0 and s["correct"] > best_c:
            best_t, best_c = t, s["correct"]
    print(f"threshold frozen on tune set: {best_t}  (tune rules-only {base})")

    for name, rowset in (("TUNE (not the result)", tune), ("HELD-OUT (the result)", held), ("ALL (for reference)", rows)):
        s0, d0 = score(rowset, 9, False)
        s1, d1 = score(rowset, best_t, True)
        print(f"\n{name}  n={len(rowset)}")
        print(f"   rules only : correct {s0['correct']:.0f}%  wrong {s0['wrong']:.0f}%  abstain {s0['abstain']:.0f}%")
        print(f"   + embedding: correct {s1['correct']:.0f}%  wrong {s1['wrong']:.0f}%  abstain {s1['abstain']:.0f}%")
        if "HELD" in name or "ALL" in name:
            for d in d1:
                print("     ", d)

    # ---- M3: chronological replay with corrections, all purchase rows, threshold frozen
    seq = sorted(((parsed[i]["date"] or datetime(2000, 1, 1), i, t) for i, t in rows), key=lambda x: x[0])
    overrides = {}
    taps = defaultdict(int); seen = defaultdict(int)
    for d, i, (ttype, _, _, merch, truth) in seq:
        cat, reason = rule_categorize(ttype, merch, overrides)
        if cat is None:
            ecat, sim = emb.best(merch)
            if sim >= best_t:
                cat = ecat
        month = d.strftime("%Y-%m")
        seen[month] += 1
        if cat is None or (truth != "?" and cat != truth) or (truth == "?" and reason != "override"):
            taps[month] += 1
            overrides[norm_merchant(merch)] = truth if truth != "?" else "Other"
    print("\nM3 taps per month (taps/transactions), corrections persist:")
    for mth in sorted(seen):
        print(f"   {mth}: {taps[mth]}/{seen[mth]}")
    print(f"   total taps {sum(taps.values())} over {len(seq)} purchases; distinct merchants {len(overrides)}")


if __name__ == "__main__":
    main()

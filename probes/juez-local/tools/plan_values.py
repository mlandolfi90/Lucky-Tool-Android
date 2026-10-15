"""The plan check of the app's "datos" answer (PlanValues.kt), for the analysis on the PC.

In an amount field an amount compares by number and currency (`20.000,00 Bs` is `20000 Bs`; `20000` alone,
`+20.000 Bs` or `hasta 20000 Bs` is not); anything else compares as text, without letter case and without the quotes,
brackets or bold around the whole value, and no other character is dropped. Only NINGUNO means "no value".
`in_text` is the app's check that a copied value is written in the action. PlanValuesTest pins the same cases on the
app side.
"""
import re
from decimal import Decimal, InvalidOperation

WRAPPERS = [("«", "»"), ('"', '"'), ("'", "'"), ("“", "”"), ("‘", "’"), ("(", ")"), ("[", "]"), ("`", "`"),
            ("**", "**")]
NONE_VALUE = "ninguno"
# The app's \s and \d are Java's, which match ASCII only.
JAVA_SPACE = r"[ \t\n\x0b\f\r]"
LETTERS = r"(?:[^\W\d_]|[$€])+"
AMOUNT = re.compile(rf"(?:({LETTERS})\.?{JAVA_SPACE}*)?([0-9](?:[0-9.,]*[0-9])?)(?:{JAVA_SPACE}*({LETTERS}))?")
NUMBER = re.compile(r"(?<!\w)[0-9](?:[0-9.,]*[0-9])?(?!\w)")
THOUSANDS = {".": re.compile(r"[0-9]{1,3}(?:\.[0-9]{3})+"), ",": re.compile(r"[0-9]{1,3}(?:,[0-9]{3})+")}


def wraps(pair, text):
    opening, closing = pair
    return len(text) >= len(opening) + len(closing) and text.startswith(opening) and text.endswith(closing)


def unwrap(value):
    text = value.strip()
    while True:
        pair = next((pair for pair in WRAPPERS if wraps(pair, text)), None)
        if pair is None:
            return text
        text = text[len(pair[0]):len(text) - len(pair[1])].strip()


def text_of(value):
    return unwrap(value).lower()


def is_none(value):
    return text_of(value) == NONE_VALUE


def one_separator(digits, separator):
    if THOUSANDS[separator].fullmatch(digits):
        return digits.replace(separator, "")
    if digits.count(separator) == 1:
        return digits.replace(separator, ".")
    return None


def two_separators(digits):
    decimal = max((digits.rfind("."), "."), (digits.rfind(","), ","))[1]
    whole, fraction = digits.rsplit(decimal, 1)
    thousands = "," if decimal == "." else "."
    if THOUSANDS[thousands].fullmatch(whole):
        return whole.replace(thousands, "") + "." + fraction
    return None


def number_of(digits):
    separators = {c for c in digits if c in ".,"}
    if not separators:
        plain = digits
    elif len(separators) == 1:
        plain = one_separator(digits, separators.pop())
    else:
        plain = two_separators(digits)
    try:
        return None if plain is None else Decimal(plain)
    except InvalidOperation:
        return None


def amount_of(value):
    text = text_of(value)
    match = AMOUNT.fullmatch(text[:-1] if text.endswith(".") else text)
    if not match:
        return None
    before, digits, after = (group or "" for group in match.groups())
    currency = before + after
    number = number_of(digits)
    if number is None or not currency or (before and after):
        return None
    return number, currency


def same(read, plan_value, amount):
    plan_amount = amount_of(plan_value) if amount else None
    if plan_amount is None:
        return text_of(read) == text_of(plan_value)
    return amount_of(read) == plan_amount


def accepted(read, plan_values, amount):
    return read is not None and any(same(read, value, amount) for value in plan_values)


def is_word_char(char):
    return char is not None and (char.isalnum() or char == "_")


def contains_word(text, word):
    if not word:
        return False
    at = text.find(word)
    while at >= 0:
        before = text[at - 1] if at > 0 else None
        after = text[at + len(word)] if at + len(word) < len(text) else None
        if (not is_word_char(word[0]) or not is_word_char(before)) and (
                not is_word_char(word[-1]) or not is_word_char(after)):
            return True
        at = text.find(word, at + 1)
    return False


def in_text(read, text, amount):
    lower = text.lower()
    copied = amount_of(read) if amount else None
    if copied is None:
        return contains_word(lower, text_of(read))
    number, currency = copied
    return any(number_of(found) == number for found in NUMBER.findall(lower)) and contains_word(lower, currency)

package com.danielealbano.androidremotecontrolmcp.policy.engine

/**
 * Words that force ACTION_BUTTON on a tapped or clicked target, even over a "not an action button" exception.
 * Matching DATA, not UI text: never moved to `strings.xml`, because Android resolves resources by the phone's
 * locale while the target app may use another language — Spanish and English are always both active.
 * Matched as whole words (or contiguous word sequences) after [PolicyLabels.words] folding, so `apagar` does not
 * match `pagar` and `sender` does not match `send`. Entries are written already folded (lowercase, no accents).
 */
object ActionKeywords {
    val SPANISH: List<String> =
        listOf(
            "pagar",
            "paga",
            "pague",
            "abonar",
            "abona",
            "abone",
            "enviar",
            "envia",
            "envie",
            "transferir",
            "transferi",
            "transfiera",
            "comprar",
            "compra",
            "compre",
            "vender",
            "vende",
            "venda",
            "confirmar",
            "confirma",
            "confirme",
            "invertir",
            "inverti",
            "invierta",
            "retirar",
            "retira",
            "retire",
            "extraer",
            "extrae",
            "extraiga",
            "rescatar",
            "rescata",
            "rescate",
            "suscribir",
            "suscribi",
            "suscriba",
            "autorizar",
            "autoriza",
            "autorice",
            "firmar",
            "firma",
            "firme",
            "aceptar",
            "acepta",
            "acepte",
            "depositar",
            "deposita",
            "deposite",
            "recargar",
            "recarga",
            "recargue",
            "donar",
            "dona",
            "done",
        )

    val ENGLISH: List<String> =
        listOf(
            "pay",
            "send",
            "transfer",
            "buy",
            "sell",
            "confirm",
            "checkout",
            "check out",
            "withdraw",
            "invest",
            "trade",
            "authorize",
            "authorise",
            "submit",
            "place order",
            "accept",
            "approve",
            "purchase",
            "deposit",
            "donate",
            "top up",
            "sign",
        )

    private val phrases: List<List<String>> =
        (SPANISH + ENGLISH).map(PolicyLabels::words).filter { it.isNotEmpty() }.distinct()
    private val singleWords: Set<String> = phrases.filter { it.size == 1 }.map { it.single() }.toSet()
    private val multiWordPhrases: List<List<String>> = phrases.filter { it.size > 1 }

    fun matches(label: String): Boolean {
        val words = PolicyLabels.words(label)
        return words.any { it in singleWords } || multiWordPhrases.any { containsPhrase(words, it) }
    }

    private fun containsPhrase(
        words: List<String>,
        phrase: List<String>,
    ): Boolean =
        (0..words.size - phrase.size).any { start ->
            phrase.indices.all { offset -> words[start + offset] == phrase[offset] }
        }
}

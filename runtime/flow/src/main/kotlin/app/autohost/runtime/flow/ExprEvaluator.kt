package app.autohost.runtime.flow

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * P6-05: the side-effect-free expression language for `when.true` / `repeat.while`
 * (mirrors autohost_ref.flows.eval_expr). Grammar, lowest to highest precedence:
 *
 *   or    := and ( '||' and )*
 *   and   := not ( '&&' not )*
 *   not   := '!' not | cmp
 *   cmp   := add ( '==' '!=' '<' '<=' '>' '>=' add )?
 *   add   := unary ( ('+'|'-') unary )*
 *   unary := '-' unary | atom
 *   atom  := number | 'str' | "str" | true | false | name | '(' or ')'
 *
 * Names are bare (env -> config -> profile) or dotted (config./profile./env./run.).
 * Secrets are refused. Ordering needs two numbers or two strings; `+` adds numbers or
 * concatenates strings (mixing is an error); booleans are not numbers.
 */
internal object ExprEvaluator {
    private val OPS = setOf("==", "!=", "<", "<=", ">", ">=", "&&", "||", "+", "-", "!", "(", ")")

    private val TOKEN = Regex(
        """(?<ws>\s+)|(?<num>\d+(?:\.\d+)?)|(?<str>'[^']*'|"[^"]*")|(?<op>==|!=|<=|>=|&&|\|\||[<>!+\-()])|(?<name>[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)""",
    )

    /** Evaluates and requires a boolean result: false -> false, non-boolean -> typed failure. */
    fun isTrue(expr: String, scope: FlowScope): Boolean {
        val value = eval(expr, scope)
        return when (JsonValues.bool(value)) {
            true -> true
            false -> false
            null -> throw FlowFailure(
                "INVALID_ARGS", "when.true must evaluate to true/false, got ${render(value)}",
            )
        }
    }

    fun render(value: JsonElement): String = when {
        value is JsonPrimitive && value.isString -> "'${value.content}'"
        else -> value.toString()
    }

    fun eval(expr: String, scope: FlowScope): JsonElement {
        val tokens = tokenize(expr)
        val parser = Parser(tokens, scope)
        val value = parser.parseOr()
        if (parser.peek().kind != Kind.END) {
            throw ExprError("unexpected trailing input in '$expr'")
        }
        return value
    }

    private enum class Kind { NUM, STR, OP, NAME, END }

    private class Token(val kind: Kind, val text: String) {
        val isInt: Boolean get() = kind == Kind.NUM && Regex("^-?\\d+$").matches(text)
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var pos = 0
        while (pos < expr.length) {
            val match = TOKEN.matchAt(expr, pos) ?: throw ExprError("unexpected character '${expr[pos]}' at $pos")
            pos = match.end()
            val group = when {
                match.groups["ws"] != null -> continue
                match.groups["num"] != null -> Kind.NUM
                match.groups["str"] != null -> Kind.STR
                match.groups["op"] != null -> Kind.OP
                else -> Kind.NAME
            }
            val text = match.value
            if (group == Kind.OP && text !in OPS) throw ExprError("unknown operator '$text'")
            tokens.add(Token(group, text))
        }
        tokens.add(Token(Kind.END, ""))
        return tokens
    }

    private class Parser(private val tokens: List<Token>, private val scope: FlowScope) {
        private var pos = 0

        fun peek(): Token = tokens[pos]

        private fun takeOp(vararg ops: String): String? {
            val token = peek()
            if (token.kind == Kind.OP && token.text in ops) {
                pos += 1
                return token.text
            }
            return null
        }

        fun parseOr(): JsonElement {
            var value = parseAnd()
            while (takeOp("||") != null) {
                val right = parseAnd()
                value = boolValue(value)?.let { left ->
                    boolValue(right)?.let { r -> JsonPrimitive(left || r) }
                } ?: throw ExprError("&& and || need booleans on both sides")
            }
            return value
        }

        fun parseAnd(): JsonElement {
            var value = parseNot()
            while (takeOp("&&") != null) {
                val right = parseNot()
                value = boolValue(value)?.let { left ->
                    boolValue(right)?.let { r -> JsonPrimitive(left && r) }
                } ?: throw ExprError("&& and || need booleans on both sides")
            }
            return value
        }

        fun parseNot(): JsonElement {
            if (takeOp("!") != null) {
                val value = parseNot()
                return JsonPrimitive(!(boolValue(value) ?: throw ExprError("! needs a boolean")))
            }
            return parseComparison()
        }

        fun parseComparison(): JsonElement {
            val left = parseAdditive()
            val op = takeOp("==", "!=", "<", "<=", ">", ">=") ?: return left
            val right = parseAdditive()
            return JsonPrimitive(compare(op, left, right))
        }

        fun parseAdditive(): JsonElement {
            var value = parseUnary()
            while (true) {
                val op = takeOp("+", "-") ?: return value
                val right = parseUnary()
                value = if (op == "+") add(value, right) else subtract(value, right)
            }
        }

        fun parseUnary(): JsonElement {
            if (takeOp("-") != null) {
                val value = parseUnary()
                if (!JsonValues.isNum(value)) throw ExprError("- needs a number")
                return negate(value)
            }
            return parseAtom()
        }

        fun parseAtom(): JsonElement {
            val token = peek()
            if (token.kind == Kind.OP && token.text == "(") {
                pos += 1
                val inner = parseOr()
                if (takeOp(")") == null) throw ExprError("missing closing parenthesis")
                return inner
            }
            if (token.kind == Kind.NUM) {
                pos += 1
                return if (token.isInt) JsonPrimitive(token.text.toLong()) else JsonPrimitive(token.text.toDouble())
            }
            if (token.kind == Kind.STR) {
                pos += 1
                return JsonPrimitive(token.text.substring(1, token.text.length - 1))
            }
            if (token.kind == Kind.NAME) {
                pos += 1
                return when (token.text) {
                    "true" -> JsonPrimitive(true)
                    "false" -> JsonPrimitive(false)
                    else -> resolveName(token.text)
                }
            }
            throw ExprError("expected a value, got '${token.text}'")
        }

        private fun resolveName(name: String): JsonElement {
            if (name.startsWith("secret.")) throw ExprError("secrets cannot be used in expressions")
            val namespace = name.substringBefore('.', "")
            val rest = name.substringAfter('.', "")
            if (rest.isNotEmpty() && namespace in setOf("config", "profile", "env", "run")) {
                val table = when (namespace) {
                    "config" -> scope.config
                    "profile" -> scope.profile
                    "env" -> JsonObject(scope.env)
                    else -> scope.run
                }
                val (found, value) = FlowScope.walk(table, rest)
                if (!found) throw ExprError("unknown variable '$name'")
                return value ?: JsonNull
            }
            for (table in listOf(JsonObject(scope.env), scope.config, scope.profile)) {
                if (name in table) return table.getValue(name)
            }
            throw ExprError("unknown variable '$name'")
        }
    }

    private fun boolValue(value: JsonElement): Boolean? = JsonValues.bool(value)

    private fun isBool(value: JsonElement): Boolean = JsonValues.isBool(value)

    private fun add(a: JsonElement, b: JsonElement): JsonElement = when {
        JsonValues.isInt(a) && JsonValues.isInt(b) -> JsonPrimitive(JsonValues.long(a) + JsonValues.long(b))
        JsonValues.isNum(a) && JsonValues.isNum(b) -> JsonPrimitive(JsonValues.double(a) + JsonValues.double(b))
        JsonValues.isStr(a) && JsonValues.isStr(b) -> JsonPrimitive(JsonValues.str(a) + JsonValues.str(b))
        else -> throw ExprError("+ needs two numbers or two strings")
    }

    private fun subtract(a: JsonElement, b: JsonElement): JsonElement = when {
        JsonValues.isInt(a) && JsonValues.isInt(b) -> JsonPrimitive(JsonValues.long(a) - JsonValues.long(b))
        JsonValues.isNum(a) && JsonValues.isNum(b) -> JsonPrimitive(JsonValues.double(a) - JsonValues.double(b))
        else -> throw ExprError("- needs two numbers")
    }

    private fun negate(value: JsonElement): JsonElement =
        if (JsonValues.isInt(value)) JsonPrimitive(-JsonValues.long(value))
        else JsonPrimitive(-JsonValues.double(value))

    private fun compare(op: String, left: JsonElement, right: JsonElement): Boolean {
        if (op == "==") {
            if (isBool(left) != isBool(right)) return false
            return when {
                isBool(left) -> JsonValues.bool(left) == JsonValues.bool(right)
                JsonValues.isNum(left) && JsonValues.isNum(right) -> JsonValues.double(left) == JsonValues.double(right)
                JsonValues.isStr(left) && JsonValues.isStr(right) -> JsonValues.str(left) == JsonValues.str(right)
                else -> left == right
            }
        }
        if (op == "!=") return !compare("==", left, right)
        if (!(JsonValues.isNum(left) && JsonValues.isNum(right)) &&
            !(JsonValues.isStr(left) && JsonValues.isStr(right))
        ) {
            throw ExprError("ordering '$op' needs two numbers or two strings")
        }
        val cmp = when {
            JsonValues.isNum(left) -> JsonValues.double(left).compareTo(JsonValues.double(right))
            else -> JsonValues.str(left).compareTo(JsonValues.str(right))
        }
        return when (op) {
            "<" -> cmp < 0
            "<=" -> cmp <= 0
            ">" -> cmp > 0
            else -> cmp >= 0
        }
    }
}

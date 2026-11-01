package dev.phaenon.jetbrains

import com.intellij.lexer.Lexer
import com.intellij.lexer.LookAheadLexer
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.jetbrains.php.lang.documentation.phpdoc.parser.PhpDocElementTypes
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.lexer.PhpWithOptionalOpenTagLexer

/**
 * The lexer behind the parser of `.phn` files: PhpStorm's PHP lexer with Phaenon's additions
 * erased from the token stream.
 *
 * PhpStorm's PHP parser only knows PHP. Everything Phaenon adds on top of PHP erases to PHP in the
 * compiler, and this lexer performs the same erasure one level earlier, on tokens: a construct the
 * PHP parser would reject is handed to it as whitespace, with the text and the offsets untouched.
 * The parser then builds ordinary PHP PSI, and `PsiBuilder` turns the erased spans into
 * `PsiWhiteSpace` leaves whose text is still the Phaenon source (the platform creates a whitespace
 * leaf for every token in the whitespace set, whatever its own type). [PhaenonTypeProvider] reads
 * those leaves back to give the declarations the types the erased text spelled.
 *
 * What is erased, and how (design: `design/generics.md` §2, `collections.md` §2, `strings.md` §2):
 *
 * - Type-argument lists `<…>` after a name: in type positions (`list<T>`, `Foo<K, V>`), on
 *   declarations (`class Box<T>`, `function f<T of Bound = Default>`, `fn<T>`) and at call sites
 *   (`new Box<int>(…)`, `f<int>(…)`, `$o->m<T>(…)`, `C::m<T>(…)`). The list is erased, the name
 *   stays. Declaration lists also record their parameter names, so that `T` used as a type later
 *   in the file is erased too (an unknown class name to PhpStorm otherwise).
 * - Types PHP has no spelling for, in type positions: `list<…>`, `map<…>`, `class<…>`, `bytes`,
 *   tuples `(A, B)` and type parameters. A union member is erased with its separator; a type
 *   erased completely takes its `?` and, for a return type, its `:` with it.
 * - `Closure(T): U` is erased whole: a bare `Closure` with a signature is `\Closure` whatever the
 *   namespace and imports (the compiler's reading), where PhpStorm would resolve the name in the
 *   file's namespace. `\Closure(T): U` keeps its name and erases the signature.
 * - `$x is T` becomes `$x instanceof T` when `T` is a plain class name (the `is` token is re-typed,
 *   its text stays); every other `is`/`as`/`as?` test is erased together with its type, so the
 *   expression degrades to its operand.
 * - `type Name = …;` aliases are erased whole.
 * - `(bytes)` is re-typed as a `(string)` cast: one token spanning the parentheses.
 *
 * The decision to read `Name<` as a type-argument list is the compiler's: PHP's relational
 * operators are non-associative, so `A < B > C` has no PHP reading, and a `<…>` that scans as a
 * type list is committed to. The one PHP shape this misreads, `f(A < B, C > ($d))`, is one the
 * compiler misreads as well (`generics.md` §2.4).
 *
 * The lexer is not restartable: it buffers tokens ahead of the base lexer and does not keep
 * per-offset states. It serves the parser, which lexes a whole file; the editor highlighter keeps
 * the plain PHP lexer (see [PhaenonBaseLexerProvider]).
 */
class PhaenonLexer(private val base: Lexer = PhpWithOptionalOpenTagLexer()) : LookAheadLexer(base) {

    private class Tok(val type: IElementType, val start: Int, val end: Int)

    /** Tokens pulled from the base lexer and not yet handed to the cache. */
    private val pending = ArrayList<Tok>()
    private var prevSig: IElementType? = null
    private var prevSigText: String = ""
    private val typeParams = HashSet<String>()
    private var buffer: CharSequence = ""

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        pending.clear()
        prevSig = null
        prevSigText = ""
        typeParams.clear()
        super.start(buffer, startOffset, endOffset, initialState)
    }

    override fun lookAhead(baseLexer: Lexer) {
        val c = tok(0)
        if (c == null) {
            // The cache must never stay empty: a null type is how a lexer says "end of file".
            addToken(base.tokenEnd, null)
            return
        }
        if (c.type in SKIPPABLE) {
            emit(Plan(1))
            return
        }
        val plan = tryFun(c)
            ?: tryConcurrently(c)
            ?: trySpawn(c)
            ?: tryIs(c)
            ?: tryAs(c)
            ?: tryTypeAlias(c)
            ?: tryDeclarationTypeParams(c)
            ?: tryBytesCast(c)
            ?: tryReturnType(c)
            ?: tryTypePosition(c)
            ?: tryTypeArguments(c)
            ?: Plan(1)
        emit(plan)
    }

    // ---------------------------------------------------------------- token access

    private fun tok(i: Int): Tok? {
        while (pending.size <= i) {
            val type = base.tokenType ?: return null
            pending.add(Tok(type, base.tokenStart, base.tokenEnd))
            base.advance()
        }
        return pending[i]
    }

    /** Index of the first significant token at or after [i], or -1 at the end of the file. */
    private fun sig(i: Int): Int {
        var j = i
        while (true) {
            val t = tok(j) ?: return -1
            if (t.type !in SKIPPABLE) return j
            j++
        }
    }

    private fun typeAt(i: Int): IElementType? = if (i < 0) null else tok(i)?.type

    private fun text(t: Tok): String = buffer.subSequence(t.start, t.end).toString()

    private fun isIdent(i: Int, word: String): Boolean {
        val t = tok(i) ?: return false
        return t.type == PhpTokenTypes.IDENTIFIER && text(t) == word
    }

    // ---------------------------------------------------------------- emission

    /** What to do with the first [count] pending tokens. */
    private inner class Plan(val count: Int) {
        val types = Array(count) { pending[it].type }
        val joinWithPrev = BooleanArray(count)

        fun erase(range: IntRange): Plan {
            for (i in range) types[i] = PhaenonTokenTypes.ERASED
            return this
        }

        fun erase(ranges: List<IntRange>): Plan {
            ranges.forEach { erase(it) }
            return this
        }

        fun replace(i: Int, type: IElementType): Plan {
            types[i] = type
            return this
        }

        /** Tokens [from]..[to] become one token of [type]. */
        fun merge(from: Int, to: Int, type: IElementType): Plan {
            types[from] = type
            for (i in from + 1..to) joinWithPrev[i] = true
            return this
        }
    }

    private fun emit(plan: Plan) {
        var i = 0
        while (i < plan.count) {
            val type = plan.types[i]
            var end = pending[i].end
            var j = i + 1
            // An erased span, whitespace inside it included, becomes one erased leaf.
            while (j < plan.count && (plan.joinWithPrev[j] || type === PhaenonTokenTypes.ERASED && plan.types[j] === PhaenonTokenTypes.ERASED)) {
                end = pending[j].end
                j++
            }
            addToken(end, type)
            if (type !in SKIPPABLE) {
                prevSig = type
                prevSigText = if (type == PhpTokenTypes.IDENTIFIER) buffer.subSequence(pending[i].start, end).toString() else ""
            }
            i = j
        }
        pending.subList(0, plan.count).clear()
    }

    // ---------------------------------------------------------------- triggers

    /**
     * `fun name(`: the `function` keyword spelled short, `public` implied (design/functions.md §1).
     * The token keeps its text and takes the keyword's type; a method without a visibility is
     * public to PhpStorm's parser as it is to PHP. `fun(` and `fun =` stay identifiers.
     */
    private fun tryFun(c: Tok): Plan? {
        if (c.type != PhpTokenTypes.IDENTIFIER || text(c) != "fun" || prevSig !in FUN_PREDECESSORS) return null
        var next = sig(1)
        if (typeAt(next) == PhpTokenTypes.opBIT_AND) next = sig(next + 1)
        val t = typeAt(next) ?: return null
        if (t != PhpTokenTypes.IDENTIFIER && t !in PhpTokenTypes.tsKEYWORDS) return null
        return Plan(1).replace(0, PhpTokenTypes.kwFUNCTION)
    }

    /**
     * `concurrently (opts) {`: the keyword and its options are erased so that the braces parse as a
     * block (design/concurrency.md §11). The expression form, `return concurrently {`, stays a
     * syntax error until a PSI block exists. `Api::concurrently(` and a `concurrently(…)` call with
     * no brace after it are calls and stay.
     */
    private fun tryConcurrently(c: Tok): Plan? {
        if (c.type != PhpTokenTypes.IDENTIFIER || text(c) != "concurrently" || afterMemberAccess()) return null
        val next = sig(1)
        when (typeAt(next)) {
            PhpTokenTypes.chLBRACE -> return Plan(1).erase(0..0)
            PhpTokenTypes.chLPAREN -> {}
            else -> return null
        }
        var depth = 0
        var i = next
        while (true) {
            when (typeAt(i) ?: return null) {
                PhpTokenTypes.chLPAREN -> depth++
                PhpTokenTypes.chRPAREN -> if (--depth == 0) break
            }
            i = sig(i + 1)
            if (i < 0) return null
        }
        if (typeAt(sig(i + 1)) != PhpTokenTypes.chLBRACE) return null
        return Plan(i + 1).erase(0..i)
    }

    /**
     * `spawn expr`: the keyword is erased and the expression stays, typed as the call it wraps
     * (design/concurrency.md §11). A member, a function or a constant called spawn is left alone.
     */
    private fun trySpawn(c: Tok): Plan? {
        if (c.type != PhpTokenTypes.IDENTIFIER || text(c) != "spawn" || afterMemberAccess() || prevSig == PhpTokenTypes.kwFUNCTION) return null
        val next = typeAt(sig(1)) ?: return null
        if (next == PhpTokenTypes.opASGN || next == PhpTokenTypes.SCOPE_RESOLUTION || next == PhpTokenTypes.opSEMICOLON) return null
        return Plan(1).erase(0..0)
    }

    private fun afterMemberAccess(): Boolean = prevSig != null && MEMBER_ACCESS.contains(prevSig)

    /** `$x is T`: `instanceof` for a class name, erased otherwise. */
    private fun tryIs(c: Tok): Plan? {
        if (c.type != PhpTokenTypes.IDENTIFIER || text(c) != "is" || prevSig !in EXPRESSION_END) return null
        val s = Scan()
        val u = scanUnion(sig(1), s) ?: return null
        if (s.extraClosers > 0) return null
        val single = u.members.singleOrNull()
        return if (single != null && single.classLike && !u.nullable) {
            Plan(u.end).replace(0, PhpTokenTypes.kwINSTANCEOF).erase(s.erase)
        } else {
            Plan(u.end).erase(0 until u.end)
        }
    }

    /** `$x as T`, `$x as? T`: erased, the expression keeps its operand. */
    private fun tryAs(c: Tok): Plan? {
        if (c.type != PhpTokenTypes.kwAS || prevSig !in VALUE_END) return null
        val s = Scan()
        val u = scanUnion(sig(1), s) ?: return null
        if (s.extraClosers > 0) return null
        return Plan(u.end).erase(0 until u.end)
    }

    /** `type Name<…> = T;` at statement level: erased whole. */
    private fun tryTypeAlias(c: Tok): Plan? {
        if (c.type != PhpTokenTypes.IDENTIFIER || text(c) != "type" || prevSig !in STATEMENT_START) return null
        var i = sig(1)
        if (typeAt(i) != PhpTokenTypes.IDENTIFIER) return null
        i = sig(i + 1)
        val s = Scan()
        if (typeAt(i) == PhpTokenTypes.opLESS) {
            i = scanTypeArguments(i, s, declaration = false) ?: return null
            i = sig(i)
        }
        if (typeAt(i) != PhpTokenTypes.opASGN) return null
        val u = scanUnion(sig(i + 1), s) ?: return null
        if (s.extraClosers > 0) return null
        val semi = sig(u.end)
        if (typeAt(semi) != PhpTokenTypes.opSEMICOLON) return null
        return Plan(semi + 1).erase(0..semi)
    }

    /** `class Box<T>`, `interface I<in T>`, `function f<T>`, `fn<T>`: the list is erased, the names recorded. */
    private fun tryDeclarationTypeParams(c: Tok): Plan? {
        val lt: Int
        if (c.type == PhpTokenTypes.kwFN) {
            lt = sig(1)
        } else if (c.type == PhpTokenTypes.IDENTIFIER && prevSig in DECLARATION_KEYWORDS) {
            lt = sig(1)
        } else if (c.type in PhpTokenTypes.tsKEYWORDS && prevSig == PhpTokenTypes.kwFUNCTION) {
            // A method may carry a reserved word as its name (`function for<U>(…)`); the lexer
            // still hands it over as the keyword, and PhpStorm's parser remaps it to a name.
            lt = sig(1)
        } else {
            return null
        }
        if (typeAt(lt) != PhpTokenTypes.opLESS) return null
        val s = Scan()
        val end = scanTypeArguments(lt, s, declaration = true) ?: return null
        if (s.extraClosers > 0) return null
        typeParams.addAll(s.names)
        return Plan(end).erase(lt until end)
    }

    /** `(bytes)` is a cast: one `(string)`-typed token. */
    private fun tryBytesCast(c: Tok): Plan? {
        if (c.type != PhpTokenTypes.chLPAREN) return null
        val word = sig(1)
        if (!isIdent(word, "bytes")) return null
        val close = sig(word + 1)
        if (typeAt(close) != PhpTokenTypes.chRPAREN) return null
        return Plan(close + 1).merge(0, close, PhpTokenTypes.opSTRING_CAST)
    }

    /** `): T` — a return type; its Phaenon parts are erased, the `:` too when nothing is left. */
    private fun tryReturnType(c: Tok): Plan? {
        if (c.type != PhpTokenTypes.opCOLON || prevSig != PhpTokenTypes.chRPAREN) return null
        val s = Scan()
        val u = scanUnion(sig(1), s) ?: return null
        if (s.extraClosers > 0 || s.erase.isEmpty()) return null
        if (typeAt(sig(u.end)) !in RETURN_TYPE_FOLLOWERS) return null
        val plan = Plan(u.end).erase(s.erase)
        if (u.allErased) plan.erase(0 until u.end)
        return plan
    }

    /** A type after `(`, `,`, a modifier, an attribute or `const`: parameters, properties, promoted parameters. */
    private fun tryTypePosition(c: Tok): Plan? {
        if (!isTypePredecessor() || !canStartType(c)) return null
        val s = Scan()
        val u = scanUnion(0, s) ?: return null
        if (s.extraClosers > 0 || s.erase.isEmpty()) return null
        val follower = typeAt(sig(u.end))
        // A typed class constant is followed by its name.
        val ok = follower in TYPE_FOLLOWERS || prevSig == PhpTokenTypes.kwCONST && follower == PhpTokenTypes.IDENTIFIER
        if (!ok) return null
        val plan = Plan(u.end).erase(s.erase)
        if (u.allErased) plan.erase(0 until u.end)
        return plan
    }

    /** `Name<…>` anywhere else: `new Box<int>(…)`, `f<int>(…)`, `->m<T>(…)`, `implements Foo<T>`. */
    private fun tryTypeArguments(c: Tok): Plan? {
        // A member named with a reserved word, `Scenario::for<T>(…)`, reaches us as the keyword.
        val named = c.type == PhpTokenTypes.IDENTIFIER || c.type in PhpTokenTypes.tsKEYWORDS && prevSig in MEMBER_ACCESS
        if (!named) return null
        val lt = sig(1)
        if (typeAt(lt) != PhpTokenTypes.opLESS) return null
        val s = Scan()
        val end = scanTypeArguments(lt, s, declaration = false) ?: return null
        if (s.extraClosers > 0) return null
        return Plan(end).erase(lt until end)
    }

    private fun isTypePredecessor(): Boolean {
        val p = prevSig ?: return false
        return p in TYPE_PREDECESSORS || p == PhpTokenTypes.IDENTIFIER && prevSigText == "readonly"
    }

    private fun canStartType(c: Tok): Boolean = c.type in TYPE_START

    // ---------------------------------------------------------------- the type scanner

    private class Scan {
        val erase = ArrayList<IntRange>()
        val names = ArrayList<String>()
        /** A `>>` closed one more list than the one that consumed it. */
        var extraClosers = 0
    }

    private class Member(val start: Int, val end: Int, val native: Boolean, val classLike: Boolean)

    private class Union(val end: Int, val members: List<Member>, val nullable: Boolean, val allErased: Boolean)

    /**
     * Scans a type at [i0]: `?T`, `A|B`, `A&B`, DNF groups, tuples, generics. Returns null when the
     * tokens are not a type. Phaenon-only members are recorded for erasure together with one
     * separator each; when every member is Phaenon-only the whole type, `?` included, is.
     */
    private fun scanUnion(i0: Int, s: Scan): Union? {
        if (i0 < 0) return null
        var i = i0
        var nullable = false
        if (typeAt(i) == PhpTokenTypes.opQUEST) {
            nullable = true
            i = sig(i + 1)
        }
        val members = ArrayList<Member>()
        val separators = ArrayList<Int>()
        while (true) {
            val m = scanAtom(i, s) ?: return null
            members.add(m)
            if (s.extraClosers > 0) break
            val sep = sig(m.end)
            val sepType = typeAt(sep)
            if (sepType == PhpTokenTypes.opBIT_OR) {
                separators.add(sep)
                i = sig(sep + 1)
            } else if (sepType == PhpTokenTypes.opBIT_AND && typeAt(sig(sep + 1)) !in BY_REF_FOLLOWERS) {
                separators.add(sep)
                i = sig(sep + 1)
            } else {
                break
            }
        }
        val end = members.last().end
        val allErased = members.none { it.native }
        if (allErased) {
            s.erase.add(i0 until end)
        } else {
            for ((k, m) in members.withIndex()) {
                if (m.native) continue
                if (k < separators.size) s.erase.add(m.start..separators[k]) else s.erase.add(separators[k - 1] until m.end)
            }
        }
        return Union(end, members, nullable, allErased)
    }

    private fun scanAtom(i: Int, s: Scan): Member? {
        if (i < 0) return null
        val t = tok(i) ?: return null
        when (t.type) {
            PhpTokenTypes.chLPAREN -> {
                var tuple = false
                var native = true
                var j = sig(i + 1)
                while (true) {
                    val u = scanUnion(j, s) ?: return null
                    if (s.extraClosers > 0) return null
                    if (u.allErased) native = false
                    val next = sig(u.end)
                    if (typeAt(next) == PhpTokenTypes.opCOMMA) {
                        tuple = true
                        j = sig(next + 1)
                        continue
                    }
                    if (typeAt(next) != PhpTokenTypes.chRPAREN) return null
                    return Member(i, next + 1, native && !tuple, classLike = false)
                }
            }
            PhpTokenTypes.kwLIST -> {
                val lt = sig(i + 1)
                if (typeAt(lt) != PhpTokenTypes.opLESS) return null
                val end = scanTypeArguments(lt, s, declaration = false) ?: return null
                return Member(i, end, native = false, classLike = false)
            }
            PhpTokenTypes.kwCLASS -> {
                val lt = sig(i + 1)
                if (typeAt(lt) != PhpTokenTypes.opLESS) return Member(i, i + 1, native = false, classLike = false)
                val end = scanTypeArguments(lt, s, declaration = false) ?: return null
                return Member(i, end, native = false, classLike = false)
            }
            PhpTokenTypes.kwARRAY, PhpTokenTypes.kwCALLABLE, PhpTokenTypes.kwSTATIC -> {
                // `array<K, V>` is the M3 spelling still found in migrated sources: PHP's array, arguments erased.
                val lt = sig(i + 1)
                if (t.type == PhpTokenTypes.kwARRAY && typeAt(lt) == PhpTokenTypes.opLESS) {
                    val end = scanTypeArguments(lt, s, declaration = false) ?: return null
                    s.erase.add(lt until end)
                    return Member(i, end, native = true, classLike = false)
                }
                return Member(i, i + 1, native = true, classLike = false)
            }
            PhpTokenTypes.IDENTIFIER, PhpTokenTypes.NAMESPACE_RESOLUTION -> {
                // A qualified name: tokens are adjacent, no whitespace inside.
                var j = i
                if (t.type == PhpTokenTypes.NAMESPACE_RESOLUTION) {
                    j++
                    if (typeAt(j) != PhpTokenTypes.IDENTIFIER) return null
                }
                while (typeAt(j + 1) == PhpTokenTypes.NAMESPACE_RESOLUTION && typeAt(j + 2) == PhpTokenTypes.IDENTIFIER) j += 2
                val name = buffer.subSequence(t.start, tok(j)!!.end).toString()
                val simple = name.substringAfterLast('\\')
                val next = sig(j + 1)
                if ((name == "Closure" || name == "\\Closure") && (typeAt(next) == PhpTokenTypes.chLPAREN || typeAt(next) in PhpTokenTypes.tsCAST_OPS)) {
                    val end = scanClosureSignature(next, s) ?: return null
                    // A bare `Closure(…)` is `\Closure` in any namespace (the compiler's parser reads
                    // `Closure(` as a type form and the emitter writes `\Closure`), but a `Closure`
                    // left to PhpStorm resolves in the file's namespace: erased whole, like `list<…>`,
                    // and typed back by PhaenonTypeText. `\Closure(…)` means the same in PHP's reading.
                    if (name == "Closure") return Member(i, end, native = false, classLike = false)
                    s.erase.add(next until end)
                    return Member(i, end, native = true, classLike = false)
                }
                if (typeAt(next) == PhpTokenTypes.opLESS) {
                    val end = scanTypeArguments(next, s, declaration = false) ?: return null
                    val generic = simple == "map" || name in typeParams
                    if (!generic) s.erase.add(next until end)
                    return Member(i, end, native = !generic, classLike = !generic && simple !in PRIMITIVE_NAMES)
                }
                val erased = name == "bytes" || name in typeParams
                return Member(i, j + 1, native = !erased, classLike = !erased && simple !in PRIMITIVE_NAMES)
            }
            else -> return null
        }
    }

    /**
     * `(A, B=, C...): R` after `Closure`; returns the index after the return type (or after `)`).
     * A parameter's `=` (optional) or `...` (variadic) marker is part of the signature. A single
     * primitive parameter, `Closure(int)`, reaches us as one cast token.
     */
    private fun scanClosureSignature(open: Int, s: Scan): Int? {
        if (typeAt(open) in PhpTokenTypes.tsCAST_OPS) return scanClosureReturnType(open + 1, s)
        var j = sig(open + 1)
        if (typeAt(j) != PhpTokenTypes.chRPAREN) {
            while (true) {
                val u = scanUnion(j, s) ?: return null
                if (s.extraClosers > 0) return null
                var next = sig(u.end)
                if (typeAt(next) == PhpTokenTypes.opVARIADIC || typeAt(next) == PhpTokenTypes.opASGN) next = sig(next + 1)
                if (typeAt(next) == PhpTokenTypes.opCOMMA) {
                    j = sig(next + 1)
                    continue
                }
                if (typeAt(next) != PhpTokenTypes.chRPAREN) return null
                j = next
                break
            }
        }
        return scanClosureReturnType(j + 1, s)
    }

    /** The optional `: R` of a closure signature; [end] is the index after the `)`. */
    private fun scanClosureReturnType(end: Int, s: Scan): Int? {
        val colon = sig(end)
        if (typeAt(colon) != PhpTokenTypes.opCOLON) return end
        val r = scanUnion(sig(colon + 1), s) ?: return null
        if (s.extraClosers > 0) return null
        return r.end
    }

    /**
     * `<A, B of Bound = Default>` starting at [lt]; returns the index after the closing `>`. A
     * `>>` closing two lists at once is consumed by the inner list, which leaves a closer for
     * the outer one in [Scan.extraClosers].
     */
    private fun scanTypeArguments(lt: Int, s: Scan, declaration: Boolean): Int? {
        var j = sig(lt + 1)
        while (true) {
            if (isIdent(j, "in") || isIdent(j, "out")) j = sig(j + 1)
            if (declaration && typeAt(j) == PhpTokenTypes.IDENTIFIER) s.names.add(text(tok(j)!!))
            val u = scanUnion(j, s) ?: return null
            var k = u.end
            if (s.extraClosers > 0) {
                s.extraClosers--
                return k
            }
            k = sig(k)
            if (isIdent(k, "of")) {
                val bound = scanUnion(sig(k + 1), s) ?: return null
                k = bound.end
                if (s.extraClosers > 0) {
                    s.extraClosers--
                    return k
                }
                k = sig(k)
            }
            if (typeAt(k) == PhpTokenTypes.opASGN) {
                val default = scanUnion(sig(k + 1), s) ?: return null
                k = default.end
                if (s.extraClosers > 0) {
                    s.extraClosers--
                    return k
                }
                k = sig(k)
            }
            when (typeAt(k)) {
                PhpTokenTypes.opCOMMA -> j = sig(k + 1)
                PhpTokenTypes.opGREATER -> return k + 1
                PhpTokenTypes.opSHIFT_RIGHT -> {
                    s.extraClosers++
                    return k + 1
                }
                else -> return null
            }
        }
    }

    private companion object {
        /** Trivia between significant tokens: whitespace, erased text, comments — a docblock is a token of its own, outside COMMENTS. */
        val SKIPPABLE: TokenSet = TokenSet.orSet(TokenSet.create(PhpTokenTypes.WHITE_SPACE, PhaenonTokenTypes.ERASED, PhpDocElementTypes.DOC_COMMENT), PhpTokenTypes.COMMENTS)

        /** What `$x is T` may follow: a value. */
        val EXPRESSION_END: TokenSet = TokenSet.orSet(
            TokenSet.create(
                PhpTokenTypes.VARIABLE, PhpTokenTypes.chRPAREN, PhpTokenTypes.chRBRACKET, PhpTokenTypes.IDENTIFIER,
                PhpTokenTypes.STRING_LITERAL, PhpTokenTypes.STRING_LITERAL_SINGLE_QUOTE, PhpTokenTypes.chRDOUBLE_QUOTE,
            ),
            PhpTokenTypes.tsNUMBERS,
        )

        /** What `$x as T` may follow; an identifier is left out because of `use Foo as Bar`. */
        val VALUE_END: TokenSet = TokenSet.orSet(
            TokenSet.create(
                PhpTokenTypes.VARIABLE, PhpTokenTypes.chRPAREN, PhpTokenTypes.chRBRACKET,
                PhpTokenTypes.STRING_LITERAL, PhpTokenTypes.STRING_LITERAL_SINGLE_QUOTE, PhpTokenTypes.chRDOUBLE_QUOTE,
            ),
            PhpTokenTypes.tsNUMBERS,
        )

        val STATEMENT_START: Set<IElementType?> = setOf(null, PhpTokenTypes.opSEMICOLON, PhpTokenTypes.chRBRACE, PhpTokenTypes.chLBRACE)

        /** Where a declaration may start: a statement boundary, a modifier, the `]` of an attribute. */
        val FUN_PREDECESSORS: Set<IElementType?> = STATEMENT_START + PhpTokenTypes.tsMODIFIERS.types + PhpTokenTypes.chRBRACKET

        /** `::` and `->`: after them a keyword is a member name. */
        val MEMBER_ACCESS: TokenSet = TokenSet.create(PhpTokenTypes.SCOPE_RESOLUTION, PhpTokenTypes.ARROW)

        val DECLARATION_KEYWORDS: TokenSet = TokenSet.create(
            PhpTokenTypes.kwCLASS, PhpTokenTypes.kwINTERFACE, PhpTokenTypes.kwTRAIT, PhpTokenTypes.kwENUM, PhpTokenTypes.kwFUNCTION,
        )

        val TYPE_PREDECESSORS: TokenSet = TokenSet.orSet(
            TokenSet.create(PhpTokenTypes.chLPAREN, PhpTokenTypes.opCOMMA, PhpTokenTypes.chRBRACKET, PhpTokenTypes.kwCONST),
            PhpTokenTypes.tsMODIFIERS,
        )

        val TYPE_START: TokenSet = TokenSet.create(
            PhpTokenTypes.opQUEST, PhpTokenTypes.chLPAREN, PhpTokenTypes.IDENTIFIER, PhpTokenTypes.NAMESPACE_RESOLUTION,
            PhpTokenTypes.kwLIST, PhpTokenTypes.kwCLASS, PhpTokenTypes.kwARRAY, PhpTokenTypes.kwCALLABLE, PhpTokenTypes.kwSTATIC,
        )

        /** What may follow a parameter's or property's type. */
        val TYPE_FOLLOWERS: TokenSet = TokenSet.create(
            PhpTokenTypes.VARIABLE, PhpTokenTypes.opBIT_AND, PhpTokenTypes.opVARIADIC, PhpTokenTypes.opASGN,
        )

        val RETURN_TYPE_FOLLOWERS: TokenSet = TokenSet.create(PhpTokenTypes.chLBRACE, PhpTokenTypes.opSEMICOLON, PhpTokenTypes.opHASH_ARRAY)

        /** After `&` in a type, these mean a by-reference parameter rather than an intersection. */
        val BY_REF_FOLLOWERS: TokenSet = TokenSet.create(PhpTokenTypes.VARIABLE, PhpTokenTypes.opVARIADIC)

        val PRIMITIVE_NAMES: Set<String> = setOf(
            "int", "float", "string", "bool", "null", "mixed", "void", "never", "object", "iterable", "true", "false",
            "self", "parent", "integer", "boolean", "double",
        )
    }
}

package dev.phaenon.jetbrains

import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.lang.CodeInsightActions
import com.intellij.lang.LanguageCodeInsightActionHandler
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.php.PhpClassHierarchyUtils
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.codeInsight.PhpCodeInsightUtil
import com.jetbrains.php.completion.insert.PhpOverriddenMethodInsertHandler
import com.jetbrains.php.lang.PhpLanguage
import com.jetbrains.php.lang.lexer.PhpTokenTypes
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpModifierList
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.elements.PhpTypeDeclaration
import com.jetbrains.php.lang.psi.elements.PhpUse
import com.jetbrains.php.refactoring.PhpAliasImporter

/**
 * The methods PhpStorm writes into a `.phn` class from a parent's — Code | Implement Methods,
 * Code | Override Methods, and the override and implement items of completion — in Phaenon's
 * spelling, with the parent's declared types.
 *
 * PhpStorm generates the method from the parent's PSI, where an erased type is a comment, and
 * writes PHP: `function get($id)` for `fun get(Id $id): ?T` (no visibility either, since the
 * parent's `fun` has none). The plugin lets PhpStorm generate the method as always — its member
 * chooser, its body and docblock options, its formatting and imports — and then rewrites the
 * method's head:
 *
 * - `fun` for a public method, `protected fun` for a protected one, `static` kept, as the compiler
 *   reads it and `phn migrate` writes it (design/functions.md §1);
 * - the type of every parameter and the return type the parent declared with an erased part, taken
 *   from the parent's source text (`Id $id`, `?T`, `class<T>`, `list<User>`,
 *   `Closure(string...): (int, string)`), and the parent's own type-parameter list;
 * - the parent's type parameters replaced by what the implementing class gives them: in
 *   `final readonly class UserRepository extends AbstractEventSourcedRepository<UserId, User>`,
 *   `fun get(Id $id): ?T` becomes `fun get(UserId $id): ?User`. The arguments are followed through
 *   every `extends` and `implements` between the class and the declaring one, so an interface's
 *   method implemented two levels down gets the arguments of that path. A parameter the class
 *   does not give an argument for keeps the parent's spelling;
 * - class names of the parent's text resolved in the parent's file and written as the
 *   implementing file can name them: an existing import, the short name in the same namespace, a
 *   new import, or the fully qualified name where the short one is taken.
 *
 * A type without an erased part is PhpStorm's own text, imports included. `.php` files are not
 * touched: the handlers below hand them to PhpStorm's, and completion wraps only in `.phn` files.
 */
internal object PhaenonOverrides {

    /** The head of a method that overrides [parent]: `fun`, `protected fun`, `static fun`, `protected static fun`. */
    fun head(parent: Method): String = buildString {
        if (parent.access.isProtected) append("protected ")
        if (parent.isStatic) append("static ")
        append("fun")
    }

    /** `(UserId $id): ?User`: the parameter list and return type of [parent] as an override in [implementing] spells them. */
    fun signatureText(parent: Method, implementing: PhpClass): String {
        val signature = Signature(parent, implementing, Target(implementing))
        val parameters = parent.parameters.mapIndexed { i, parameter ->
            val type = signature.parameterType(i) ?: parameterType(parameter)?.text ?: parameter.typeDeclaration?.text
            listOfNotNull(type, (if (parameter.isVariadic) "..." else "") + "$" + parameter.name).joinToString(" ")
        }
        val returnType = signature.returnType() ?: returnType(parent)?.text
        return "(" + parameters.joinToString(", ") + ")" + (returnType?.let { ": $it" } ?: "")
    }

    /**
     * Rewrites [generated], which PhpStorm generated in a `.phn` file from [parent], into Phaenon's
     * spelling (see the class comment); [rewriteHead] false keeps the modifiers and keyword the
     * developer wrote before completing the name. Runs in a write action; reformats the method.
     */
    fun rewrite(generated: Method, parent: Method, rewriteHead: Boolean) {
        val file = generated.containingFile
        val project = file.project
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(file) ?: return
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(generated)
        // PhpStorm inserted the method and its imports through the PSI; the document comes first now.
        documents.doPostponedOperationsAndUnblockDocument(document)
        documents.commitDocument(document)
        val method = pointer.element ?: return
        val implementing = method.containingClass ?: return
        val target = Target(implementing)
        val signature = Signature(parent, implementing, target)
        val edits = ArrayList<Pair<TextRange, String>>()

        // Exceptions belong in the signature (design/functions.md §4): the parent's clause, and the
        // bare `@throws` tags PhpStorm copied from a PHP parent's docblock, become a `throws` clause.
        val docComment = method.docComment
        val lifted = docComment?.getTagElementsByName("@throws")?.filter { liftable(it.text) }.orEmpty()
        val throws = LinkedHashSet<String>(signature.throwsNames())
        // A parent written in PHP declares its exceptions in its docblock, which completion's item does not copy.
        val parentTags = parent.docComment?.getTagElementsByName("@throws")?.filter { liftable(it.text) }.orEmpty()
        for (tag in lifted + parentTags) tag.text.removePrefix("@throws").trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }.forEach { throws.add(signature.spell(it, tag)) }
        val clause = if (throws.isEmpty()) "" else " throws " + throws.joinToString("|")
        val close = method.node.findChildByType(PhpTokenTypes.chRPAREN)
        val returnType = signature.returnType()
        if (close != null && (returnType != null || clause.isNotEmpty())) {
            val start = close.textRange.endOffset
            val end = method.typeDeclaration?.textRange?.endOffset ?: start
            val typeText = returnType?.let { ": $it" } ?: document.getText(TextRange(start, end))
            edits.add(TextRange(start, end) to typeText + clause)
        }
        if (docComment != null) docblockEdit(docComment, lifted.map { it.textRange }, document)?.let(edits::add)
        for ((i, parameter) in method.parameters.withIndex()) {
            val type = signature.parameterType(i) ?: continue
            val existing = parameter.typeDeclaration
            if (existing != null) {
                edits.add(existing.textRange to type)
            } else {
                val at = typeInsertionOffset(parameter) ?: continue
                edits.add(TextRange(at, at) to "$type ")
            }
        }
        val typeParameters = declarationList(parent)
        val name = method.nameIdentifier
        if (typeParameters != null && name != null && declarationList(method) == null) {
            edits.add(TextRange(name.textRange.endOffset, name.textRange.endOffset) to typeParameters.text)
        }
        if (rewriteHead) {
            val keyword = method.node.findChildByType(PhpTokenTypes.kwFUNCTION)
            if (keyword != null) {
                val modifiers = PsiTreeUtil.getChildOfType(method, PhpModifierList::class.java)?.takeIf { it.textLength > 0 }
                edits.add(TextRange(modifiers?.textRange?.startOffset ?: keyword.startOffset, keyword.textRange.endOffset) to head(parent))
            }
        }

        for ((range, text) in edits.sortedByDescending { it.first.startOffset }) document.replaceString(range.startOffset, range.endOffset, text)
        documents.commitDocument(document)

        val rewritten = pointer.element ?: return
        val scope = PhpCodeInsightUtil.findScopeForUseOperator(rewritten)
        if (scope != null) {
            for (fqn in target.imports) PhpAliasImporter.insertUseStatement(fqn, scope)
            documents.doPostponedOperationsAndUnblockDocument(document)
            documents.commitDocument(document)
        }
        pointer.element?.let { CodeStyleManager.getInstance(project).reformat(it) }
    }

    /** A `@throws` tag that is a type and nothing else, which the signature can say: `@throws A|B`. */
    private fun liftable(tag: String): Boolean = tag.removePrefix("@throws").trim().matches(Regex("""\\?[A-Za-z_][\w\\]*(\s*\|\s*\\?[A-Za-z_][\w\\]*)*"""))

    /**
     * The generated docblock in the canonical layout (design/functions.md §5): without the tags the
     * signature took over, on one line when one line of content is left, gone when none is.
     */
    private fun docblockEdit(comment: PsiElement, dropped: List<TextRange>, document: com.intellij.openapi.editor.Document): Pair<TextRange, String>? {
        val range = comment.textRange
        val content = comment.text.removePrefix("/**").removeSuffix("*/").lines().map { it.trim().removePrefix("*").trim() }
        val droppedTexts = dropped.map { document.getText(it).trim() }.toSet()
        val kept = content.filter { it.isNotEmpty() && it !in droppedTexts }
        val originalLines = content.count { it.isNotEmpty() }
        if (kept.size == originalLines && (kept.size != 1 || !comment.text.contains('\n'))) return null
        if (kept.size >= 2) {
            // Only the lifted tags' lines go.
            val text = comment.text.lines().filterNot { it.trim().removePrefix("*").trim() in droppedTexts }.joinToString("\n")
            return range to text
        }
        if (kept.isEmpty()) {
            // Nothing left to say: the docblock goes with the line it stood on.
            val lineStart = document.getLineStartOffset(document.getLineNumber(range.startOffset))
            val next = document.getLineStartOffset((document.getLineNumber(range.endOffset) + 1).coerceAtMost(document.lineCount - 1))
            val onItsOwn = document.getText(TextRange(lineStart, range.startOffset)).isBlank()
            return if (onItsOwn) TextRange(lineStart, next) to "" else range to ""
        }
        if (kept.size == 1) return range to "/** ${kept.single()} */"
        return null
    }

    /** Where a type goes in a parameter that has none: before `&`, `...` or the name. */
    private fun typeInsertionOffset(parameter: Parameter): Int? {
        val name = parameter.nameIdentifier ?: return null
        var at = name.textRange.startOffset
        var leaf: PsiElement? = PsiTreeUtil.prevLeaf(name, true)
        while (leaf != null && PsiTreeUtil.isAncestor(parameter, leaf, false)) {
            val type = PsiUtilCore.getElementType(leaf)
            if (type === PhpTokenTypes.opBIT_AND || type === PhpTokenTypes.opVARIADIC) at = leaf.textRange.startOffset
            else if (leaf !is PsiWhiteSpace) break
            leaf = PsiTreeUtil.prevLeaf(leaf, true)
        }
        return at
    }

    // ---------------------------------------------------------------- the parent's declaration

    /** A declared type as written, and whether part of it was erased. */
    private class TypeText(val text: String, val erased: Boolean, val context: PsiElement)

    /** The type of [parameter] as written, native and erased parts alike: the leaves between the modifiers and `&`/`...`/the name. */
    private fun parameterType(parameter: Parameter): TypeText? {
        val name = parameter.nameIdentifier ?: return null
        var leaf: PsiElement? = PsiTreeUtil.prevLeaf(name, true)
        while (leaf != null && (leaf is PsiWhiteSpace || PsiUtilCore.getElementType(leaf).let { it === PhpTokenTypes.opBIT_AND || it === PhpTokenTypes.opVARIADIC })) {
            leaf = PsiTreeUtil.prevLeaf(leaf, true)
        }
        var start = -1
        var end = -1
        var erased = false
        while (leaf != null) {
            val isType = when {
                PhaenonTokenTypes.isErased(leaf) -> true.also { erased = true }
                leaf is PsiWhiteSpace || leaf is PsiComment -> null
                PsiTreeUtil.getParentOfType(leaf, PhpTypeDeclaration::class.java, false)?.let { PsiTreeUtil.isAncestor(parameter, it, false) } == true -> true
                else -> false
            }
            if (isType == false) break
            if (isType == true) {
                if (end < 0) end = leaf.textRange.endOffset
                start = leaf.textRange.startOffset
            }
            leaf = PsiTreeUtil.prevLeaf(leaf, true)
        }
        if (start < 0) return null
        return TypeText(parameter.containingFile.viewProvider.contents.subSequence(start, end).toString(), erased, parameter)
    }

    /** The return type of [function] as written, without its colon. */
    private fun returnType(function: Function): TypeText? {
        val close = function.node.findChildByType(PhpTokenTypes.chRPAREN)?.psi ?: return null
        val text = function.containingFile.viewProvider.contents
        var leaf: PsiElement? = PsiTreeUtil.nextLeaf(close, true)
        var start = -1
        var end = -1
        var erased = false
        while (leaf != null) {
            val type = PsiUtilCore.getElementType(leaf)
            when {
                // The `throws` clause ends the return type; [Signature.throwsNames] carries it over.
                PhaenonErasedTypes.isThrowsClause(leaf) -> break
                PhaenonTokenTypes.isErased(leaf) -> {
                    erased = true
                    var from = leaf.textRange.startOffset
                    if (start < 0 && leaf.text.startsWith(":")) {
                        from++
                        while (from < leaf.textRange.endOffset && text[from].isWhitespace()) from++
                    }
                    if (start < 0) start = from
                    end = leaf.textRange.endOffset
                }
                leaf is PsiWhiteSpace || leaf is PsiComment || type === PhpTokenTypes.opCOLON -> Unit
                PsiTreeUtil.getParentOfType(leaf, PhpTypeDeclaration::class.java, false)?.let { PsiTreeUtil.isAncestor(function, it, true) } == true -> {
                    if (start < 0) start = leaf.textRange.startOffset
                    end = leaf.textRange.endOffset
                }
                else -> break
            }
            leaf = PsiTreeUtil.nextLeaf(leaf, true)
        }
        if (start < 0 || end <= start) return null
        return TypeText(text.subSequence(start, end).toString(), erased, function)
    }

    /** The erased `<…>` list after a function's or class's name: its type parameters. */
    private fun declarationList(declaration: PsiElement): PsiElement? {
        val name = when (declaration) {
            is Function -> declaration.nameIdentifier
            is PhpClass -> declaration.nameIdentifier
            else -> null
        } ?: return null
        var leaf: PsiElement? = PsiTreeUtil.nextLeaf(name, true)
        while (leaf is PsiWhiteSpace) leaf = PsiTreeUtil.nextLeaf(leaf, true)
        return leaf?.takeIf { PhaenonTokenTypes.isErased(it) && it.text.startsWith("<") }
    }

    private fun typeParameterNames(declaration: PsiElement): List<String> =
        declarationList(declaration)?.let { PhaenonTypeText.parameterNames(it.text) }.orEmpty()

    // ---------------------------------------------------------------- substitution

    /**
     * What the implementing class makes of the parent's declaration: the types to write, with the
     * parent's type parameters replaced. Types without an erased part are left to PhpStorm (null).
     */
    private class Signature(private val parent: Method, implementing: PhpClass, private val target: Target) {

        private val ownTypeParameters: Set<String> = typeParameterNames(parent).toSet()

        /** The parent class's type parameters → their arguments, written for the implementing file. */
        private val arguments: Map<String, String> =
            parent.containingClass?.let { typeArguments(implementing, it, target) }.orEmpty() - ownTypeParameters

        private val keep: Set<String> = ownTypeParameters + parent.containingClass?.let(::typeParameterNames).orEmpty()

        fun parameterType(index: Int): String? =
            parent.parameters.getOrNull(index)?.let(::parameterType)?.takeIf { it.erased }?.let { render(it) }

        fun returnType(): String? = returnType(parent)?.takeIf { it.erased }?.let { render(it) }

        /** The parent's `throws` clause, its names as the implementing file spells them; empty when it has none. */
        fun throwsNames(): List<String> = PhaenonErasedTypes.throwsNames(parent).map { render(it, parent, arguments, keep, target) }

        /** A name a `@throws` tag of the generated docblock gives, as the implementing file spells it. */
        fun spell(name: String, context: PsiElement): String = render(name, context, arguments, keep, target)

        private fun render(type: TypeText): String = render(type.text, type.context, arguments, keep, target)
    }

    /**
     * The type arguments [from] gives [declaring]'s type parameters along its `extends` and
     * `implements` clauses, written for [target]'s file; null when [declaring] is not a supertype.
     */
    private fun typeArguments(from: PhpClass, declaring: PhpClass, target: Target, own: Map<String, String> = emptyMap(), seen: MutableSet<String> = HashSet()): Map<String, String>? {
        if (!seen.add(from.fqn.lowercase())) return null
        for ((reference, arguments) in supertypeClauses(from)) {
            val supertype = resolveClass(reference) ?: continue
            val written = arguments.map { render(it, reference, own, typeParameterNames(from).toSet(), target) }
            val given = typeParameterNames(supertype).zip(written).toMap()
            if (supertype.fqn.equals(declaring.fqn, ignoreCase = true)) return given
            typeArguments(supertype, declaring, target, given, seen)?.let { return it }
        }
        return null
    }

    /** Each class a class extends or implements, with the type arguments written after its name. */
    private fun supertypeClauses(klass: PhpClass): List<Pair<ClassReference, List<String>>> {
        val references = klass.extendsList.referenceElements + klass.implementsList.referenceElements
        return references.map { reference ->
            var leaf: PsiElement? = PsiTreeUtil.nextLeaf(reference, true)
            while (leaf is PsiWhiteSpace) leaf = PsiTreeUtil.nextLeaf(leaf, true)
            val list = leaf?.takeIf { PhaenonTokenTypes.isErased(it) && it.text.startsWith("<") }?.text
            reference to (list?.let(::splitArguments) ?: emptyList())
        }
    }

    private fun resolveClass(reference: ClassReference): PhpClass? {
        val candidates = PhpIndex.getInstance(reference.project).getAnyByFQN(reference.fqn)
        return candidates.firstOrNull { !PhaenonFiles.isGeneratedOutput(it.containingFile) } ?: candidates.firstOrNull()
    }

    /** `<A, map<K, V>, Closure(int, string): bool>` → its top-level arguments. */
    private fun splitArguments(list: String): List<String> {
        val inner = list.trim().removePrefix("<").removeSuffix(">")
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((i, c) in inner.withIndex()) {
            when (c) {
                '<', '(', '[', '{' -> depth++
                '>', ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    out.add(inner.substring(start, i).trim())
                    start = i + 1
                }
            }
        }
        inner.substring(start).trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }

    private val NAME = Regex("""\\?[A-Za-z_][A-Za-z0-9_]*(?:\\[A-Za-z_][A-Za-z0-9_]*)*""")

    /** The words of a type that name no class. */
    private val KEYWORDS: Set<String> = setOf(
        "list", "map", "class", "bytes", "int", "float", "string", "bool", "mixed", "null", "void", "never", "object",
        "array", "callable", "iterable", "self", "static", "parent", "true", "false",
    )

    /**
     * [text], written in [context]'s file, as [target]'s file must write it: a type parameter in
     * [arguments] replaced, one in [keep] left, a class name spelled for the target.
     */
    private fun render(text: String, context: PsiElement, arguments: Map<String, String>, keep: Set<String>, target: Target): String {
        val out = StringBuilder()
        var last = 0
        for (match in NAME.findAll(text)) {
            val start = match.range.first
            if (start > 0 && (text[start - 1] == '$' || text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) continue
            val name = match.value
            val replacement = when {
                name in arguments -> arguments.getValue(name)
                name in keep || name.lowercase() in KEYWORDS -> null
                // A bare `Closure` before a signature is `\Closure` in every namespace.
                name == "Closure" && text.substring(match.range.last + 1).trimStart().startsWith("(") -> null
                context.containingFile == target.file -> null
                else -> target.spell(name, context)
            } ?: continue
            out.append(text, last, start).append(replacement)
            last = match.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    /** The file a generated method lands in, how it names classes, and the imports it will need. */
    private class Target(anchor: PsiElement) {
        val file: PsiFile = anchor.containingFile
        private val namespace: String = PsiTreeUtil.getParentOfType(anchor, PhpNamespace::class.java)?.fqn?.trim('\\').orEmpty()

        /** Alias as written → FQN with a leading backslash, of each class import of the file. */
        private val aliases: Map<String, String> = PsiTreeUtil.findChildrenOfType(file, PhpUse::class.java)
            .filter { !it.isOfConst && !it.isOfFunction && !it.isTraitImport && it.fqn.isNotEmpty() }
            .associate { (it.aliasName ?: it.fqn.substringAfterLast('\\')) to "\\" + it.fqn.trimStart('\\') }

        /** FQNs to import, in the order met. */
        val imports = LinkedHashSet<String>()

        fun spell(name: String, context: PsiElement): String? {
            val klass = PhaenonErasedNames.resolve(name, context).firstOrNull() ?: return null
            val fqn = "\\" + klass.fqn.trimStart('\\')
            val short = klass.name
            val classNamespace = klass.namespaceName.trim('\\')
            aliases.entries.firstOrNull { it.value.equals(fqn, ignoreCase = true) }?.let { return it.key }
            imports.firstOrNull { it.substringAfterLast('\\').equals(short, ignoreCase = true) }?.let { return if (it.equals(fqn, ignoreCase = true)) short else fqn }
            val taken = aliases.keys.any { it.equals(short, ignoreCase = true) }
            if (!taken && (classNamespace.equals(namespace, ignoreCase = true) || namespace.isEmpty())) return short
            if (classNamespace.isEmpty()) return fqn
            if (taken || PhpIndex.getInstance(file.project).getAnyByFQN("\\$namespace\\$short").isNotEmpty()) return fqn
            imports.add(fqn)
            return short
        }
    }

    // ---------------------------------------------------------------- entry points

    /**
     * Code | Implement Methods and Code | Override Methods in a `.phn` class: PhpStorm's action, then
     * [rewrite] of each method it added, against the method it implements or overrides.
     */
    fun generate(php: LanguageCodeInsightActionHandler, project: Project, editor: Editor, file: PsiFile) {
        val klass = classAtCaret(editor, file)
        if (klass == null) {
            php.invoke(project, editor, file)
            return
        }
        val pointers = SmartPointerManager.getInstance(project)
        val before = klass.ownMethods.map { it.name }.toSet()
        val klassPointer = pointers.createSmartPsiElementPointer(klass)

        php.invoke(project, editor, file)

        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val after = klassPointer.element ?: return
        val generated = after.ownMethods.filter { it.name !in before }.map { pointers.createSmartPsiElementPointer(it) }
        if (generated.isEmpty()) return
        ApplicationManager.getApplication().runWriteAction {
            for (pointer in generated) {
                val method = pointer.element ?: continue
                val parent = generatedFrom(method) ?: continue
                rewrite(method, parent, rewriteHead = true)
            }
        }
    }

    /**
     * The declaration PhpStorm generated [method] from: the nearest method of its name in the
     * class's supertypes, a constructor included (PhpStorm's dialogs offer the parent's).
     */
    private fun generatedFrom(method: Method): Method? {
        var found: Method? = null
        PhpClassHierarchyUtils.processSuperMethods(method) { parent, _, _ ->
            if (parent !== method && parent.containingClass != null) found = parent
            found == null
        }
        return found
    }

    /**
     * PhpStorm's handler of [actions] for PHP: the one registered after the plugin's own. The
     * platform asks only the first handler of a language, so the plugin's handler stands in for
     * PhpStorm's in every PHP file and hands it the work; it is found in the extension point
     * rather than by its class, which is the PHP plugin's own.
     */
    fun phpHandler(actions: LanguageExtension<LanguageCodeInsightActionHandler>, own: Class<*>): LanguageCodeInsightActionHandler? =
        actions.allForLanguage(PhpLanguage.INSTANCE).firstOrNull { !own.isInstance(it) }

    private fun classAtCaret(editor: Editor, file: PsiFile): PhpClass? =
        PsiTreeUtil.getParentOfType(file.findElementAt(editor.caretModel.offset), PhpClass::class.java)
            ?: PsiTreeUtil.getParentOfType(file.findElementAt((editor.caretModel.offset - 1).coerceAtLeast(0)), PhpClass::class.java)

    /**
     * PhpStorm's override or implement item of completion, made Phaenon's in a `.phn` file: shown
     * and inserted as `fun get` (or with the modifiers the parent's visibility needs) where a whole
     * declaration is completed, with the parameter list and return type an override writes; after
     * `fun` the name is completed as before. Either way the inserted method is [rewrite]n.
     * Anything else comes back unchanged.
     */
    fun decorate(element: LookupElement, implementing: PhpClass, declarationStarts: Boolean): LookupElement {
        val parent = element.`object` as? Method ?: return element
        if ((element as? LookupElementBuilder)?.insertHandler !is PhpOverriddenMethodInsertHandler) return element
        return OverrideItem(element, parent, implementing, declarationStarts)
    }

    private class OverrideItem(
        delegate: LookupElement,
        private val parent: Method,
        private val implementing: PhpClass,
        private val declarationStarts: Boolean,
    ) : LookupElementDecorator<LookupElement>(delegate) {

        private val text: String = if (declarationStarts) head(parent) + " " + parent.name else delegate.lookupString

        override fun getLookupString(): String = text

        override fun getAllLookupStrings(): Set<String> = delegate.allLookupStrings + text

        override fun renderElement(presentation: LookupElementPresentation) {
            super.renderElement(presentation)
            presentation.itemText = text
            val body = if (implementing.isInterface) "" else " {...}"
            presentation.setTailText(signatureText(parent, implementing) + body, true)
        }

        override fun handleInsert(context: InsertionContext) {
            val parentPointer = SmartPointerManager.getInstance(context.project).createSmartPsiElementPointer(parent)
            super.handleInsert(context)
            context.commitDocument()
            val method = PsiTreeUtil.getParentOfType(context.file.findElementAt(context.startOffset), Method::class.java, false) ?: return
            val parent = parentPointer.element ?: return
            rewrite(method, parent, rewriteHead = declarationStarts)
        }
    }
}

/**
 * Code | Implement Methods: PhpStorm's, in Phaenon's spelling in a `.phn` class ([PhaenonOverrides]).
 * The platform asks only the first handler of the language, so for every other PHP file this one
 * is PhpStorm's handler.
 */
class PhaenonImplementMethodsHandler : LanguageCodeInsightActionHandler {
    private val php get() = PhaenonOverrides.phpHandler(CodeInsightActions.IMPLEMENT_METHOD, javaClass)
    override fun isValidFor(editor: Editor, file: PsiFile): Boolean = php?.isValidFor(editor, file) == true
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val php = php ?: return
        if (PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)) PhaenonOverrides.generate(php, project, editor, file) else php.invoke(project, editor, file)
    }
    override fun startInWriteAction(): Boolean = php?.startInWriteAction() == true
}

/** Code | Override Methods, as [PhaenonImplementMethodsHandler] is for implementing. */
class PhaenonOverrideMethodsHandler : LanguageCodeInsightActionHandler {
    private val php get() = PhaenonOverrides.phpHandler(CodeInsightActions.OVERRIDE_METHOD, javaClass)
    override fun isValidFor(editor: Editor, file: PsiFile): Boolean = php?.isValidFor(editor, file) == true
    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val php = php ?: return
        if (PhaenonFiles.isPhaenonFile(file.viewProvider.virtualFile)) PhaenonOverrides.generate(php, project, editor, file) else php.invoke(project, editor, file)
    }
    override fun startInWriteAction(): Boolean = php?.startInWriteAction() == true
}

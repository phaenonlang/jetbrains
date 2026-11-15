package dev.phaenon.jetbrains

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.pom.PsiDeclaredTarget
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.documentation.phpdoc.psi.PhpDocType
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Function
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.Parameter
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpReference
import java.io.DataInput
import java.io.DataOutput

/**
 * The `type` aliases of a project, by qualified name (design/generics.md §11.6, §11.7).
 *
 * An alias is a name of its namespace, as a class is: `type Row = array{id: int};` in
 * `namespace App\Orders` declares `App\Orders\Row`, and another file names it through `use`, a
 * qualified name or, in the same namespace, bare. The compiler erases the declaration, and so
 * does [PhaenonLexer]: to PhpStorm the file holds a comment there, and `Row` in a signature is a
 * class nobody declared. This object is what knows better.
 *
 * - [PhaenonAliasIndex] holds every `type` declaration of the project's `.phn` files under its
 *   qualified name, lower-cased, since names are compared as class names are. A declaration is
 *   stored with what it stands for, its names already qualified in the declaring file, so that an
 *   alias is expanded without that file being parsed again.
 * - [declarations] and [namedAt] lead from a name to the declaration, as a [PhaenonTypeAlias] in the
 *   erased leaf: where navigation lands.
 * - [expanded] is the type a written type stands for once its aliases are replaced, generic ones
 *   with their arguments, spelled as a docblock spells it (`array{id: int, name?: string}`), which
 *   is what the compiler writes in the output and what PhpStorm reads a shape from.
 *
 * A class wins over an alias of its name (§11.7): where both are declared the name is the class's,
 * and nothing here answers for it. A `.php` file names no alias.
 */
object PhaenonAliases {

    /** The lower-cased key of a qualified name: no leading backslash. */
    fun key(fqn: String): String = fqn.trimStart('\\').lowercase()

    /**
     * The keys of every alias the index holds; a name not among them is no alias, which is what
     * nearly every name asked about is. Read again after any change to the project's code or files.
     */
    private fun keys(project: Project): Set<String> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val keys = HashSet<String>()
            FileBasedIndex.getInstance().processAllKeys(PhaenonAliasIndex.NAME, { keys.add(it) }, project)
            CachedValueProvider.Result.create<Set<String>>(keys, PsiModificationTracker.getInstance(project), VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS)
        }

    /** Whether the project may be asked: an index is read only once it is built. */
    private fun ready(project: Project): Boolean = !project.isDisposed && !DumbService.isDumb(project)

    /** The alias declared under [fqn], null when there is none or a class bears the name. */
    fun find(project: Project, fqn: String): PhaenonAliasIndex.Declaration? {
        if (!ready(project)) return null
        val key = key(fqn)
        if (key !in keys(project)) return null
        val found = FileBasedIndex.getInstance().getValues(PhaenonAliasIndex.NAME, key, GlobalSearchScope.allScope(project))
            .firstNotNullOfOrNull { it.firstOrNull() } ?: return null
        return found.takeIf { PhpIndex.getInstance(project).getAnyByFQN("\\$key").isEmpty() }
    }

    fun isAlias(project: Project, fqn: String): Boolean = find(project, fqn) != null

    /** Every alias of the project no class shadows, for completion: one declaration per name. */
    fun all(project: Project): List<PhaenonAliasIndex.Declaration> {
        if (!ready(project)) return emptyList()
        return keys(project).mapNotNull { find(project, it) }
    }

    /** The declarations of the alias [fqn] names, as navigation targets; several when it is declared twice. */
    fun declarations(project: Project, fqn: String): List<PhaenonTypeAlias> {
        if (find(project, fqn) == null) return emptyList()
        val targets = ArrayList<PhaenonTypeAlias>()
        val psi = PsiManager.getInstance(project)
        FileBasedIndex.getInstance().processValues(PhaenonAliasIndex.NAME, key(fqn), null, { virtual, declarations ->
            val file = psi.findFile(virtual)
            if (file != null) declarations.mapNotNullTo(targets) { PhaenonTypeAlias.at(file, it.offset) }
            true
        }, GlobalSearchScope.allScope(project))
        return targets
    }

    /**
     * The qualified name of the type named at [leaf] in a `.phn` file, as PhpStorm reads it through
     * the imports and the namespace: a class reference in a signature or a `use`, a docblock's type.
     */
    private fun nameAt(leaf: PsiElement): String? {
        if (!PhaenonErasedTypes.isPhaenon(leaf)) return null
        val reference = PsiTreeUtil.getParentOfType(leaf, false, ClassReference::class.java, PhpDocType::class.java) as? PhpReference ?: return null
        // A docblock type's own qualified name leaves the imports out, which PhpStorm applies when it resolves one.
        if (reference is PhpDocType) return reference.text.takeIf { NAME.matches(it) }?.let { PhaenonErasedNames.qualified(it, reference) }
        return reference.fqn?.takeIf { it.isNotEmpty() }
    }

    /** The alias named at [leaf], in a signature, a docblock or a `use`: a name PhpStorm resolves to no class. */
    fun namedAt(leaf: PsiElement): List<PhaenonTypeAlias> = nameAt(leaf)?.let { declarations(leaf.project, it) }.orEmpty()

    /** Whether the name at [leaf] is an alias's. */
    fun isNamedAt(leaf: PsiElement): Boolean = nameAt(leaf)?.let { isAlias(leaf.project, it) } == true

    /**
     * The type a docblock gives [declaration], as its tag spells it: `@var` over a property,
     * `@param` for a parameter, `@return` over a function; null without one. A docblock may name
     * an alias (design/collections.md §6.5), `@var list<Order>` over `private array $orders`.
     */
    fun documentedType(declaration: PhpNamedElement): String? {
        val tag: PsiElement = when (declaration) {
            is Parameter -> declaration.docTag
            is Function -> declaration.docComment?.returnTag
            else -> declaration.docComment?.varTag
        } ?: return null
        val types = PsiTreeUtil.getChildrenOfType(tag, PhpDocType::class.java)?.takeIf { it.isNotEmpty() } ?: return null
        return tag.containingFile.viewProvider.contents.subSequence(types.first().textRange.startOffset, types.last().textRange.endOffset).toString()
    }

    /** The alias a name written in an erased construct stands for (`Row` in `list<Row>`, in another alias's shape). */
    fun inErased(name: String, context: PsiElement): List<PhaenonTypeAlias> {
        val fqn = PhaenonErasedNames.qualified(name, context) ?: return emptyList()
        return declarations(context.project, fqn)
    }

    /**
     * Whether the type written for [declaration] names an alias: a parameter's or a property's
     * type, a function's return type. PhpStorm compares a value with that type as written, a class
     * of the alias's name, where the compiler compares it with what the alias stands for.
     */
    fun typesWithAlias(declaration: PhpNamedElement): Boolean {
        if (!PhaenonErasedTypes.isPhaenon(declaration)) return false
        val written = if (declaration is Function) PhaenonErasedTypes.writtenReturnType(declaration) else PhaenonErasedTypes.writtenType(declaration)
        val type = written?.let(PhaenonType::parse) ?: return false
        val qualified = PhaenonTypeText(declaration, PhaenonErasedTypes.typeParameterBounds(declaration)).qualified(type)
        return qualified.names { it.name.startsWith("\\") && isAlias(declaration.project, it.name) }
    }

    /** The erased leaves of [file] that are `type` declarations of its namespaces: none inside a function or a class. */
    fun declarationLeaves(file: PsiFile): List<PsiElement> =
        PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java).filter { leaf ->
            PhaenonTokenTypes.isErased(leaf) && PsiTreeUtil.getParentOfType(leaf, Function::class.java, PhpClass::class.java) == null &&
                PhaenonType.alias(leaf.text) != null
        }

    /**
     * [type], whose names are qualified, with every alias in it replaced by what it stands for;
     * null when it names none, which leaves the type to PhpStorm. A generic alias takes its
     * arguments, a missing one its parameter's default, else its bound, else `mixed`. An alias
     * that mentions itself, which the compiler refuses (`P2015`), is `mixed` where it comes round.
     */
    fun expanded(project: Project, type: PhaenonType): PhaenonType? {
        if (!ready(project) || keys(project).isEmpty()) return null
        var found = false
        fun expand(node: PhaenonType, visiting: Set<String>): PhaenonType = node.map { named ->
            if (!named.name.startsWith("\\")) return@map named
            val key = key(named.name)
            val declaration = find(project, named.name) ?: return@map named
            found = true
            if (key in visiting) return@map MIXED
            val body = PhaenonType.parse(declaration.type) ?: return@map MIXED
            val inside = visiting + key
            val arguments = HashMap<String, PhaenonType>()
            declaration.parameters.forEachIndexed { k, parameter ->
                arguments[parameter.name] = named.arguments.getOrNull(k)
                    ?: (parameter.default.ifEmpty { parameter.bound }).takeIf { it.isNotEmpty() }?.let(PhaenonType::parse)?.let { expand(it, inside) }
                    ?: MIXED
            }
            expand(body.map { inner -> arguments[inner.name]?.takeIf { inner.arguments.isEmpty() } ?: inner }, inside)
        }
        return expand(type, emptySet()).takeIf { found }
    }

    private val MIXED = PhaenonType.Named("mixed")

    private val NAME = Regex("""\\?[A-Za-z_][A-Za-z0-9_]*(\\[A-Za-z_][A-Za-z0-9_]*)*""")
}

/**
 * The declaration of a `type` alias: the name inside the leaf its declaration was erased into.
 * The leaf is a comment to PhpStorm, so the declaration has no element of its own; this one
 * stands in for it as a navigation target at the name's offset, as [PhaenonTypeParameter] does
 * for a type parameter.
 */
class PhaenonTypeAlias(private val leaf: PsiElement, private val offsetInLeaf: Int, private val name: String) : FakePsiElement(), PsiDeclaredTarget {

    /** The alias's qualified name, with its leading backslash. */
    val fqn: String
        get() {
            val namespace = PhaenonErasedNames.namespaceOf(leaf)?.fqn?.trim('\\').orEmpty()
            return if (namespace.isEmpty()) "\\$name" else "\\$namespace\\$name"
        }

    override fun getParent(): PsiElement = leaf

    override fun getContainingFile(): PsiFile = leaf.containingFile

    override fun getName(): String = name

    override fun getText(): String = name

    override fun getTextRange(): TextRange = TextRange.from(textOffset, name.length)

    override fun getTextOffset(): Int = leaf.textRange.startOffset + offsetInLeaf

    override fun getNavigationElement(): PsiElement = this

    override fun getLocationString(): String = fqn.trimStart('\\').substringBeforeLast('\\', "")

    /** The name, relative to this element: all of it. */
    override fun getNameIdentifierRange(): TextRange = TextRange(0, name.length)

    override fun isPhysical(): Boolean = true

    override fun isValid(): Boolean = leaf.isValid

    override fun isEquivalentTo(another: PsiElement?): Boolean = this == another

    override fun equals(other: Any?): Boolean = other is PhaenonTypeAlias && other.leaf == leaf && other.offsetInLeaf == offsetInLeaf

    override fun hashCode(): Int = 31 * leaf.hashCode() + offsetInLeaf

    companion object {
        /** The alias whose name stands at [offset] of [file], null when no `type` declaration does. */
        fun at(file: PsiFile, offset: Int): PhaenonTypeAlias? {
            val leaf = file.findElementAt(offset)?.takeIf { PhaenonTokenTypes.isErased(it) } ?: return null
            return of(leaf)?.takeIf { it.textOffset == offset }
        }

        /** The alias an erased leaf declares, null when the leaf is no `type` declaration. */
        fun of(leaf: PsiElement): PhaenonTypeAlias? {
            val alias = PhaenonType.alias(leaf.text) ?: return null
            return PhaenonTypeAlias(leaf, alias.offset, alias.name)
        }
    }
}

/**
 * The index behind [PhaenonAliases]: each `type` declaration of a `.phn` file under its qualified
 * name, lower-cased and without the leading backslash. A file may declare a name twice, which is
 * the compiler's error to report (`P2511`), so a key's value is a list.
 *
 * A declaration is read from the file's own tree, where [PhaenonLexer] left it as an erased leaf,
 * and only in a file whose text may hold one: `type`, a name, then `=` or `<`, the test the
 * compiler makes before it parses a source for its aliases (§11.7). What the alias stands for is
 * stored with its names qualified as the declaring file reads them, through its imports and its
 * namespace, so the text means the same wherever it is expanded.
 */
class PhaenonAliasIndex : FileBasedIndexExtension<String, List<PhaenonAliasIndex.Declaration>>() {

    /** A type parameter of an alias: its name, and its bound and default as qualified type text, empty when it has none. */
    data class Parameter(val name: String, val bound: String, val default: String)

    /**
     * One `type` declaration: its qualified name as written, with the leading backslash, the offset
     * of the name in the file, the parameters, and the type as qualified text (empty when it does
     * not parse).
     */
    data class Declaration(val fqn: String, val offset: Int, val parameters: List<Parameter>, val type: String) {
        val name: String get() = fqn.substringAfterLast('\\')

        /** The namespace, without a backslash at either end; empty for the global one. */
        val namespace: String get() = fqn.trimStart('\\').substringBeforeLast('\\', "")
    }

    override fun getName(): ID<String, List<Declaration>> = NAME

    override fun getVersion(): Int = VERSION

    override fun dependsOnFileContent(): Boolean = true

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        object : DefaultFileTypeSpecificInputFilter(PhpFileType.INSTANCE) {
            override fun acceptInput(file: VirtualFile): Boolean = PhaenonFiles.isPhaenonFile(file)
        }

    override fun getIndexer(): DataIndexer<String, List<Declaration>, FileContent> = DataIndexer { content ->
        if (!MAY_DECLARE.containsMatchIn(content.contentAsText)) return@DataIndexer emptyMap()
        val declared = HashMap<String, MutableList<Declaration>>()
        for (leaf in PhaenonAliases.declarationLeaves(content.psiFile)) {
            val alias = PhaenonType.alias(leaf.text) ?: continue
            val own = alias.parameters.map { it.name }.toSet()
            fun qualified(type: PhaenonType?): String = type?.map { named -> qualify(named, own, leaf) }?.text().orEmpty()
            val parameters = alias.parameters.map { Parameter(it.name, qualified(it.bound), qualified(it.default)) }
            val fqn = PhaenonTypeAlias(leaf, alias.offset, alias.name).fqn
            declared.getOrPut(PhaenonAliases.key(fqn)) { ArrayList() }
                .add(Declaration(fqn, leaf.textRange.startOffset + alias.offset, parameters, qualified(alias.type)))
        }
        declared
    }

    /** A name of an alias's type as the declaring file reads it: a keyword or one of the alias's own parameters as written, anything else qualified. */
    private fun qualify(named: PhaenonType.Named, parameters: Set<String>, context: PsiElement): PhaenonType {
        if (!named.qualified && named.name in parameters) return named
        val fqn = PhaenonErasedNames.qualified(named.name, context) ?: return named
        return named.copy(name = fqn)
    }

    override fun getValueExternalizer(): DataExternalizer<List<Declaration>> = object : DataExternalizer<List<Declaration>> {
        override fun save(out: DataOutput, value: List<Declaration>) {
            out.writeInt(value.size)
            for (declaration in value) {
                out.writeUTF(declaration.fqn)
                out.writeInt(declaration.offset)
                out.writeInt(declaration.parameters.size)
                for (parameter in declaration.parameters) {
                    out.writeUTF(parameter.name)
                    writeText(out, parameter.bound)
                    writeText(out, parameter.default)
                }
                writeText(out, declaration.type)
            }
        }

        override fun read(input: DataInput): List<Declaration> = List(input.readInt()) {
            val fqn = input.readUTF()
            val offset = input.readInt()
            val parameters = List(input.readInt()) { Parameter(input.readUTF(), readText(input), readText(input)) }
            Declaration(fqn, offset, parameters, readText(input))
        }

        /** A type's text has no bound on its length, which `writeUTF` has. */
        private fun writeText(out: DataOutput, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun readText(input: DataInput): String = ByteArray(input.readInt()).also { input.readFully(it) }.toString(Charsets.UTF_8)
    }

    companion object {
        val NAME: ID<String, List<Declaration>> = ID.create("dev.phaenon.aliases")

        /**
         * Bumped when what is stored for a file changes. [PhaenonFileElementType.PSI_VERSION] is
         * part of it: the declarations are read from the tree the lexer makes.
         */
        private const val VERSION = 1 + 1000 * PhaenonFileElementType.PSI_VERSION

        /** What a file that declares an alias holds: `type`, a name, then `=` or `<`, blanks and comments between them. */
        private val MAY_DECLARE = Regex("""(?is)(?<![\w$\\>:])type(?:\s|/\*.*?\*/|//[^\n]*|#[^\n]*)+[a-z_]\w*(?:\s|/\*.*?\*/|//[^\n]*|#[^\n]*)*[=<]""")
    }
}

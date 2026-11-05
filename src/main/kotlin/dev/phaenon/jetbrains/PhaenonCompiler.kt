package dev.phaenon.jetbrains

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The project's Phaenon settings: which `phn` runs (empty: found as [PhaenonCompiler.binary] says)
 * and whether the language server builds the outputs on save (design/tooling.md §6.2: on by
 * default).
 */
@Service(Service.Level.PROJECT)
@State(name = "Phaenon", storages = [Storage("phaenon.xml")])
class PhaenonSettings : PersistentStateComponent<PhaenonSettings.State> {
    class State {
        var binary: String = ""
        var buildOnSave: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var binary: String
        get() = state.binary
        set(value) {
            state.binary = value
        }

    var buildOnSave: Boolean
        get() = state.buildOnSave
        set(value) {
            state.buildOnSave = value
        }

    companion object {
        @JvmStatic
        fun of(project: Project): PhaenonSettings = project.service()
    }
}

/** Settings | Languages & Frameworks | Phaenon. */
class PhaenonSettingsConfigurable(private val project: Project) : BoundConfigurable("Phaenon") {
    override fun createPanel() = panel {
        val settings = PhaenonSettings.of(project)
        row("phn binary:") {
            textField().bindText(settings::binary).comment(
                "Empty: \$PHN_BINARY, else the project's vendor/bin/phn, else phn on the PATH.",
            )
        }
        row {
            checkBox("Build the outputs on save").bindSelected(settings::buildOnSave)
                .comment("The language server compiles the project after each save, as phn build does.")
        }
    }

    override fun apply() {
        super.apply()
        PhaenonRestart.languageServer(project)
    }
}

/** Restarting what reads the settings; the LSP integration registers itself when the IDE has LSP. */
object PhaenonRestart {
    @Volatile
    var languageServer: (Project) -> Unit = {}
}

/**
 * The project's compiler, `phn`, and the project around a `.phn`.
 */
object PhaenonCompiler {

    private val log = logger<PhaenonCompiler>()

    /**
     * The directory of the project a file belongs to: the nearest above it with a `phaenon.json`,
     * else with a `composer.json`, else the project's directory.
     */
    @JvmStatic
    fun root(project: Project, file: VirtualFile?): VirtualFile? {
        for (marker in listOf("phaenon.json", "composer.json")) {
            var dir = file?.parent
            while (dir != null) {
                if (dir.findChild(marker) != null) return dir
                dir = dir.parent
            }
        }
        return project.guessProjectDir()
    }

    /**
     * Whether a file is installed code: under a `vendor` directory that sits beside a
     * `composer.json`. The project around that directory reads such a file as vendor code. Opened in
     * the project's server, the file would be one of the project's own and declare its classes
     * twice; a server of the package's own would check it without its dependencies, which are
     * installed beside it. The VS Code extension applies the same rule.
     */
    @JvmStatic
    fun isVendorCode(file: VirtualFile): Boolean {
        var dir = file.parent
        while (dir != null) {
            val up = dir.parent ?: return false
            if (dir.name == "vendor" && up.findChild("composer.json") != null) return true
            dir = up
        }
        return false
    }

    /**
     * The `phn` to run in [root]: the setting; else `$PHN_BINARY`; else the project's
     * `vendor/bin/phn`, the compiler the project builds with (design/tooling.md §6.1); else `phn`
     * on the PATH. Null when there is none.
     */
    @JvmStatic
    fun binary(project: Project, root: VirtualFile?): String? {
        PhaenonSettings.of(project).binary.trim().takeIf { it.isNotEmpty() }?.let { return it }
        System.getenv("PHN_BINARY")?.takeIf { it.isNotBlank() }?.let { return it }
        if (root != null) {
            val launcher = if (SystemInfo.isWindows) "vendor/bin/phn.bat" else "vendor/bin/phn"
            File(root.path, launcher).takeIf { it.isFile }?.let { return it.path }
        }
        return PathEnvironmentVariableUtil.findInPath(if (SystemInfo.isWindows) "phn.exe" else "phn")?.path
    }

    /**
     * Phaenon's layout of a `.phn`'s text (`phn fmt --stdin`, the first layer of phn fmt), or
     * null when no `phn` is found, it fails (a text that does not parse), or it takes longer than
     * [timeoutMs].
     */
    @JvmStatic
    fun layout(project: Project, file: VirtualFile?, text: String, timeoutMs: Int = 5000): String? {
        val root = root(project, file)
        val binary = binary(project, root) ?: return null
        return try {
            val command = GeneralCommandLine(binary, "fmt", "--stdin").withCharset(StandardCharsets.UTF_8)
            root?.takeIf { File(it.path).isDirectory }?.let { command.withWorkDirectory(it.path) }
            val handler = CapturingProcessHandler(command)
            handler.processInput.use { it.write(text.toByteArray(StandardCharsets.UTF_8)) }
            val out = handler.runProcess(timeoutMs)
            if (out.isTimeout || out.exitCode != 0) {
                if (!out.isTimeout) log.debug("phn fmt --stdin: ${out.stderr}")
                null
            } else {
                out.stdout
            }
        } catch (e: Exception) {
            log.warn("phn fmt --stdin: $binary", e)
            null
        }
    }
}

package dev.phaenon.jetbrains

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspHoverDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import com.intellij.util.Consumer
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets

/**
 * `phn lsp` in PhpStorm (design/tooling.md §6.2): the checker's findings in the editor, beside the
 * plugin's PSI, which keeps navigation, completion, refactoring, formatting and debugging.
 *
 * The server is asked for diagnostics and their quick-fixes only (`"features": "diagnostics"`):
 * it then advertises nothing else, so PhpStorm, whatever its version enables by default, never asks
 * it what the PSI answers. It is the project's own compiler ([PhaenonCompiler.binary]), started
 * when a `.phn` opens, and with build on save on ([PhaenonSettings]) it writes the outputs after
 * each save; the status bar shows the last build.
 *
 * One server runs per project, in its directory ([serverRoot]), and each takes its own project's
 * files only: a project inside another's directory gets a server of its own, whose files the outer
 * one would otherwise check too, and installed code gets none.
 */
class PhaenonLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        val root = serverRoot(project, file)?.takeIf { java.io.File(it.path).isDirectory } ?: return
        val binary = PhaenonCompiler.binary(project, root) ?: return
        serverStarter.ensureServerStarted(PhaenonLspServerDescriptor(project, root, binary))
    }

    companion object {
        /**
         * The directory of the server a file belongs to: its project's ([PhaenonCompiler.root]).
         * Null for a file that is not a `.phn`, and for installed code
         * ([PhaenonCompiler.isVendorCode]), which the PSI alone reads.
         */
        @JvmStatic
        fun serverRoot(project: Project, file: VirtualFile): VirtualFile? =
            if (!PhaenonFiles.isPhaenonFile(file) || PhaenonCompiler.isVendorCode(file)) null
            else PhaenonCompiler.root(project, file)

        init {
            PhaenonRestart.languageServer = { project ->
                LspServerManager.getInstance(project).stopAndRestartIfNeeded(PhaenonLspServerSupportProvider::class.java)
            }
        }

    }
}

class PhaenonLspServerDescriptor(project: Project, private val root: VirtualFile, private val binary: String) :
    ProjectWideLspServerDescriptor(project, "Phaenon") {

    // PhpStorm offers a file to every server whose descriptor supports it: this one takes its own
    // project's.
    override fun isSupportedFile(file: VirtualFile): Boolean =
        PhaenonLspServerSupportProvider.serverRoot(project, file) == root

    override fun createCommandLine(): GeneralCommandLine =
        GeneralCommandLine(binary, "lsp").withWorkDirectory(root.path).withCharset(StandardCharsets.UTF_8)

    override fun createInitializationOptions(): Any =
        mapOf("features" to "diagnostics", "buildOnSave" to PhaenonSettings.of(project).buildOnSave)

    override fun getLanguageId(file: VirtualFile): String = "phaenon"

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient = PhaenonLspClient(handler, project)

    // What the PSI answers. The server does not advertise these either.
    override val lspCustomization: LspCustomization get() = object : LspCustomization() {
        override val goToDefinitionCustomizer get() = LspGoToDefinitionDisabled
        override val hoverCustomizer get() = LspHoverDisabled
        override val completionCustomizer get() = LspCompletionDisabled
        override val formattingCustomizer get() = LspFormattingDisabled
        override val semanticTokensCustomizer get() = LspSemanticTokensDisabled
    }

    override fun equals(other: Any?): Boolean =
        other is PhaenonLspServerDescriptor && other.root == root && other.binary == binary
    override fun hashCode(): Int = root.hashCode() * 31 + binary.hashCode()
}

/** The client side of `phaenon/build`, the server's notification after each build on save. */
class PhaenonLspClient(handler: LspServerNotificationsHandler, private val project: Project) : Lsp4jClient(handler) {
    @JsonNotification("phaenon/build")
    fun build(params: JsonObject) {
        PhaenonBuildStatus.of(project).update(PhaenonBuild.of(params))
    }
}

/** A build on save, as the server reports it. */
data class PhaenonBuild(val files: Int, val written: Int, val withheld: Int, val durationMs: Long, val error: String?) {
    /** The status bar's text. */
    val text: String
        get() = when {
            error != null -> "phn: build failed"
            withheld > 0 -> "phn: $withheld of $files withheld"
            else -> "phn: built"
        }

    val tooltip: String
        get() = error?.let { "The last build on save failed: $it" }
            ?: buildString {
                append("Last build on save: $files files")
                if (written > 0) append(", $written outputs written")
                if (withheld > 0) append(", $withheld with errors not compiled")
                append(" (${durationMs} ms)")
            }

    companion object {
        fun of(params: JsonObject): PhaenonBuild {
            fun int(key: String) = params.get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            return PhaenonBuild(int("files").toInt(), int("written").toInt(), int("withheld").toInt(), int("durationMs"),
                params.get("error")?.takeIf { it.isJsonPrimitive }?.asString)
        }
    }
}

/** The project's last build on save, shown by [PhaenonBuildWidget]. */
@Service(Service.Level.PROJECT)
class PhaenonBuildStatus(private val project: Project) {
    @Volatile
    var last: PhaenonBuild? = null
        private set

    fun update(build: PhaenonBuild) {
        last = build
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) WindowManager.getInstance().getStatusBar(project)?.updateWidget(PhaenonBuildWidget.ID)
        }
    }

    companion object {
        @JvmStatic
        fun of(project: Project): PhaenonBuildStatus = project.service()
    }
}

class PhaenonBuildWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = PhaenonBuildWidget.ID
    override fun getDisplayName(): String = "Phaenon build"
    override fun isAvailable(project: Project): Boolean = PhaenonSettings.of(project).buildOnSave
    override fun createWidget(project: Project): StatusBarWidget = PhaenonBuildWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class PhaenonBuildWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    override fun ID(): String = ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText(): String = PhaenonBuildStatus.of(project).last?.text ?: ""
    override fun getTooltipText(): String? = PhaenonBuildStatus.of(project).last?.tooltip
    override fun getAlignment(): Float = 0f
    override fun getClickConsumer(): Consumer<MouseEvent>? = null

    companion object {
        const val ID = "PhaenonBuild"
    }
}

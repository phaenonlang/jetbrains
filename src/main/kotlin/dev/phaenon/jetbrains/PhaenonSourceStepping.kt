package dev.phaenon.jetbrains

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManagerListener
import com.jetbrains.php.debug.common.PhpDebugProcess
import java.util.concurrent.atomic.AtomicReference

/**
 * Stepping by source line (design/line-map.md §4.2). Xdebug steps by the lines of the compiled
 * file, and some of those are one source line: an expression body `fun log(string $m): void =>
 * …;` stops on its statement and again on the closing brace the compiler wrote, both holds of the
 * source line. A step that lands on such a line in the same frame and the same source line as the
 * stop it started from is repeated, so the `.phn` never shows a stop on one line twice in a row.
 *
 * Only holds are skipped: a stop on a run line — the line a breakpoint targets, the line of plain
 * PHP the source wrote — is shown even when the line did not change, as in a `.php` (a loop on
 * one line stops once per iteration).
 */
object PhaenonStepping {

    /** The step to repeat; anything else that resumes (Resume, Step Out, Run to Cursor, Smart Step Into) repeats nothing. */
    enum class Step { OVER, INTO }

    /** A stop as the decision sees it: the frame's identity, the shown file and 0-based line, and whether the compiled line is a hold. */
    data class Stop(val frame: Any?, val file: String?, val line: Int, val hold: Boolean)

    /** Whether the step that went from [previous] to [current] should be repeated. */
    @JvmStatic
    fun repeat(step: Step?, previous: Stop?, current: Stop): Boolean =
        step != null && previous != null && current.hold && current.file != null &&
            current.file == previous.file && current.line == previous.line && current.frame == previous.frame

    /** The platform's debugger actions that resume a session (`XDebuggerActions`' ids), with the step each one is. */
    @JvmStatic
    val ACTIONS: Map<String, Step?> = mapOf(
        "StepOver" to Step.OVER,
        "ForceStepOver" to Step.OVER,
        "StepInto" to Step.INTO,
        "ForceStepInto" to Step.INTO,
        "SmartStepInto" to null,
        "StepOut" to null,
        "Resume" to null,
        "RunToCursor" to null,
        "ForceRunToCursor" to null,
        "Debugger.PopFrame" to null,
    )
}

/** The step the developer asked for last, until the session resumes with it. */
@Service(Service.Level.PROJECT)
class PhaenonSteppingState {

    /** A recorded request; [step] is null for a resume that is not a step. */
    class Request(val step: PhaenonStepping.Step?)

    internal val pending = AtomicReference<Request?>()
}

/** Records which debugger action resumed a session: the session itself does not say. */
class PhaenonStepActionListener : AnActionListener {
    override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
        val id = ActionManager.getInstance().getId(action) ?: return
        if (id !in PhaenonStepping.ACTIONS) return
        val project = event.project ?: return
        project.service<PhaenonSteppingState>().pending.set(PhaenonSteppingState.Request(PhaenonStepping.ACTIONS[id]))
    }
}

/** Watches every PHP debug session of the project and repeats the steps that stayed on a source line. */
class PhaenonSourceStepping(private val project: Project) : XDebuggerManagerListener {

    override fun processStarted(debugProcess: XDebugProcess) {
        if (debugProcess !is PhpDebugProcess<*>) return
        val session = debugProcess.session
        session.addSessionListener(Stepper(session, project.service()), debugProcess)
    }

    private class Stepper(private val session: XDebugSession, private val state: PhaenonSteppingState) : XDebugSessionListener {

        @Volatile private var step: PhaenonStepping.Step? = null
        @Volatile private var previous: PhaenonStepping.Stop? = null

        /** A resume that no recorded action asked for (another plugin, a programmatic call) repeats nothing. */
        override fun beforeSessionResume() {
            step = state.pending.getAndSet(null)?.step
        }

        override fun sessionPaused() {
            val frame = session.currentStackFrame ?: return
            val position = frame.sourcePosition
            val stop = PhaenonStepping.Stop(frame.equalityObject, position?.file?.url, position?.line ?: -1, (position as? PhaenonMappedPosition)?.hold == true)
            val step = step
            val repeat = PhaenonStepping.repeat(step, previous, stop)
            previous = stop
            if (!repeat) return
            ApplicationManager.getApplication().invokeLater {
                if (session.isStopped || !session.isSuspended) return@invokeLater
                state.pending.set(PhaenonSteppingState.Request(step))
                when (step) {
                    PhaenonStepping.Step.OVER -> session.stepOver(false)
                    PhaenonStepping.Step.INTO -> session.stepInto()
                    null -> {}
                }
            }
        }
    }
}

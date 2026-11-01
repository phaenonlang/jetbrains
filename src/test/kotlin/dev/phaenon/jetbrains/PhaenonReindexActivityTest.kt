package dev.phaenon.jetbrains

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.jetbrains.php.PhpIndex
import kotlin.time.Duration.Companion.seconds

/**
 * A plugin update that changes the PSI of `.phn` files must re-index them once: the platform
 * versions their stubs by the PHP file type, so it would keep the stale trees otherwise. What can
 * be checked headless is the activity's contract: which files it asks the index to redo, that it
 * stores the version it ran for, and that it does nothing when that version is already stored.
 *
 * The tests run off the EDT, as the IDE runs a project activity; the fixture is set up on the EDT.
 */
class PhaenonReindexActivityTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        PropertiesComponent.getInstance(project).unsetValue(PhaenonReindexActivity.VERSION_PROPERTY)
    }

    private fun configure(vararg paths: String) {
        runInEdtAndWait { myFixture.configureByFiles(*paths) }
    }

    private fun storeVersion(version: Int) {
        PropertiesComponent.getInstance(project).setValue(PhaenonReindexActivity.VERSION_PROPERTY, version, 0)
    }

    /** Runs the activity with a recorder in place of the index and returns the files it asked to re-index. */
    private fun reindexRequests(): List<String> {
        val requested = mutableListOf<VirtualFile>()
        timeoutRunBlocking(60.seconds) { PhaenonReindexActivity(requested::add).execute(project) }
        return requested.map { it.name }
    }

    /**
     * The IDE installs the coroutine debug probes, which read the debug metadata of every suspended
     * frame on resume; the Kotlin 2.1 library PhpStorm 2025.1 bundles rejects metadata above
     * version 1, and the coroutine then never resumes (the tests below would hang, not fail).
     * Guarded by the API version in build.gradle.kts.
     */
    fun testTheActivityCarriesCoroutineMetadataTheBundledLibraryReads() {
        val continuation = Class.forName("dev.phaenon.jetbrains.PhaenonReindexActivity\$execute\$1")
        val metadata = continuation.annotations.single { it.annotationClass.java.name == "kotlin.coroutines.jvm.internal.DebugMetadata" }
        val version = metadata.annotationClass.java.getMethod("v").invoke(metadata) as Int

        assertEquals(1, version)
    }

    fun testAProjectWithoutAStoredVersionReindexesItsPhaenonFilesAndStoresTheVersion() {
        configure("generated/Label.phn", "generated/Consumer.phn", "generated/Label.php")

        assertEquals(setOf("Label.phn", "Consumer.phn"), reindexRequests().toSet())
        assertEquals(PhaenonFileElementType.PSI_VERSION, PhaenonReindexActivity.storedVersion(project))
    }

    fun testAnOlderStoredVersionReindexes() {
        configure("generated/Label.phn")
        storeVersion(PhaenonFileElementType.PSI_VERSION - 1)

        assertEquals(listOf("Label.phn"), reindexRequests())
        assertEquals(PhaenonFileElementType.PSI_VERSION, PhaenonReindexActivity.storedVersion(project))
    }

    fun testTheCurrentVersionIsNotReindexedAgain() {
        configure("generated/Label.phn")
        PhaenonReindexActivity.rememberCurrentVersion(project)

        assertEmpty(reindexRequests())
        assertEquals(PhaenonFileElementType.PSI_VERSION, PhaenonReindexActivity.storedVersion(project))
    }

    fun testASecondStartAfterTheReindexDoesNothing() {
        configure("generated/Label.phn")

        assertEquals(listOf("Label.phn"), reindexRequests())
        assertEmpty(reindexRequests())
    }

    fun testAProjectWithoutPhaenonFilesOnlyStoresTheVersion() {
        configure("generated/Label.php")

        assertEmpty(reindexRequests())
        assertEquals(PhaenonFileElementType.PSI_VERSION, PhaenonReindexActivity.storedVersion(project))
    }

    fun testTheRealReindexRequestLeavesTheIndexUsable() {
        configure("generated/Label.phn")

        timeoutRunBlocking(60.seconds) {
            PhaenonReindexActivity().execute(project)
            val declaredIn = readAction { PhpIndex.getInstance(project).getClassesByFQN("\\App\\Label").map { it.containingFile.name } }
            assertEquals(listOf("Label.phn"), declaredIn)
        }
        assertEquals(PhaenonFileElementType.PSI_VERSION, PhaenonReindexActivity.storedVersion(project))
    }
}

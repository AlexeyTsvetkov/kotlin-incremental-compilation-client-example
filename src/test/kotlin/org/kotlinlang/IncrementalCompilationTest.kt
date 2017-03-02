package org.kotlinlang

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.incremental.ICReporter
import org.jetbrains.kotlin.incremental.makeIncrementally
import org.junit.*
import org.junit.rules.TestName

import kotlin.properties.Delegates
import java.io.File

class IncrementalCompilationTest {
    @get:Rule var testName = TestName()
    private var workingDir: File by Delegates.notNull()
    private val srcDir: File get() = File(workingDir, "src")
    private val classesDir: File get() = File(workingDir, "classes")
    private val cachesDir: File get() = File(workingDir, "ic-caches")

    @Before
    fun setUp() {
        workingDir = FileUtil.createTempDirectory("kotlin-ic-test-", testName.methodName)
        listOf(srcDir, classesDir, cachesDir).forEach { it.mkdirs() }
    }

    @After
    fun tearDown() {
        workingDir.deleteRecursively()
    }

    @Test
    fun testHelloWorld() {
        val aKt = File(srcDir, "A.kt")
        aKt.writeText("class A(val x: Int)")

        val useAKt = File(srcDir, "useA.kt")
        useAKt.writeText("""
            fun useA(a: A) {
                println("a.x is " + a.x)
            }
        """)

        val dummyKt = File(srcDir, "dummy.kt")
        dummyKt.writeText("fun dummy() {}")

        compile(debug = true).apply {
            assertFilesEquals(expected = listOf(aKt, useAKt, dummyKt),
                              actual = compiledFiles,
                              message = "After initial build")
        }

        aKt.writeText("class A(val x: Int?)")
        compile(debug = true).apply {
            assertFilesEquals(expected = listOf(aKt, useAKt),
                              actual = compiledFiles,
                              message = "After modification")
        }
    }

    private fun assertFilesEquals(expected: Iterable<File>, actual: Iterable<File>, message: String = "") {
        fun transform(files: Iterable<File>) =
                files.map { it.relativeTo(workingDir).path }.sorted().joinToString("\n")

        val expectedPaths = transform(expected)
        val actualPaths = transform(actual)

        Assert.assertEquals(message, expectedPaths, actualPaths)
    }

    private fun compile(debug: Boolean = false): CompilationResult {
        if (debug) {
            println("======= NEW COMPILATION =======")
        }

        // in case of default maven convention source roots are: src/main/java and src/main/kotlin
        val sourceRoots = listOf(srcDir)
        val args = K2JVMCompilerArguments().apply {
            destination = classesDir.canonicalPath
            moduleName = "example"
            classpath = File("build/kotlin-runtime-for-tests")
                    .listFiles()
                    .joinToString(separator = File.pathSeparator) { it.canonicalPath }
        }
        val messageCollector = PrintingMessageCollector(System.err, MessageRenderer.PLAIN_RELATIVE_PATHS, false)
        // ICReporter is needed only for debug and tests.
        val compiledFiles = hashSetOf<File>()
        val icReporter = object : ICReporter {
            override fun pathsAsString(files: Iterable<File>): String {
                return files.joinToString { it.relativeTo(workingDir).path }
            }

            override fun report(message: () -> String) {
                if (debug) {
                    println("IC: ${message()}")
                }
            }

            override fun reportCompileIteration(sourceFiles: Collection<File>, exitCode: ExitCode) {
                if (debug) {
                    println("Compiled files: ${pathsAsString(sourceFiles)}")
                }
                compiledFiles.addAll(sourceFiles)
            }
        }

        makeIncrementally(cachesDir, sourceRoots, args, messageCollector, icReporter)
        return CompilationResult(compiledFiles)
    }

    private data class CompilationResult(val compiledFiles: Set<File>)
}
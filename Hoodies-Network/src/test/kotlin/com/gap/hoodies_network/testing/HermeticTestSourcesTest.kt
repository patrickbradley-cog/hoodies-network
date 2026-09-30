package com.gap.hoodies_network.testing

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Guards the "no real-network dependency" rule for the test source sets.
 *
 * Every URL literal in `src/androidTest` and `src/test` must point at the loopback interface
 * (the in-process mock web server) unless its host is listed in [STRING_ONLY_HOSTS], which
 * are hosts that tests only parse, compare or store and never connect to.
 */
class HermeticTestSourcesTest {

    @Test
    fun testSourcesOnlyTargetLoopbackOrStringOnlyHosts() {
        val sourceFiles = TEST_SOURCE_ROOTS
            .map { File(it) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in SOURCE_EXTENSIONS }.toList() }
        assertTrue("no test sources found under $TEST_SOURCE_ROOTS", sourceFiles.isNotEmpty())

        val offenders = sourceFiles.flatMap { file ->
            file.readLines().withIndex().flatMap { (index, line) ->
                URL_LITERAL.findAll(line)
                    .map { it.groupValues[1].lowercase() }
                    .filterNot { it in LOOPBACK_HOSTS || it in STRING_ONLY_HOSTS }
                    .map { host -> "${file.path}:${index + 1}: $host" }
                    .toList()
            }
        }
        if (offenders.isNotEmpty()) {
            fail("Test sources reference non-loopback hosts:\n" + offenders.joinToString("\n"))
        }
    }

    private companion object {
        val TEST_SOURCE_ROOTS = listOf("src/androidTest", "src/test")
        val SOURCE_EXTENSIONS = setOf("kt", "java")
        val URL_LITERAL = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://([A-Za-z0-9.-]+)""")
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1")
        val STRING_ONLY_HOSTS = setOf("gap.com")
    }
}

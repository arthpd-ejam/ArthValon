package avalon.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The player UI must work with no internet: every file is bundled and nothing
 * references an external host. This scans the bundled web resources.
 */
class OfflineAssetsTest {

    private fun webDir(): File {
        val fromResource = javaClass.classLoader.getResource("web/index.html")
            ?: fail("web/index.html must be bundled")
        return File(fromResource.toURI()).parentFile
    }

    @Test
    fun `no bundled web file references an external URL`() {
        val dir = webDir()
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.any { it.name == "index.html" })
        val external = Regex("""(https?:)?//[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        val problems = mutableListOf<String>()
        for (f in files) {
            if (f.extension !in setOf("html", "css", "js", "json", "webmanifest", "svg")) continue
            f.readLines().forEachIndexed { i, line ->
                // Allow the SVG/XHTML namespace URIs and the license comment: everything else is a leak of
                // a network request.
                val cleaned = line.replace("http://www.w3.org/2000/svg", "").replace("http://www.w3.org/1999/xhtml", "")
                if (external.containsMatchIn(cleaned)) problems += "${f.name}:${i + 1}: $line"
            }
        }
        assertTrue(problems.isEmpty(), "External references found:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `index references only relative assets that exist`() {
        val dir = webDir()
        val html = File(dir, "index.html").readText()
        val refs = Regex("""(?:src|href)=["']([^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(refs.isNotEmpty())
        for (r in refs) {
            if (r.startsWith("#") || r.startsWith("data:")) continue
            assertTrue(!r.contains("://") && !r.startsWith("//"), "absolute reference: $r")
            val path = r.substringBefore("?").removePrefix("./").removePrefix("/")
            assertTrue(File(dir, path).isFile, "missing bundled file: $r")
        }
    }
}

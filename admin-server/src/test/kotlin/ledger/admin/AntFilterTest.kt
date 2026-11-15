package ledger.admin

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The launcher's filter for `data/game-server.log` (`scripts/ant-filter.awk`), run with the
 * system's awk as `./ledger up` runs it, on output shaped like Ant's.
 */
class AntFilterTest {
    private val script = Path.of("../scripts/ant-filter.awk")
    private val note = "ledger: the server has begun its own logging, in logs/default.log and logs/world_1.log; " +
        "only Ant lines are kept here from now on."

    private val preamble = listOf(
        "Buildfile: /srv/openrsc/server/build.xml",
        "",
        "runserver:",
        "     [echo] Using JVM arguments for Java 8 minimum.",
    )

    private fun filter(lines: List<String>): List<String> {
        assertTrue(Files.isRegularFile(script), "No filter at ${script.toAbsolutePath()}")
        val awk = ProcessBuilder("awk", "-v", "default_log=logs/default.log", "-v", "world_log=logs/world_1.log", "-f", script.toString())
            .redirectErrorStream(true)
            .start()
        awk.outputStream.bufferedWriter().use { it.write(lines.joinToString("\n", postfix = "\n")) }
        val output = awk.inputStream.bufferedReader().readLines()
        assertTrue(awk.waitFor(10, TimeUnit.SECONDS), "awk didn't finish")
        assertEquals(0, awk.exitValue(), output.joinToString("\n"))
        return output
    }

    @Test
    fun `keeps Ant's lines before the server's logging, then a note, then only Ant's own`() {
        val output = filter(
            preamble + listOf(
                "     [java] 2026-01-01 09:00:00 [InitThread] INFO  Server: - Launching Game Server...",
                "     [java] 2026-01-01 09:00:01 [InitThread] ERROR Server: - Something went wrong",
                "     [java] \tat org.example.Thing.run(Thing.java:12)",
                "     [java] Not a timestamped line, but still the server's",
                "     [java] Java Result: 1",
                "",
                "BUILD SUCCESSFUL",
                "Total time: 3 minutes 2 seconds",
            ),
        )
        assertEquals(
            preamble + listOf(note, "     [java] Java Result: 1", "", "BUILD SUCCESSFUL", "Total time: 3 minutes 2 seconds"),
            output,
        )
    }

    @Test
    fun `keeps everything when the server never starts logging`() {
        val failed = preamble + listOf(
            "     [java] Error: Could not find or load main class com.openrsc.server.Server",
            "     [java] Java Result: 1",
            "",
            "BUILD FAILED",
            "/srv/openrsc/server/build.xml:42: Java returned: 1",
        )
        assertEquals(failed, filter(failed))
    }

    @Test
    fun `the note comes once, however many timestamped lines follow`() {
        val output = filter(
            preamble + (0..9).map { "     [java] 2026-01-01 09:00:0$it [World] INFO  World: - tick $it" },
        )
        assertEquals(preamble + note, output)
    }

    @Test
    fun `a timestamp that isn't at the start of the server's line doesn't count`() {
        // Only "[java] <date> <time> " marks the start of the server's logging; a date later in
        // a line, or in one of Ant's own, doesn't.
        val lines = preamble + listOf(
            "     [java] Starting at 2026-01-01 09:00:00 as configured",
            "Build started 2026-01-01 09:00:00 ",
        )
        assertEquals(lines, filter(lines))
    }
}

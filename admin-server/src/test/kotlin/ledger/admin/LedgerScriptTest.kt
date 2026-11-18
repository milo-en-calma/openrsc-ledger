package ledger.admin

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.roundToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `ledger` script's own functions, run in bash: its function definitions are taken from the
 * script and the tests call them, with only what would touch the machine (ports, the ledger's
 * health check, the browser, settings) replaced by fakes in a temporary folder. Nothing here
 * starts a real game server or ledger: `ant`, `java` and `ledger-admin` are small fake scripts.
 */
class LedgerScriptTest {
    private val dir = createTempDirectory("ledger-script").toRealPath()
    private val script = Path.of("../ledger").toAbsolutePath().normalize()

    /** Everything in the script before it acts on its arguments: its settings, globals and functions. */
    private val definitions: String = Files.readString(script).substringBefore("\ncase \"\${1:-}\" in").also {
        check(it.contains("/usr/libexec/java_home")) { "find_java no longer asks /usr/libexec/java_home" }
    }

    /** Where the fake macOS `java_home` lives; the script's `/usr/libexec/java_home` is pointed here. */
    private val javaHomeTool = dir.resolve("java_home")

    private fun system(name: String): Path = listOf("/usr/bin/$name", "/bin/$name").map { Path.of(it) }.first { it.exists() }

    /** The real `sleep`, which the fakes call by its full path, as their lives are counted in real time. */
    private val realSleep = system("sleep")

    /**
     * The commands the script needs, linked one by one, so no system `java` or `ant` is found by
     * accident. Its `sleep` sleeps a fiftieth of what it's asked, so the script's once-a-second
     * polls cost the tests 0.02 seconds each.
     */
    private val tools = dir.resolve("tools").also { tools ->
        tools.createDirectories()
        for (name in listOf("awk", "cat", "dirname", "head", "mkdir", "nohup", "readlink", "rm", "sed", "tail", "touch")) {
            Files.createSymbolicLink(tools.resolve(name), system(name))
        }
        executable(tools.resolve("sleep"), "#!/bin/bash\nexec $realSleep \"$(${system("awk")} -v s=\"$1\" 'BEGIN { print s / 50 }')\"\n")
    }

    private val fakes = dir.resolve("fakes").also { it.createDirectories() }
    private val ports = dir.resolve("ports").also { it.createDirectories() }
    private val pids = dir.resolve("pids").also { it.createDirectories() }
    private val stop = dir.resolve("stop")

    private val gamePort = 43001
    private val ledgerPort = 43002

    /** The Java 25 the stubbed load_settings hands `up`. */
    private val java25 = javaHome("25", "25.0.1")

    @AfterTest
    fun stopFakes() {
        stop.writeText("")
        pids.toFile().listFiles()?.forEach { file ->
            file.readText().trim().toLongOrNull()?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    // --- how close each test came to its limits, printed to its JUnit report ---

    /** Each script run's time in milliseconds, with the seconds it was allowed. */
    private val scriptRuns = mutableListOf<Pair<Long, Int>>()

    /** How many polls the fake ledger lives for, when it stops by itself after answering. */
    private var lifePolls: Int? = null

    private fun millisSince(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    @AfterTest
    fun printMargins() {
        scriptRuns.maxByOrNull { (millis, limit) -> millis.toDouble() / limit }?.let { (millis, limit) ->
            val which = if (scriptRuns.size == 1) "The one script run" else "The slowest of ${scriptRuns.size} script runs"
            println("$which took $millis ms (limit ${limit * 1_000} ms)")
        }
        lifePolls?.let { polls ->
            for (seenAt in lines("health-seen-at")) {
                println(if (seenAt == "never") "The script never saw the fake ledger answer in its $polls polls" else "The script saw the fake ledger answer by its poll $seenAt of $polls")
            }
        }
    }

    // --- fakes ---

    private fun executable(path: Path, text: String): Path {
        path.parent.createDirectories()
        path.writeText(text)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }

    /** A folder holding a fake `bin/java` whose `-version` prints [version] the way Java does, to stderr. */
    private fun javaHome(name: String, version: String): Path {
        val home = dir.resolve("jdks/$name")
        executable(home.resolve("bin/java"), "#!/bin/bash\necho 'openjdk version \"$version\" 2025-09-16' >&2\necho 'OpenJDK Runtime Environment' >&2\n")
        return home
    }

    /** A fake `/usr/libexec/java_home` that answers every question with [home] and exit 0, or exit 1 if null. */
    private fun javaHomeToolAnswers(home: Path?) {
        executable(javaHomeTool, if (home == null) "#!/bin/bash\necho 'Unable to locate a Java Runtime.' >&2\nexit 1\n" else "#!/bin/bash\necho '$home'\n")
    }

    /**
     * A fake `ant`: records where and how it was run and with which JAVA_HOME, then, like the game
     * server, opens the game port after [delaySeconds] and keeps running until the test ends. With
     * [fails], it exits instead, never opening the port.
     */
    private fun fakeAnt(delaySeconds: Int = 0, fails: Boolean = false) = executable(
        fakes.resolve("ant"),
        """
        #!/bin/bash
        echo "cwd=${'$'}PWD args=${'$'}* JAVA_HOME=${'$'}{JAVA_HOME:-}" >>"$dir/ant-calls"
        echo ${'$'}${'$'} >"$pids/ant"
        echo "BUILD STARTED"
        $realSleep $delaySeconds
        ${if (fails) "exit 1" else ""}
        touch "$ports/$gamePort"
        while [ ! -e "$stop" ]; do $realSleep $FAKE_POLL_SECONDS; done

        """.trimIndent(),
    )

    /**
     * A fake `ledger-admin`: records its arguments and JAVA_HOME, then answers on the ledger port
     * unless [healthy] is false, and runs until stopped. It exits after [lifeSeconds] of real time
     * if that's set: long enough for the script to see it answer and say it's up, even while
     * other tests run beside this one. A healthy one with a life notes in health-seen-at the
     * first of its polls after the script's health check found it answering, or "never".
     */
    private fun fakeLedger(healthy: Boolean = true, lifeSeconds: Double? = null): Path {
        val polls = lifeSeconds?.let { (it / FAKE_POLL_SECONDS).roundToInt() }
        if (healthy) { lifePolls = polls }
        val notesAnswer = healthy && polls != null
        return executable(
            fakes.resolve("ledger-admin"),
            """
            #!/bin/bash
            trap 'echo stopped >>"$dir/ledger-calls"; rm -f "$ports/$ledgerPort"; exit 0' TERM
            echo "args=${'$'}* JAVA_HOME=${'$'}{JAVA_HOME:-}" >>"$dir/ledger-calls"
            echo ${'$'}${'$'} >"$pids/ledger"
            ${if (notesAnswer) "rm -f \"$dir/health-seen\"" else ""}
            ${if (healthy) "touch \"$ports/$ledgerPort\"" else ""}
            waited=0
            seen_at=
            while [ ! -e "$stop" ]; do
                $realSleep $FAKE_POLL_SECONDS
                waited=${'$'}((waited + 1))
                ${if (notesAnswer) "[ -n \"${'$'}seen_at\" ] || [ ! -e \"$dir/health-seen\" ] || { seen_at=${'$'}waited; echo \"${'$'}waited\" >>\"$dir/health-seen-at\"; }" else ""}
                ${if (polls != null) "[ \"${'$'}waited\" -lt $polls ] || { ${if (notesAnswer) "[ -n \"${'$'}seen_at\" ] || echo never >>\"$dir/health-seen-at\"; " else ""}rm -f \"$ports/$ledgerPort\"; exit 3; }" else ""}
            done

            """.trimIndent(),
        )
    }

    /**
     * Replaces the script's functions that touch the machine: settings come from here instead of
     * ledger.conf, ports are files in [ports], and the browser is a file of the URLs it was asked to open.
     * A health check that finds the ledger answering leaves health-seen, for the fake ledger to note.
     */
    private val stubs = """
        load_settings() {
            echo "load_settings ${'$'}*" >>"$dir/calls"
            java_home="$java25"
            server_dir="$dir/server"
            mkdir -p "${'$'}server_dir"
            world="local"
            game_port=$gamePort
            ledger_port=$ledgerPort
            data_dir="$dir/data"
            world_log="$dir/server/logs/runescape_1.log"
            default_log="$dir/server/logs/default.log"
        }
        port_open() { [ -e "$ports/${'$'}1" ]; }
        ledger_health() { [ -e "$ports/${'$'}ledger_port" ] && : >>"$dir/health-seen"; }
        open_browser() { echo "${'$'}1" >>"$dir/browser"; }
        app="$fakes/ledger-admin"

    """.trimIndent()

    // --- running bash ---

    private class Ran(val exit: Int, val out: String, val err: String)

    private fun bash(
        body: String,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
        path: List<Path> = listOf(fakes, tools),
        withStubs: Boolean = true,
        replacing: Map<String, String> = emptyMap(),
        whileRunning: (Process) -> Unit = {},
    ): Ran {
        var tested = definitions.replace("/usr/libexec/java_home", javaHomeTool.toString())
        for ((old, new) in replacing) {
            check(tested.split(old).size == 2) { "the script doesn't have \"$old\" exactly once" }
            tested = tested.replace(old, new)
        }
        val program = tested + "\n" +
            (if (withStubs) stubs + "\n" else "") + body + "\n"
        val out = dir.resolve("out-${System.nanoTime()}")
        val err = dir.resolve("err-${System.nanoTime()}")
        val builder = ProcessBuilder(listOf("/bin/bash", "-c", program, script.toString()) + args)
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .directory(dir.toFile())
        builder.environment().apply {
            keys.removeAll(listOf("JAVA_HOME", "LEDGER_JAVA_HOME", "LEDGER_CONF"))
            put("PATH", path.joinToString(File.pathSeparator))
            putAll(env)
        }
        val started = System.nanoTime()
        val process = builder.start()
        whileRunning(process)
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        scriptRuns += millisSince(started) to 60
        assertTrue(finished, "the script didn't finish")
        return Ran(process.exitValue(), out.readText(), err.readText())
    }

    /** Runs the whole script, as a person would, with [args]. */
    private fun ledger(vararg args: String): Ran {
        val out = dir.resolve("out-${System.nanoTime()}")
        val err = dir.resolve("err-${System.nanoTime()}")
        val started = System.nanoTime()
        val process = ProcessBuilder(listOf("/bin/bash", script.toString()) + args)
            .redirectOutput(out.toFile())
            .redirectError(err.toFile())
            .start()
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        scriptRuns += millisSince(started) to 30
        assertTrue(finished, "the script didn't finish")
        return Ran(process.exitValue(), out.readText(), err.readText())
    }

    private fun waitUntil(what: String, seconds: Int = 20, condition: () -> Boolean) {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting until $what" }
            Thread.sleep(50)
        }
        println("Waiting until $what took ${millisSince(started)} ms (limit ${seconds * 1_000} ms)")
    }

    private fun interrupt(process: Process) {
        ProcessBuilder("/bin/kill", "-INT", process.pid().toString()).start().waitFor()
    }

    private fun alive(name: String): Boolean =
        pids.resolve(name).exists() && ProcessHandle.of(pids.resolve(name).readText().trim().toLong()).map { it.isAlive }.orElse(false)

    private fun lines(name: String): List<String> = dir.resolve(name).let { if (it.exists()) it.readText().lines().filter(String::isNotEmpty) else emptyList() }

    // --- find_java ---

    private fun findJava(env: Map<String, String> = emptyMap(), path: List<Path> = listOf(fakes, tools)) =
        bash("find_java", env = env, path = path, withStubs = false)

    @Test
    fun `find_java takes LEDGER_JAVA_HOME first`() {
        javaHomeToolAnswers(javaHome("tool", "25.0.2"))
        val ran = findJava(mapOf("LEDGER_JAVA_HOME" to javaHome("a", "25.0.1").toString(), "JAVA_HOME" to javaHome("b", "26").toString()))
        assertEquals(0, ran.exit, ran.err)
        assertEquals("${dir.resolve("jdks/a")}\n", ran.out)
    }

    @Test
    fun `find_java takes JAVA_HOME when it is 25 or newer`() {
        javaHomeToolAnswers(javaHome("tool", "25.0.2"))
        val ran = findJava(mapOf("JAVA_HOME" to javaHome("b", "26-ea").toString()))
        assertEquals("${dir.resolve("jdks/b")}\n", ran.out)
    }

    @Test
    fun `find_java passes over an older JAVA_HOME, which the game server may use, for the system's Java 25`() {
        javaHomeToolAnswers(javaHome("tool", "25.0.2"))
        for (old in listOf("17.0.9", "21", "1.8.0_392")) {
            val ran = findJava(mapOf("JAVA_HOME" to javaHome("old-$old", old).toString()))
            assertEquals(0, ran.exit, ran.err)
            assertEquals("${dir.resolve("jdks/tool")}\n", ran.out, "with JAVA_HOME at Java $old")
        }
    }

    @Test
    fun `find_java finds a Java 25 on the PATH, through its links, when macOS has none`() {
        javaHomeToolAnswers(null)
        val home = javaHome("on-path", "25.0.1")
        val bin = dir.resolve("linked-bin").createDirectories()
        Files.createSymbolicLink(bin.resolve("java"), home.resolve("bin/java"))
        val ran = findJava(path = listOf(bin, tools))
        assertEquals(0, ran.exit, ran.err)
        assertEquals("$home\n", ran.out)
    }

    private val noJava25 = "ledger: The ledger needs Java 25 or newer; set LEDGER_JAVA_HOME to one.\n"

    @Test
    fun `find_java says the ledger needs Java 25 when no Java is 25 or newer`() {
        javaHomeToolAnswers(null)
        val bin = dir.resolve("linked-bin").createDirectories()
        Files.createSymbolicLink(bin.resolve("java"), javaHome("on-path", "21.0.4").resolve("bin/java"))
        val ran = findJava(mapOf("JAVA_HOME" to javaHome("old", "17").toString()), path = listOf(bin, tools))
        assertEquals(1, ran.exit)
        assertEquals("", ran.out)
        assertEquals(noJava25, ran.err)
    }

    @Test
    fun `find_java doesn't take an older Java that macOS offers when it has no 25`() {
        // Asked for "-v 25+" with no such Java installed, macOS's java_home prints its default
        // Java anyway, with exit 0 (seen with "-v 99+").
        javaHomeToolAnswers(javaHome("only", "21.0.4"))
        val ran = findJava()
        assertEquals(1, ran.exit, "took ${ran.out}")
        assertEquals(noJava25, ran.err)
    }

    @Test
    fun `find_java goes on to the PATH when macOS offers an older Java`() {
        javaHomeToolAnswers(javaHome("only", "21.0.4"))
        val home = javaHome("on-path", "25.0.1")
        val bin = dir.resolve("linked-bin").createDirectories()
        Files.createSymbolicLink(bin.resolve("java"), home.resolve("bin/java"))
        assertEquals("$home\n", findJava(path = listOf(bin, tools)).out)
    }

    @Test
    fun `a LEDGER_JAVA_HOME that isn't Java 25 is refused, saying why, not passed over`() {
        javaHomeToolAnswers(javaHome("tool", "25.0.2"))
        val silent = executable(dir.resolve("jdks/silent/bin/java"), "#!/bin/bash\necho 'no version here' >&2\n").parent.parent
        val refusals = mapOf(
            javaHome("old", "21.0.4") to "which is Java 21, not 25 or newer",
            javaHome("eight", "1.8.0_392") to "which is Java 8, not 25 or newer",
            dir.resolve("no-such-folder") to "which has no bin/java",
            silent to "whose bin/java doesn't say its version",
        )
        for ((wrong, why) in refusals) {
            val ran = findJava(mapOf("LEDGER_JAVA_HOME" to wrong.toString(), "JAVA_HOME" to javaHome("b", "25").toString()))
            assertEquals(1, ran.exit, "with LEDGER_JAVA_HOME=$wrong: ${ran.out}")
            assertEquals("", ran.out)
            assertEquals("ledger: LEDGER_JAVA_HOME is $wrong, $why.\n", ran.err)
        }
    }

    // --- load_settings ---

    @Test
    fun `load_settings says how to get a ledger conf when there is none`() {
        val ran = bash("load_settings", env = mapOf("LEDGER_CONF" to "$dir/missing.conf"), withStubs = false)
        assertEquals(1, ran.exit)
        assertEquals("ledger: No config at $dir/missing.conf. Copy ledger.conf.example to ledger.conf and set openrsc.dir.\n", ran.err)
    }

    @Test
    fun `load_settings says the ledger needs Java 25 when there is none`() {
        javaHomeToolAnswers(null)
        val conf = dir.resolve("ledger.conf").also { it.writeText("openrsc.dir = x\n") }
        val ran = bash("load_settings", env = mapOf("LEDGER_CONF" to conf.toString()), withStubs = false)
        assertEquals(1, ran.exit)
        assertEquals("ledger: The ledger needs Java 25 or newer; set LEDGER_JAVA_HOME to one.\n", ran.err)
    }

    @Test
    fun `an empty LEDGER_CONF means the ledger conf beside the script, as the ledger itself reads it`() {
        val ran = bash("echo \"\$conf\"", env = mapOf("LEDGER_CONF" to ""), withStubs = false)
        assertEquals("${script.parent.resolve("ledger.conf")}\n", ran.out)
    }

    // --- the script's arguments ---

    @Test
    fun `help prints the usage and succeeds, however it is asked for`() {
        for (asked in listOf("help", "-h", "--help")) {
            val ran = ledger(asked)
            assertEquals(0, ran.exit, asked)
            assertTrue(ran.out.startsWith("Usage: ./ledger up [--no-browser] [--ledger-only]"), ran.out)
        }
    }

    @Test
    fun `no command, an unknown one or an unknown option prints the usage and fails without starting anything`() {
        for (args in listOf(emptyList(), listOf("down"), listOf("up", "--browser"), listOf("up", "--no-browser", "x"), listOf("logs", "now"))) {
            val ran = ledger(*args.toTypedArray())
            assertEquals(2, ran.exit, "$args")
            assertEquals("", ran.out)
            assertTrue(ran.err.startsWith("Usage: ./ledger up"), "$args: ${ran.err}")
        }
    }

    // --- up ---

    @Test
    fun `up refuses to start when something already listens on the ledger's port`() {
        fakeAnt()
        fakeLedger()
        ports.resolve("$ledgerPort").writeText("")
        val ran = bash("up \"\$@\"")
        assertEquals(1, ran.exit)
        assertEquals("ledger: Something is already listening on port $ledgerPort; is a ledger running? Stop it first.\n", ran.err)
        assertEquals(emptyList(), lines("ant-calls"))
        assertEquals(emptyList(), lines("ledger-calls"))
    }

    @Test
    fun `up builds the ledger before anything else`() {
        fakeLedger(lifeSeconds = 0.5)
        ports.resolve("$gamePort").writeText("")
        bash("up \"\$@\"", "--no-browser")
        assertEquals(listOf("load_settings build"), lines("calls"))
    }

    @Test
    fun `up starts the game server with ant in its server folder, with the caller's own Java, then the ledger with Java 25`() {
        fakeAnt()
        fakeLedger(lifeSeconds = 1.0)
        val ran = bash("up \"\$@\"", "--no-browser", env = mapOf("JAVA_HOME" to "/the/callers/java"))
        assertEquals(listOf("cwd=$dir/server args=runserver -DconfFile=local JAVA_HOME=/the/callers/java"), lines("ant-calls"))
        assertEquals("args=${script.parent}/ledger.conf JAVA_HOME=$java25", lines("ledger-calls").first())
        assertTrue(ran.out.startsWith("ledger: Using Java 25 from $java25.\n"), ran.out)
        assertTrue(ran.out.contains("ledger: The game server is up on port $gamePort. ./ledger logs follows its log.\n"), ran.out)
        assertTrue(ran.out.contains("ledger: The ledger is up. Ctrl-C stops it and leaves the game server running.\n"), ran.out)
        assertTrue(dir.resolve("data/game-server.log").readText().contains("BUILD STARTED"), "ant's output is kept in game-server.log")
    }

    @Test
    fun `up leaves a running game server alone and starts only the ledger`() {
        fakeAnt()
        fakeLedger(lifeSeconds = 0.5)
        ports.resolve("$gamePort").writeText("")
        val ran = bash("up \"\$@\"", "--no-browser")
        assertEquals(emptyList(), lines("ant-calls"))
        assertTrue(ran.out.contains("\nledger: The game server is already running on port $gamePort; starting only the ledger.\n"), ran.out)
        assertEquals(1, lines("ledger-calls").count { it.startsWith("args=") })
    }

    @Test
    fun `up --ledger-only never starts the game server, running or not`() {
        fakeAnt()
        fakeLedger(lifeSeconds = 0.5)
        val down = bash("up \"\$@\"", "--ledger-only", "--no-browser")
        assertTrue(down.out.contains("\nledger: The game server isn't running, and --ledger-only leaves it so; starting only the ledger.\n"), down.out)
        assertTrue(down.out.contains("ledger: The ledger is up. Ctrl-C stops it.\n"), down.out)

        ports.resolve("$gamePort").writeText("")
        val up = bash("up \"\$@\"", "--ledger-only", "--no-browser")
        assertTrue(up.out.contains("\nledger: The game server is running on port $gamePort; starting only the ledger, as asked.\n"), up.out)
        assertEquals(emptyList(), lines("ant-calls"))
    }

    @Test
    fun `up says ant is needed when the game server must be started and ant isn't there`() {
        fakeLedger()
        val ran = bash("up \"\$@\"", "--no-browser")
        assertEquals(1, ran.exit)
        assertEquals("ledger: ant isn't installed; OpenRSC's server is started with it.\n", ran.err)
        assertEquals(emptyList(), lines("ledger-calls"))
    }

    @Test
    fun `up stops when ant ends without the game server opening its port, and doesn't start the ledger`() {
        fakeAnt(fails = true)
        fakeLedger()
        val ran = bash("up \"\$@\"", "--no-browser")
        assertEquals(1, ran.exit)
        assertEquals("ledger: The game server didn't open port $gamePort; see $dir/data/game-server.log and $dir/server/logs/default.log\n", ran.err)
        assertEquals(emptyList(), lines("ledger-calls"))
    }

    @Test
    fun `up opens the ledger in the browser unless told not to`() {
        fakeLedger(lifeSeconds = 0.5)
        ports.resolve("$gamePort").writeText("")
        bash("up \"\$@\"")
        assertEquals(listOf("http://127.0.0.1:$ledgerPort/"), lines("browser"))
        bash("up \"\$@\"", "--no-browser")
        assertEquals(1, lines("browser").size)
    }

    @Test
    fun `when the ledger stops by itself, up says so and fails`() {
        fakeLedger(lifeSeconds = 0.5)
        ports.resolve("$gamePort").writeText("")
        val ran = bash("up \"\$@\"", "--no-browser")
        assertEquals(1, ran.exit)
        assertTrue(ran.out.contains("ledger: The ledger has stopped.\n"), ran.out)
        assertEquals("", ran.err)
    }

    @Test
    fun `when the ledger exits before answering its health check, up fails without waiting on`() {
        fakeLedger(healthy = false, lifeSeconds = 0.5)
        ports.resolve("$gamePort").writeText("")
        val ran = bash("up \"\$@\"", "--no-browser")
        assertEquals(1, ran.exit)
        assertEquals(emptyList(), lines("browser"))
        assertFalse(ran.out.contains("The ledger is up"), ran.out)
        assertFalse(ran.out.contains("didn't answer"), "the ledger's own error says why it stopped: ${ran.out}")
        assertEquals("", ran.err)
    }

    @Test
    fun `when the ledger runs but never answers its health check, up says so and stops it`() {
        // The script gives it 120 seconds; the test shortens that to 5 in the text it runs, which
        // the tests' sleep makes a tenth of a second.
        fakeLedger(healthy = false)
        ports.resolve("$gamePort").writeText("")
        val ran = bash("up \"\$@\"", "--no-browser", replacing = mapOf("wait_for 120 " to "wait_for 5 "))
        assertEquals(1, ran.exit)
        assertTrue(
            ran.out.contains(
                "ledger: The ledger didn't answer on http://127.0.0.1:$ledgerPort/api/health within 120 seconds; stopping it.\n" +
                    "ledger: Stopping the ledger...\n",
            ),
            ran.out,
        )
        assertTrue("stopped" in lines("ledger-calls"), "the ledger was sent TERM")
        assertEquals("", ran.err)
    }

    // --- Ctrl-C ---

    @Test
    fun `Ctrl-C stops the ledger and leaves the game server running, saying how to stop it with everyone saved`() {
        fakeAnt()
        fakeLedger()
        val ran = bash("up \"\$@\"", "--no-browser") { process ->
            waitUntil("the ledger is up") { lines("ledger-calls").isNotEmpty() && ports.resolve("$ledgerPort").exists() }
            Thread.sleep(300)
            interrupt(process)
        }
        assertEquals(0, ran.exit, ran.err)
        assertEquals("", ran.err)
        assertTrue("stopped" in lines("ledger-calls"), "the ledger was sent TERM")
        assertTrue(alive("ant"), "the game server is left running")
        assertTrue(
            ran.out.endsWith(
                """
                ledger: Stopping the ledger...
                ledger: The game server is still running on port $gamePort.
                ledger: To stop it with every player saved, log in as an admin and type ::shutdown (a
                ledger: countdown, then everyone is saved and logged out). Stopping its process instead
                ledger: loses what players did since the last autosave.
                ledger: Its log is $dir/server/logs/runescape_1.log; ./ledger logs follows it.

                """.trimIndent(),
            ),
            ran.out,
        )
    }

    @Test
    fun `Ctrl-C while the game server is still starting leaves it starting, and doesn't say it isn't running`() {
        fakeAnt(delaySeconds = 4)
        fakeLedger()
        var antSeen = 0L
        val ran = bash("up \"\$@\"", "--no-browser") { process ->
            waitUntil("ant has started") { lines("ant-calls").isNotEmpty() }
            antSeen = System.nanoTime()
            Thread.sleep(500)
            interrupt(process)
        }
        println("The script ended ${millisSince(antSeen)} ms after the test saw ant start (ant opens its port 4000 ms after starting)")
        assertEquals(0, ran.exit, ran.err)
        assertEquals(emptyList(), lines("ledger-calls"))
        assertTrue(alive("ant"), "the game server carries on starting")
        assertTrue(
            ran.out.endsWith(
                """
                ledger: The game server is still starting, and carries on in the background until it opens port $gamePort.
                ledger: To stop it with every player saved, log in as an admin and type ::shutdown (a
                ledger: countdown, then everyone is saved and logged out). Stopping its process instead
                ledger: loses what players did since the last autosave.
                ledger: Its log is $dir/server/logs/runescape_1.log; ./ledger logs follows it.

                """.trimIndent(),
            ),
            ran.out,
        )
        assertEquals("", ran.err)
    }

    // --- logs ---

    private fun serverLog(lineCount: Int): Path =
        dir.resolve("server/logs/runescape_1.log").also { log ->
            log.parent.createDirectories()
            log.writeText((1..lineCount).joinToString("") { "line $it\n" })
        }

    @Test
    fun `logs says the server writes its log when it starts, when there is none yet`() {
        val ran = bash("logs \"\$@\"")
        assertEquals(1, ran.exit)
        assertEquals("ledger: There's no game server log yet ($dir/server/logs/runescape_1.log); the server writes it when it starts.\n", ran.err)
    }

    @Test
    fun `logs shows the last 40 lines and ends when the game server isn't running`() {
        serverLog(50)
        val ran = bash("logs \"\$@\"")
        assertEquals(0, ran.exit, ran.err)
        assertEquals(
            "ledger: The game server isn't running. The end of its last log, from $dir/server/logs/runescape_1.log:\n" +
                (11..50).joinToString("") { "line $it\n" },
            ran.out,
        )
        assertEquals(listOf("load_settings "), lines("calls"), "logs doesn't rebuild the ledger")
    }

    @Test
    fun `logs follows the log by name while the game server runs`() {
        serverLog(3)
        ports.resolve("$gamePort").writeText("")
        val bin = dir.resolve("tail-bin")
        executable(bin.resolve("tail"), "#!/bin/bash\necho \"tail \$*\"\n")
        val ran = bash("logs \"\$@\"", path = listOf(bin, fakes, tools))
        assertEquals(0, ran.exit, ran.err)
        assertEquals(
            "ledger: Following $dir/server/logs/runescape_1.log. Ctrl-C stops following; the game server keeps running.\n" +
                "tail -n 40 -F $dir/server/logs/runescape_1.log\n",
            ran.out,
        )
    }

    private companion object {
        /** How often the fake game server and ledger look for the test's stop file, in real seconds. */
        const val FAKE_POLL_SECONDS = 0.02
    }
}

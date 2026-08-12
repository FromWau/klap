package com.fromwau.klap.fixture.pulse

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kern.terminal.bold
import com.fromwau.kern.terminal.dim
import com.fromwau.kern.terminal.defaultTerminal
import com.fromwau.kern.terminal.green
import com.fromwau.kern.terminal.red
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.ColorScope
import com.fromwau.klap.ConversionError
import com.fromwau.klap.cli
import com.fromwau.klap.runSuspending
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

fun main(args: Array<String>) {
    // Everything below is the caller-owned scope the suspending-actions design doc talks about: a
    // background service loop that outlives any single action, plus a shutdown hook so Ctrl-C actually
    // cancels it instead of the JVM just dying mid-flight. klap does not provide either of these; the
    // guide is explicit that both are the caller's job.
    val rootJob = Job()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            // JVM shutdown hooks run on EVERY exit, not only on a signal, so guard on the job still
            // being active: rootJob is already complete on the ordinary happy-path exit, and cancelling
            // (or announcing a cancel) at that point would be a false alarm printed on every run.
            if (rootJob.isActive) {
                System.err.println("[pulse] shutdown signal received, cancelling...")
                rootJob.cancel(CancellationException("process is shutting down"))
                runBlocking { rootJob.children.forEach { it.join() } }
            }
        },
    )

    val code = try {
        runBlocking(rootJob) {
            val metrics = MetricsCollector()
            val metricsJob = launch { metrics.startLoop() }

            val exitCode = pulseCli().runSuspending(args, defaultTerminal())

            metricsJob.cancelAndJoin()
            exitCode
        }
    } catch (_: CancellationException) {
        // runBlocking rethrows the cancellation that the shutdown hook above triggered; left uncaught
        // this is a raw stack trace on an ordinary Ctrl-C, which is not what a caller-cancelled run
        // should look like. 130 is the conventional 128+SIGINT exit code.
        System.err.println("[pulse] cancelled")
        130
    } finally {
        // A bare Job() never transitions out of Active on its own just because its children finished;
        // it needs an explicit complete() call. Without this the shutdown hook's `rootJob.isActive`
        // check above stays true forever, so a perfectly ordinary exit still looks like a live
        // cancellation to it. Safe to call after a cancellation too: complete() is then a no-op.
        rootJob.complete()
    }
    exitProcess(code)
}

/**
 * The whole command tree, named rather than inlined into [main]: a test drives it through
 * `Cli.runSuspending(argv, terminal)`, which needs the built [Cli], not the process-exiting `.main(args)`.
 */
internal fun pulseCli(): Cli = cli("pulse") {
    description = "A tiny async service monitor"
    version = "0.1.0"
    author = "dx-probe"
    epilogue = "All services are simulated; nothing here touches the network."

    val repo = Repository()
    val checker = HealthChecker()
    val notifier = Notifier()

    example("pulse check --timeout 3s", "concurrently probe every known service, give up after 3s")
    example("pulse watch --interval 1s --duration 5s", "poll every service once a second for five seconds")
    example("pulse services show cache", "look up one service")

    command("services", "inspect the service catalogue") {
        command("list", "list every known service") {
            actionSuspending(
                human = { services -> services.joinToString("\n") { "${it.name} (base ${it.baseLatency}, ${it.failureMode})" } },
            ) {
                Ok(repo.listServices())
            }
        }

        command("show", "show one service by name") {
            val name = argument("name", "service name")
            actionSuspending(human = { svc -> "${svc.name}: base ${svc.baseLatency}, failure mode ${svc.failureMode}" }) {
                val svc = repo.findService(name())
                    ?: return@actionSuspending Err(CliError.Failure("no service named '${name()}'", exitCode = 2))
                Ok(svc)
            }
        }
    }

    command("check", "concurrently probe every service and report health") {
        val timeout = option("--timeout", "-t", help = "give up on the whole batch after this long, e.g. 3s")
            .convert(::parseDuration)
            .range(1.seconds..120.seconds)
            .default(5.seconds)
            .placeholder("DURATION")
        val only = option("--service", "-s", help = "limit to this service (repeatable)").multiple()

        actionSuspending(human = { report -> renderCheckReport(report) }) {
            val budget = timeout()
            val wanted = only()
            val services = repo.listServices().filter { wanted.isEmpty() || it.name in wanted }
            if (services.isEmpty()) {
                return@actionSuspending Err(CliError.Usage("no known service matches ${wanted.joinToString(", ")}"))
            }

            // Only running out of the whole batch's time budget is a hard failure, which is what
            // withTimeoutOrNull turning null distinguishes; a single service failing is just a row.
            val statuses = withTimeoutOrNull(budget) { checker.probeAll(services) }
                ?: return@actionSuspending Err(
                    CliError.Domain(
                        BatchTimeout(budget),
                        "check timed out after $budget",
                        exitCode = 4,
                    ),
                )

            Ok(CheckReport(statuses))
        }
    }

    command("watch", "poll every service on an interval until the duration elapses") {
        val interval = option("--interval", "-i", help = "wait this long between polls, e.g. 1s")
            .convert(::parseDuration)
            .range(1.seconds..60.seconds)
            .default(2.seconds)
            .placeholder("DURATION")
        val duration = option("--duration", "-d", help = "keep polling for this long before stopping, e.g. 30s")
            .convert(::parseDuration)
            .range(1.seconds..300.seconds)
            .default(10.seconds)
            .placeholder("DURATION")

        actionSuspending(
            human = { summary -> "watched ${summary.ticks} tick(s); ${summary.finalHealthy}/${summary.finalTotal} healthy at the end" },
        ) {
            var lastReport: CheckReport? = null
            var tickCount = 0

            // The command "gives up after the duration" on its own terms: withTimeoutOrNull cancels the
            // loop from the inside, no caller involvement needed, and null just means "time's up",
            // not a failure worth reporting as one.
            withTimeoutOrNull(duration()) {
                while (true) {
                    val statuses = checker.probeAll(repo.listServices())
                    lastReport = CheckReport(statuses)
                    tickCount++
                    // `json` holds back the human half itself; see guide.md "Actions that print their own output".
                    if (!json) println("[$tickCount] ${statuses.count { it.healthy }}/${statuses.size} healthy")
                    delay(interval())
                }
            }

            val report = lastReport
                ?: return@actionSuspending Err(CliError.Failure("watch stopped before a single tick completed"))
            Ok(WatchSummary(ticks = tickCount, finalHealthy = report.healthyCount, finalTotal = report.total))
        }
    }

    command("notify", "send a notification to a channel") {
        val channel = argument("channel", "channel to notify")
        val message = argument("message", "message body")

        // No `human`: the action already returns the line to print, which is what klap prints by default.
        actionSuspending {
            notifier.notify(channel(), message())
                .mapError { CliError.Domain(it, "failed to notify ${channel()}: channel unreachable", exitCode = 5) }
                .map { "sent to ${channel()}" }
        }
    }

    // Synchronous, on purpose: no I/O, so it needs no actionSuspending, and it proves the tree can mix
    // both kinds of command (guide.md "Suspending actions": "the reverse direction is allowed").
    command("info", "print static build info (no I/O, synchronous)") {
        action(human = { info -> "${info.name} v${info.version} - ${info.serviceCount} known services" }) {
            Ok(BuildInfo(name = "pulse", version = "0.1.0", serviceCount = DEFAULT_SERVICES.size))
        }
    }
}

private fun ColorScope.renderCheckReport(report: CheckReport): String = buildString {
    report.results.forEach { s ->
        val line = if (s.healthy) {
            // Explicit unit: a measured Duration renders at nanosecond precision (`40.961158ms`), which is
            // false precision for a health probe. The configured latencies below are whole ms already.
            green("[ok]   ${s.service} (${s.latency.toString(DurationUnit.MILLISECONDS)})")
        } else {
            red("[fail] ${s.service}: ${s.detail}")
        }
        appendLine(line)
    }
    append(dim(bold("${report.healthyCount}/${report.total} healthy")))
}

/**
 * Probe every service concurrently and fold each outcome into a row, so one service being down never
 * sinks the batch.
 */
private suspend fun HealthChecker.probeAll(services: List<ServiceConfig>): List<HealthStatus> =
    coroutineScope {
        services
            .map { svc -> async { Triple(svc.name, Clock.System.now(), check(svc)) } }
            .awaitAll()
    }.map { (name, startedAt, result) ->
        result.getOrElse { err ->
            HealthStatus(
                service = name,
                healthy = false,
                latency = Duration.ZERO,
                // This service's own start instant, not the moment the batch finished: an unhealthy row's
                // timestamp has to mean what a healthy row's means.
                checkedAt = startedAt,
                detail = err.describe(),
            )
        }
    }

private fun ServiceError.describe(): String = when (this) {
    is ServiceError.Down -> "$service is down"
    is ServiceError.Flaked -> "$service flaked: $reason"
}

/** klap has no built-in Duration converter, so `--timeout`/`--interval`/`--duration` all convert through this. */
private fun parseDuration(raw: String): Result<Duration, ConversionError> =
    try {
        Ok(Duration.parse(raw))
    } catch (_: IllegalArgumentException) {
        Err(ConversionError.Domain(DurationError.Malformed(raw), "not a valid duration, try 3s, 1m30s, or 2h"))
    }

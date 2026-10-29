package avalon.server

/**
 * Run the Avalon server on a PC for development/testing:
 *
 *   ./gradlew :server:run              (port 8080)
 *   ./gradlew :server:run --args="9000"
 *   java -jar server/build/libs/server-all.jar [port]
 */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8080
    val server = AvalonServer(port = port)
    server.start(wait = false)
    println()
    println("Avalon Offline server is running.")
    println()
    println("Friends on the same Wi-Fi open one of these in their phone browser:")
    for ((nif, ip) in AvalonServer.lanAddresses()) println("    http://$ip:${server.boundPort}/      ($nif)")
    println()
    println("YOUR screen (makes you the host; keep this link private):")
    println("    ${server.hostUrl()}")
    println()
    println("Press Ctrl+C to stop.")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}

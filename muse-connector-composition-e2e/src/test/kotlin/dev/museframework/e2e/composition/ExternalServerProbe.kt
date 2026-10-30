package dev.museframework.e2e.composition

import java.time.Duration

object ExternalServerProbe {
    fun h2TcpServerThreadCount(): Int =
        Thread
            .getAllStackTraces()
            .keys
            .count { thread -> thread.name.startsWith("H2 TCP Server") && thread.isAlive }

    fun awaitH2TcpServerThreadCount(
        expected: Int,
        timeout: Duration,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (h2TcpServerThreadCount() == expected) {
                return
            }
            Thread.sleep(50)
        }
        error(
            "Timed out waiting for H2 TCP server thread count; expected=$expected actual=${h2TcpServerThreadCount()}",
        )
    }
}

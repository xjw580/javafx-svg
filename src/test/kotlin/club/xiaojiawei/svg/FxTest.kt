package club.xiaojiawei.svg

import javafx.application.Platform
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal object FxTest {
    private var started = false
    @Synchronized private fun start() {
        if (started) return
        val ready = CountDownLatch(1)
        Platform.startup { Platform.setImplicitExit(false); ready.countDown() }
        check(ready.await(15, TimeUnit.SECONDS)) { "JavaFX startup timeout" }
        started = true
    }
    fun run(action: () -> Unit) {
        start()
        val task = FutureTask { action() }
        Platform.runLater(task)
        task.get(15, TimeUnit.SECONDS)
    }
}

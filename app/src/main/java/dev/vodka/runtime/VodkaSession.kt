package dev.vodka.runtime

import java.io.File
import java.util.concurrent.Executors

class VodkaSession(
  private val rootfsDir: File,
  private val prootPath: String,
  private val defaultEnv: List<String> = emptyList(),
  private val defaultBinds: List<BindMount> = emptyList(),
) {
  sealed class Result {
    abstract val log: String
    data class Exited(val code: Int, override val log: String = "") : Result()
    data class Failed(val message: String, override val log: String = "") : Result()
  }

  private val logDir: File = File(rootfsDir.parentFile ?: rootfsDir, "logs").apply { mkdirs() }

  private val executor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "vodka-session")
  }

  fun probe(): Capabilities = Capabilities.parse(NativeContainer.probe(rootfsDir.absolutePath))

  fun launch(
    command: List<String>,
    backend: ContainerBackend = ContainerBackend.AUTO,
    binds: List<BindMount> = emptyList(),
    env: List<String> = emptyList(),
    workingDir: String = "/",
    onFinished: (Result) -> Unit,
  ) {
    executor.execute {
      val logFile = File(logDir, "run-${System.currentTimeMillis()}.log")
      val code = NativeContainer.run(
        rootfs = rootfsDir.absolutePath,
        workingDir = workingDir,
        backend = backend.wire,
        prootPath = prootPath,
        binds = (defaultBinds + binds).map { it.toWire() }.toTypedArray(),
        env = (defaultEnv + env).toTypedArray(),
        command = command.toTypedArray(),
        mountProc = true,
        mountDev = true,
        fakeRoot = true,
        logPath = logFile.absolutePath,
      )
      val log = logFile.takeIf { it.exists() }?.readText()?.takeLast(4000)?.trim().orEmpty()
      onFinished(
        if (code >= 0) Result.Exited(code, log)
        else Result.Failed("container start failed ($code)", log),
      )
    }
  }

  fun stop() {
    NativeContainer.stop()
  }

  fun shutdown() {
    executor.shutdownNow()
  }
}

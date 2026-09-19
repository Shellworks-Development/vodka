package dev.vodka.runtime

object NativeContainer {

  init {
    System.loadLibrary("vodka_jni")
  }

  external fun probe(rootfs: String): String

  external fun run(
    rootfs: String,
    workingDir: String,
    backend: String,
    prootPath: String,
    binds: Array<String>,
    env: Array<String>,
    command: Array<String>,
    mountProc: Boolean,
    mountDev: Boolean,
    fakeRoot: Boolean,
    logPath: String,
  ): Int

  external fun stop(): Boolean

  external fun pid(): Int
}

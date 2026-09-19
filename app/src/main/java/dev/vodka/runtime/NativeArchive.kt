package dev.vodka.runtime

object NativeArchive {

  init {
    System.loadLibrary("vodka_jni")
  }

  external fun extractTarGz(archivePath: String, destDir: String): String
}

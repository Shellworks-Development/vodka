package dev.vodka.runtime

object NativeRuntime {

  init {
    System.loadLibrary("vodka_jni")
  }

  external fun planArgs(config: Array<String>): Array<String>

  external fun planEnv(config: Array<String>): Array<String>

  external fun planWorkdir(config: Array<String>): String

  external fun fexConfigJson(config: Array<String>): String

  external fun fexAppConfigJson(config: Array<String>): String
}

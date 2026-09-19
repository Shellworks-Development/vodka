package dev.vodka.runtime

enum class ContainerBackend(val wire: String) {
  AUTO("auto"),
  NAMESPACES("namespaces"),
  PROOT("proot"),
}

package dev.vodka.runtime

data class Capabilities(
  val kernelRelease: String,
  val hostArch: String,
  val pageSize: Long,
  val userNamespace: Boolean,
  val mountNamespace: Boolean,
  val pidNamespace: Boolean,
  val prootPresent: Boolean,
) {
  val namespacesAvailable: Boolean get() = userNamespace && mountNamespace

  val preferredBackend: ContainerBackend
    get() = if (namespacesAvailable) ContainerBackend.NAMESPACES else ContainerBackend.PROOT

  fun describe(): String = buildString {
    appendLine("kernel      $kernelRelease")
    appendLine("arch        $hostArch")
    appendLine("page size   $pageSize")
    appendLine("user ns     $userNamespace")
    appendLine("mount ns    $mountNamespace")
    appendLine("pid ns      $pidNamespace")
    appendLine("proot       $prootPresent")
    appendLine("backend     ${preferredBackend.wire}")
  }

  companion object {
    fun parse(raw: String): Capabilities {
      val values = raw.lineSequence()
        .mapNotNull { line ->
          val index = line.indexOf('=')
          if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }
        .toMap()
      return Capabilities(
        kernelRelease = values["kernel"].orEmpty(),
        hostArch = values["arch"].orEmpty(),
        pageSize = values["page"]?.toLongOrNull() ?: 0L,
        userNamespace = values["user_ns"] == "1",
        mountNamespace = values["mount_ns"] == "1",
        pidNamespace = values["pid_ns"] == "1",
        prootPresent = values["proot"] == "1",
      )
    }
  }
}

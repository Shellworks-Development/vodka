package dev.vodka.runtime

import java.io.File

class StudioRuntime(
  private val roots: RootfsManager,
  private val session: VodkaSession,
) {
  val guestRootfs: String = "/opt/vodka/rootfs-x86_64"
  val home: String = "/home/vodka"
  val winePrefix: String = "$home/.wine"
  val appConfig: String = "$home/.config/fex-emu/RobloxStudio.json"

  fun binds(): List<BindMount> = listOf(BindMount(roots.x86Rootfs.absolutePath, guestRootfs))

  fun configArray(studioExe: String? = null, pulse: String? = null): Array<String> = buildList {
    add("fex=/usr/bin/FEX")
    add("wine=/usr/lib/wine/wine64")
    add("env=WINESERVER=/usr/lib/wine/wineserver64")
    add("env=WINEDLLPATH=$guestRootfs/usr/lib/x86_64-linux-gnu/wine/x86_64-windows:$guestRootfs/usr/lib/x86_64-linux-gnu/wine/i386-windows")
    add("rootfs=$guestRootfs")
    add("thunks=/usr/lib/aarch64-linux-gnu/fex-emu/HostThunks")
    add("home=$home")
    add("wineprefix=$winePrefix")
    add("display=:0")
    if (pulse != null) add("pulse=$pulse")
    if (studioExe != null) add("studio=$studioExe")
    add("appconfig=$appConfig")
    add("tso=1")
    add("hidehypervisor=1")
  }.toTypedArray()

  fun writeFexConfig(pulse: String? = null) {
    val dir = File(roots.arm64Rootfs, "home/vodka/.config/fex-emu")
    dir.mkdirs()
    File(dir, "Config.json").writeText(NativeRuntime.fexConfigJson(configArray(null, pulse)))
    File(dir, "RobloxStudio.json").writeText(NativeRuntime.fexAppConfigJson(configArray(null, pulse)))
    File(roots.arm64Rootfs, home.removePrefix("/")).mkdirs()
  }

  fun runSetup(backend: ContainerBackend, onFinished: (VodkaSession.Result) -> Unit) {
    writeFexConfig()
    val env = listOf(
      "HOME=$home",
      "FEX=/usr/bin/FEX",
      "WINE=/usr/lib/wine/wine64",
      "WINESERVER=/usr/lib/wine/wineserver64",
      "WINEPREFIX=$winePrefix",
      "GUEST_ROOTFS=$guestRootfs",
      "DXVK_DIR=$guestRootfs/opt/vodka/dxvk",
      "DISPLAY=:0",
    )
    session.launch(
      command = listOf("/bin/sh", "/usr/local/bin/vodka-wine-setup"),
      backend = backend,
      binds = binds(),
      env = env,
      workingDir = home,
      onFinished = onFinished,
    )
  }

  fun runWine(
    args: List<String>,
    backend: ContainerBackend,
    pulse: String? = null,
    onFinished: (VodkaSession.Result) -> Unit,
  ) {
    writeFexConfig(pulse)
    val config = configArray(null, pulse)
    val argv = NativeRuntime.planArgs(config).toMutableList()
    argv.addAll(args)
    session.launch(
      command = argv,
      backend = backend,
      binds = binds(),
      env = NativeRuntime.planEnv(config).toList(),
      workingDir = home,
      onFinished = onFinished,
    )
  }

  fun launch(
    studioExe: String,
    backend: ContainerBackend,
    pulse: String? = null,
    onFinished: (VodkaSession.Result) -> Unit,
  ) {
    writeFexConfig(pulse)
    val config = configArray(studioExe, pulse)
    session.launch(
      command = NativeRuntime.planArgs(config).toList(),
      backend = backend,
      binds = binds(),
      env = NativeRuntime.planEnv(config).toList(),
      workingDir = NativeRuntime.planWorkdir(config),
      onFinished = onFinished,
    )
  }

  fun findStudioExe(): String? {
    val start = File(roots.arm64Rootfs, "home/vodka/.wine/drive_c")
    return search(start, 0)
  }

  private fun search(dir: File, depth: Int): String? {
    if (depth > 8 || !dir.isDirectory) return null
    val children = dir.listFiles() ?: return null
    for (child in children) {
      if (child.isFile && child.name.equals("RobloxStudioBeta.exe", ignoreCase = true)) {
        val relative = child.absolutePath.removePrefix(roots.arm64Rootfs.absolutePath)
        return relative.ifEmpty { child.absolutePath }
      }
    }
    for (child in children) {
      val found = search(child, depth + 1)
      if (found != null) return found
    }
    return null
  }
}

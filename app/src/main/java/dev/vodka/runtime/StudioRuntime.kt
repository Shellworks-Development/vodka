package dev.vodka.runtime

import java.io.File

class StudioRuntime(
  private val roots: RootfsManager,
  private val session: VodkaSession,
) {
  val guestRootfs: String = "/opt/vodka/rootfs-x86_64"
  val home: String = "/home/vodka"
  val winePrefix: String = "$guestRootfs/opt/vodka/prefix"
  val appConfig: String = "$home/.config/fex-emu/RobloxStudio.json"
  val fbDir: File = File(roots.baseDir, "fb")

  fun binds(): List<BindMount> = listOf(
    BindMount(roots.x86Rootfs.absolutePath, guestRootfs),
    BindMount(fbDir.absolutePath, "/fb"),
  )

  fun prepare() {
    fbDir.mkdirs()
  }

  fun configArray(studioExe: String? = null, pulse: String? = null): Array<String> = buildList {
    add("fex=/usr/bin/FEX")
    add("wine=/opt/kombucha/bin/wine")
    add("env=WINESERVER=/opt/kombucha/bin/wineserver")
    add("env=WINELOADER=/opt/kombucha/bin/wine")
    add("env=WINEDLLPATH=/opt/kombucha/lib/wine/x86_64-unix:/opt/kombucha/lib/wine/x86_64-windows")
    add("env=WINEDATADIR=/opt/kombucha/share/wine")
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

  fun runSetup(
    backend: ContainerBackend,
    onOutput: ((String) -> Unit)? = null,
    onFinished: (VodkaSession.Result) -> Unit,
  ) {
    runScript("/usr/local/bin/vodka-wine-setup", backend, onOutput, onFinished)
  }

  fun runScript(
    path: String,
    backend: ContainerBackend,
    onOutput: ((String) -> Unit)? = null,
    onFinished: (VodkaSession.Result) -> Unit,
  ) {
    runScriptEnv(path, emptyList(), backend, onOutput, onFinished)
  }

  fun runScriptEnv(
    path: String,
    extraEnv: List<String>,
    backend: ContainerBackend,
    onOutput: ((String) -> Unit)? = null,
    onFinished: (VodkaSession.Result) -> Unit,
  ) {
    writeFexConfig()
    prepare()
    val env = listOf(
      "HOME=$home",
      "FEX=/usr/bin/FEX",
      "WINE=/opt/kombucha/bin/wine",
      "WINESERVER=/opt/kombucha/bin/wineserver",
      "WINELOADER=/opt/kombucha/bin/wine",
      "WINEDLLPATH=/opt/kombucha/lib/wine/x86_64-unix:/opt/kombucha/lib/wine/x86_64-windows",
      "WINEDATADIR=/opt/kombucha/share/wine",
      "WINEPREFIX=$winePrefix",
      "GUEST_ROOTFS=$guestRootfs",
      "DXVK_DIR=$guestRootfs/opt/vodka/dxvk",
      "DISPLAY=:0",
      "FBSIZE=1280x720x24",
      "WINEDEBUG=-all",
      "FEX_DISKCACHE=1",
      "FEX_DISKCACHEPATH=$guestRootfs/opt/vodka/fexcache",
    ) + extraEnv
    session.launch(
      command = listOf("/bin/sh", path),
      backend = backend,
      binds = binds(),
      env = env,
      workingDir = home,
      onOutput = onOutput,
      onFinished = onFinished,
    )
  }

  fun launchStudioX11(
    studioExe: String,
    backend: ContainerBackend,
    onOutput: ((String) -> Unit)? = null,
    onFinished: (VodkaSession.Result) -> Unit,
  ) {
    runScriptEnv(
      "/usr/local/bin/vodka-studio",
      listOf("STUDIO_EXE=$studioExe"),
      backend,
      onOutput,
      onFinished,
    )
  }

  fun installStudio(
    installer: String,
    backend: ContainerBackend,
    onOutput: ((String) -> Unit)? = null,
    onFinished: (VodkaSession.Result) -> Unit,
  ) {
    runScriptEnv(
      "/usr/local/bin/vodka-install-studio",
      listOf("INSTALLER=$installer"),
      backend,
      onOutput,
      onFinished,
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
    val start = File(roots.x86Rootfs, "opt/vodka/prefix/drive_c")
    return search(start, 0)
  }

  private fun search(dir: File, depth: Int): String? {
    if (depth > 8 || !dir.isDirectory) return null
    val children = dir.listFiles() ?: return null
    for (child in children) {
      if (child.isFile && child.name.equals("RobloxStudioBeta.exe", ignoreCase = true)) {
        val relative = child.absolutePath.removePrefix(roots.x86Rootfs.absolutePath)
        return "/opt/vodka/rootfs-x86_64" + relative
      }
    }
    for (child in children) {
      val found = search(child, depth + 1)
      if (found != null) return found
    }
    return null
  }
}

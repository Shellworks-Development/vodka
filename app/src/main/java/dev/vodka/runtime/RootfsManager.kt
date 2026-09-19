package dev.vodka.runtime

import android.content.Context
import java.io.File

class RootfsManager(context: Context) {

  sealed class InstallResult {
    data class Installed(val files: Long, val bytes: Long) : InstallResult()
    data class Failed(val message: String) : InstallResult()
  }

  data class InboxResult(val name: String, val result: InstallResult)

  private val appContext = context.applicationContext

  val baseDir: File = File(context.filesDir, "runtime")
  val arm64Rootfs: File = File(baseDir, "rootfs-arm64")
  val x86Rootfs: File = File(baseDir, "rootfs-x86_64")
  val internalInbox: File = File(context.filesDir, "payloads")
  val externalInbox: File =
    File(appContext.getExternalFilesDir(null) ?: context.filesDir, "payloads")

  val prootTmpDir: File = File(context.filesDir, "proot-tmp")
  val shmDir: File = File(context.filesDir, "shm")

  val nativeLibDir: String get() = appContext.applicationInfo.nativeLibraryDir

  val prootPath: String
    get() {
      val bundled = File(nativeLibDir, "libproot.so")
      return if (bundled.exists()) bundled.absolutePath
      else File(arm64Rootfs, "usr/bin/proot").absolutePath
    }

  val fexBinary: String get() = File(arm64Rootfs, "usr/bin/FEX").absolutePath
  val hostThunks: String
    get() = File(arm64Rootfs, "usr/lib/aarch64-linux-gnu/fex-emu/HostThunks").absolutePath

  fun inboxLocations(): List<File> = listOf(internalInbox, externalInbox).distinct()

  fun ensureLayout() {
    val dirs = listOf(
      baseDir,
      arm64Rootfs,
      x86Rootfs,
      prootTmpDir,
      shmDir,
      File(arm64Rootfs, "home/vodka/.config/fex-emu"),
      File(arm64Rootfs, "home/vodka/.local/share"),
      File(arm64Rootfs, "home/vodka/.cache"),
    ) + inboxLocations()
    dirs.forEach { it.mkdirs() }
  }

  fun isInstalled(): Boolean = fileExists(arm64Rootfs, "usr/share/vodka/arch") || fileExists(arm64Rootfs, "bin/sh")

  fun isX86Installed(): Boolean =
    fileExists(x86Rootfs, "usr/share/vodka/arch") || fileExists(x86Rootfs, "usr/bin/wine")

  fun isFexInstalled(): Boolean = fileExists(arm64Rootfs, "usr/bin/FEX") || fileExists(arm64Rootfs, "bin/FEX")

  fun isPrefixReady(): Boolean = fileExists(x86Rootfs, "opt/vodka/prefix/drive_c")

  fun installArm64(archive: File): InstallResult =
    install(archive, arm64Rootfs) { it ->
      fileExists(it, "usr/share/vodka/arch") || fileExists(it, "bin/sh")
    }

  fun installX86(archive: File): InstallResult =
    install(archive, x86Rootfs) { it ->
      fileExists(it, "usr/share/vodka/arch") || fileExists(it, "usr/bin/wine")
    }

  fun installFex(archive: File): InstallResult =
    merge(archive, arm64Rootfs) { it ->
      fileExists(it, "usr/bin/FEX") || fileExists(it, "bin/FEX")
    }

  fun installBox64(archive: File): InstallResult =
    merge(archive, arm64Rootfs) { it ->
      fileExists(it, "usr/bin/box64") || fileExists(it, "bin/box64")
    }

  fun installMesa(archive: File): InstallResult =
    merge(archive, arm64Rootfs) { it ->
      fileExists(it, "usr/lib/libvulkan_freedreno.so") ||
        fileExists(it, "usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so")
    }

  fun installInbox(): List<InboxResult> {
    val results = mutableListOf<InboxResult>()
    val seen = mutableSetOf<String>()
    for (dir in inboxLocations()) {
      val files = dir.listFiles()?.sortedBy { it.name } ?: continue
      for (file in files) {
        if (!file.isFile || !file.name.endsWith(".tar.gz") || !seen.add(file.name)) continue
        val result = when {
          file.name.contains("x86_64") -> installX86(file)
          file.name.contains("fex") -> installFex(file)
          else -> installArm64(file)
        }
        results += InboxResult(file.name, result)
      }
    }
    return results
  }

  private fun install(archive: File, target: File, validate: (File) -> Boolean): InstallResult {
    if (!archive.isFile) return InstallResult.Failed("archive not found: ${archive.name}")
    val staging = File(baseDir, target.name + ".staging")
    staging.deleteRecursively()
    if (!staging.mkdirs()) return InstallResult.Failed("cannot create staging directory")
    val result = extract(archive, staging, validate)
    if (result is InstallResult.Failed) {
      staging.deleteRecursively()
      return result
    }
    target.deleteRecursively()
    if (!staging.renameTo(target)) {
      staging.copyRecursively(target, overwrite = true)
      staging.deleteRecursively()
    }
    return result
  }

  private fun merge(archive: File, target: File, validate: (File) -> Boolean): InstallResult {
    if (!archive.isFile) return InstallResult.Failed("archive not found: ${archive.name}")
    target.mkdirs()
    return extract(archive, target, validate)
  }

  private fun extract(archive: File, dest: File, validate: (File) -> Boolean): InstallResult {
    val raw = NativeArchive.extractTarGz(archive.absolutePath, dest.absolutePath)
    if (!raw.startsWith("ok ")) {
      return InstallResult.Failed(raw.removePrefix("error:").trim())
    }
    if (!validate(dest)) {
      return InstallResult.Failed("archive does not look like a Vodka payload")
    }
    val parts = raw.trim().split(" ")
    return InstallResult.Installed(
      files = parts.getOrNull(1)?.toLongOrNull() ?: 0L,
      bytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
    )
  }

  private fun fileExists(root: File, relative: String): Boolean =
    File(root, relative).let { it.exists() || it.isSymlink() }
}

private fun File.isSymlink(): Boolean = try {
  java.nio.file.Files.isSymbolicLink(toPath())
} catch (_: Exception) {
  false
}

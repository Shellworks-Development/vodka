package dev.vodka

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.vodka.databinding.ActivityMainBinding
import dev.vodka.runtime.BindMount
import dev.vodka.runtime.ContainerBackend
import dev.vodka.runtime.RootfsManager
import dev.vodka.runtime.RuntimeFetcher
import dev.vodka.runtime.StudioFetcher
import dev.vodka.runtime.StudioRuntime
import dev.vodka.runtime.VodkaSession
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

  private enum class Payload { BASE, X86, FEX }

  private lateinit var binding: ActivityMainBinding
  private lateinit var session: VodkaSession
  private lateinit var studio: StudioRuntime
  private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "vodka-ui-io") }

  private var selectedBackend = ContainerBackend.AUTO
  private var pendingPayload: Payload? = null

  private val prefs by lazy { getSharedPreferences("vodka", MODE_PRIVATE) }
  private val liveLog = StringBuilder()

  private fun beginLive(header: String) {
    synchronized(liveLog) { liveLog.setLength(0) }
    binding.status.text = header
  }

  private fun appendLive(text: String) {
    val output = synchronized(liveLog) {
      liveLog.append(text)
      if (liveLog.length > 4000) liveLog.delete(0, liveLog.length - 4000)
      liveLog.toString()
    }
    runOnUiThread { binding.status.text = output }
  }
  private fun githubToken(): String? = prefs.getString("gh_token", null)?.takeIf { it.isNotBlank() }

  private fun promptForToken() {
    val input = EditText(this).apply {
      setText(githubToken().orEmpty())
      hint = "ghp_…"
    }
    AlertDialog.Builder(this)
      .setTitle(R.string.token_button)
      .setView(input)
      .setPositiveButton("Save") { _, _ ->
        prefs.edit().putString("gh_token", input.text.toString().trim()).apply()
      }
      .setNeutralButton("Clear") { _, _ ->
        prefs.edit().remove("gh_token").apply()
      }
      .setNegativeButton("Cancel", null)
      .show()
  }

  private val pickArchive =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      val payload = pendingPayload
      pendingPayload = null
      if (uri != null && payload != null) installFrom(payload, uri)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    val roots = (application as VodkaApp).rootfs
    session = VodkaSession(
      roots.arm64Rootfs,
      roots.prootPath,
      defaultEnv = listOf(
        "HOME=/home/vodka",
        "XDG_CONFIG_HOME=/home/vodka/.config",
        "XDG_DATA_HOME=/home/vodka/.local/share",
        "XDG_CACHE_HOME=/home/vodka/.cache",
        "XDG_RUNTIME_DIR=/tmp",
        "TMPDIR=/tmp",
        "PROOT_TMP_DIR=${roots.prootTmpDir}",
      ),
      defaultBinds = listOf(
        BindMount("/proc", "/proc"),
        BindMount("/dev", "/dev"),
        BindMount(roots.shmDir.absolutePath, "/dev/shm"),
        BindMount("/sys", "/sys"),
      ),
    )
    studio = StudioRuntime(roots, session)
    studio.prepare()

    binding.backendAuto.isChecked = true
    binding.backendGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (!isChecked) return@addOnButtonCheckedListener
      selectedBackend = when (checkedId) {
        R.id.backendNamespaces -> ContainerBackend.NAMESPACES
        R.id.backendProot -> ContainerBackend.PROOT
        else -> ContainerBackend.AUTO
      }
    }

    binding.refreshButton.setOnClickListener { refreshStatus() }
    binding.primaryButton.setOnClickListener { primaryAction() }
    binding.advancedToggle.setOnClickListener {
      binding.advancedGroup.visibility =
        if (binding.advancedGroup.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }
    binding.installButton.setOnClickListener { pick(Payload.BASE) }
    binding.installX86Button.setOnClickListener { pick(Payload.X86) }
    binding.installFexButton.setOnClickListener { pick(Payload.FEX) }
    binding.installInboxButton.setOnClickListener { installInbox() }
    binding.setupButton.setOnClickListener { runSetup() }
    binding.runButton.setOnClickListener { runSmokeTest() }
    binding.downloadButton.setOnClickListener { downloadRuntime() }
    binding.installStudioButton.setOnClickListener { fetchStudio() }
    binding.displayButton.setOnClickListener { showDisplay() }
    binding.tokenButton.setOnClickListener { promptForToken() }

    refreshStatus()
    handleAutomation(intent)
  }

  private fun handleAutomation(intent: Intent?) {
    when (intent?.getStringExtra("dev.vodka.action")) {
      "install_inbox" -> installInbox()
      "smoke" -> runSmokeTest()
      "setup" -> runSetup()
      "launch" -> launchStudio()
      "exec" -> {
        val argv = intent.getStringArrayExtra("argv")?.toList().orEmpty()
        if (argv.isNotEmpty()) {
          setBusy(true)
          binding.status.text = getString(R.string.status_launching)
          val extraEnv = intent.getStringArrayExtra("env")?.toList().orEmpty()
          session.launch(command = argv, backend = selectedBackend, env = extraEnv) { report(it) }
        }
      }
      "wine" -> {
        val args = intent.getStringArrayExtra("args")?.toList().orEmpty()
        setBusy(true)
        binding.status.text = getString(R.string.status_launching)
        studio.runWine(args, selectedBackend) { report(it) }
      }
      "run" -> {
        val script = intent.getStringExtra("script")
        if (!script.isNullOrEmpty()) {
          setBusy(true)
          binding.status.text = getString(R.string.status_launching)
          studio.runScript(script, selectedBackend) { report(it) }
        }
      }
      "display" -> showDisplay()
      "download" -> downloadRuntime()
      "primary" -> primaryAction()
      "studio" -> fetchStudio()
      "settoken" -> intent.getStringExtra("token")?.let { prefs.edit().putString("gh_token", it).apply() }
    }
  }

  private fun pick(payload: Payload) {
    pendingPayload = payload
    pickArchive.launch(
      arrayOf(
        "application/gzip",
        "application/x-gzip",
        "application/x-tar",
        "application/octet-stream",
        "*/*",
      ),
    )
  }

  private fun updateSetup() {
    val roots = (application as VodkaApp).rootfs
    val base = roots.isInstalled()
    val wine = roots.isX86Installed()
    val fex = roots.isFexInstalled()
    val prefix = roots.isPrefixReady()
    val studioReady = studio.findStudioExe() != null
    val done = listOf(base, wine, fex, prefix, studioReady).count { it }

    binding.stepChecklist.text = buildString {
      append(mark(base)).append(" base   ")
      append(mark(wine)).append(" wine   ")
      append(mark(fex)).append(" fex   ")
      append(mark(prefix)).append(" prefix   ")
      append(mark(studioReady)).append(" studio")
    }
    binding.stepProgress.setProgressCompat(done, true)

    when {
      !base -> {
        binding.stepTitle.text = getString(R.string.step_title_base)
        binding.stepDetail.text = getString(R.string.step_detail_base)
        binding.primaryButton.text = getString(R.string.action_base)
      }
      !wine -> {
        binding.stepTitle.text = getString(R.string.step_title_wine)
        binding.stepDetail.text = getString(R.string.step_detail_wine)
        binding.primaryButton.text = getString(R.string.action_wine)
      }
      !fex -> {
        binding.stepTitle.text = getString(R.string.step_title_fex)
        binding.stepDetail.text = getString(R.string.step_detail_fex)
        binding.primaryButton.text = getString(R.string.action_fex)
      }
      !prefix -> {
        binding.stepTitle.text = getString(R.string.step_title_prefix)
        binding.stepDetail.text = getString(R.string.step_detail_prefix)
        binding.primaryButton.text = getString(R.string.action_prefix)
      }
      !studioReady -> {
        binding.stepTitle.text = getString(R.string.step_title_install_studio)
        binding.stepDetail.text = getString(R.string.step_detail_install_studio)
        binding.primaryButton.text = getString(R.string.action_install_studio_step)
      }
      else -> {
        binding.stepTitle.text = getString(R.string.step_title_launch)
        binding.stepDetail.text = getString(R.string.step_detail_launch)
        binding.primaryButton.text = getString(R.string.action_launch)
      }
    }
    setBusy(false)
  }

  private fun primaryAction() {
    val roots = (application as VodkaApp).rootfs
    when {
      !roots.isInstalled() -> downloadPayloads(listOf("rootfs-arm64.tar.gz"))
      !roots.isX86Installed() -> downloadPayloads(listOf("rootfs-x86_64.tar.gz"))
      !roots.isFexInstalled() -> downloadPayloads(
        listOf("fex-aarch64.tar.gz", "box64-aarch64.tar.gz", "mesa-turnip-aarch64.tar.gz"),
      )
      !roots.isPrefixReady() -> runSetup()
      studio.findStudioExe() == null -> fetchStudio()
      else -> launchStudio()
    }
  }

  private fun mark(done: Boolean): String = if (done) "✓" else "•"

  private fun refreshStatus() {
    setBusy(true)
    binding.status.text = getString(R.string.status_unknown)
    io.execute {
      val roots = (application as VodkaApp).rootfs
      val caps = session.probe()
      val text = buildString {
        append(caps.describe())
        appendLine("base rootfs ${installed(roots.isInstalled())}")
        appendLine("wine rootfs ${installed(roots.isX86Installed())}")
        appendLine("fex         ${installed(roots.isFexInstalled())}")
      }
      runOnUiThread {
        binding.status.text = text
        updateSetup()
      }
    }
  }

  private fun installFrom(payload: Payload, uri: Uri) {
    setBusy(true)
    binding.status.text = getString(R.string.status_installing)
    val roots = (application as VodkaApp).rootfs
    io.execute {
      val cache = File(cacheDir, "vodka-payload.tar.gz")
      val copied = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
          cache.outputStream().use { output -> input.copyTo(output) }
        } != null
      }.getOrDefault(false)

      val result = if (!copied) {
        RootfsManager.InstallResult.Failed("cannot read selected file")
      } else {
        when (payload) {
          Payload.BASE -> roots.installArm64(cache)
          Payload.X86 -> roots.installX86(cache)
          Payload.FEX -> roots.installFex(cache)
        }
      }
      cache.delete()

      val message = when (result) {
        is RootfsManager.InstallResult.Installed ->
          "installed ${payload.name.lowercase()}: ${result.files} files, ${result.bytes / 1024 / 1024} MiB"
        is RootfsManager.InstallResult.Failed -> result.message
      }
      runOnUiThread {
        binding.status.text = message
        updateSetup()
      }
    }
  }

  private fun installInbox() {
    setBusy(true)
    binding.status.text = getString(R.string.status_installing)
    val roots = (application as VodkaApp).rootfs
    io.execute {
      val results = roots.installInbox()
      val text = if (results.isEmpty()) {
        "inbox empty (" + roots.inboxLocations().joinToString(", ") { it.absolutePath } + ")"
      } else {
        results.joinToString("\n") { r ->
          when (val result = r.result) {
            is RootfsManager.InstallResult.Installed ->
              "${r.name}: ${result.files} files, ${result.bytes / 1024 / 1024} MiB"
            is RootfsManager.InstallResult.Failed -> "${r.name}: ${result.message}"
          }
        }
      }
      runOnUiThread {
        binding.status.text = text
        updateSetup()
      }
    }
  }

  private fun downloadRuntime() {
    downloadPayloads(
      listOf(
        "rootfs-arm64.tar.gz",
        "rootfs-x86_64.tar.gz",
        "fex-aarch64.tar.gz",
        "box64-aarch64.tar.gz",
        "mesa-turnip-aarch64.tar.gz",
      ),
    )
  }

  private fun downloadPayloads(names: List<String>) {
    setBusy(true)
    binding.status.text = "Checking release…"
    val roots = (application as VodkaApp).rootfs
    io.execute {
      val report = StringBuilder()
      try {
        val fetcher = RuntimeFetcher(roots.internalInbox, githubToken())
        val byName = fetcher.listAssets().associateBy { it.name }
        val installedDir = File(roots.baseDir, "installed").apply { mkdirs() }
        val needed = ArrayList<String>()
        val toInstall = ArrayList<String>()

        for (name in names) {
          val asset = byName[name]
          if (asset == null) {
            synchronized(report) { report.append(name).append(": not in release\n") }
            continue
          }
          val remote = byName["$name.sha256"]?.let {
            runCatching { fetcher.downloadText(it).split(Regex("\\s+"))[0] }.getOrNull()
          }
          val local = File(installedDir, name).takeIf { it.exists() }?.readText()?.trim()
          val inboxFile = File(roots.internalInbox, name)
          val hasCopy = inboxFile.exists() && inboxFile.length() == asset.size
          when {
            remote != null && remote == local && hasCopy -> {
              synchronized(report) { report.append(name).append(": unchanged, skipped\n") }
            }
            hasCopy -> {
              synchronized(report) { report.append(name).append(": using existing download\n") }
              toInstall.add(name)
            }
            else -> {
              needed.add(name)
              toInstall.add(name)
            }
          }
        }

        val progress = java.util.concurrent.ConcurrentHashMap<String, LongArray>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        for (name in needed) {
          val asset = byName.getValue(name)
          progress[name] = longArrayOf(0, asset.size)
          pool.execute {
            runCatching {
              fetcher.download(asset) { done, total ->
                progress[name] = longArrayOf(done, total)
                publishProgress(report, needed, progress)
              }
            }.onFailure { error ->
              synchronized(report) {
                report.append(name).append(": download failed: ").append(error.message).append('\n')
              }
            }
          }
        }
        pool.shutdown()
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES)

        for (name in toInstall) {
          val file = File(roots.internalInbox, name)
          val result = when (name) {
            "rootfs-arm64.tar.gz" -> roots.installArm64(file)
            "rootfs-x86_64.tar.gz" -> roots.installX86(file)
            "fex-aarch64.tar.gz" -> roots.installFex(file)
            "box64-aarch64.tar.gz" -> roots.installBox64(file)
            "mesa-turnip-aarch64.tar.gz" -> roots.installMesa(file)
            else -> null
          }
          if (result is RootfsManager.InstallResult.Installed) {
            byName["$name.sha256"]?.let {
              runCatching { File(installedDir, name).writeText(fetcher.downloadText(it).split(Regex("\\s+"))[0]) }
            }
          }
          synchronized(report) { report.append(name).append(": ").append(describe(result)).append('\n') }
          publishProgress(report, needed, progress)
        }
      } catch (e: Exception) {
        synchronized(report) { report.append("failed: ").append(e.message) }
      }
      File(roots.baseDir, "download.log").writeText(report.toString())
      runOnUiThread {
        binding.status.text = report.toString()
        updateSetup()
      }
    }
  }

  private fun publishProgress(
    report: StringBuilder,
    names: List<String>,
    progress: java.util.concurrent.ConcurrentHashMap<String, LongArray>,
  ) {
    val text = buildString {
      synchronized(report) { append(report) }
      for (name in names) {
        val value = progress[name] ?: continue
        val total = value[1].coerceAtLeast(1)
        val percent = (value[0] * 100 / total).toInt()
        appendLine("$name: $percent%  (${value[0] / 1048576}/${value[1] / 1048576} MB)")
      }
    }
    runOnUiThread { binding.status.text = text }
  }

  private fun fetchStudio() {
    setBusy(true)
    beginLive("Downloading Roblox Studio…")
    val roots = (application as VodkaApp).rootfs
    io.execute {
      try {
        val downloads = File(roots.baseDir, "downloads").apply { mkdirs() }
        val fetcher = StudioFetcher(downloads)
        val version = fetcher.latestVersion()
        val packages = fetcher.packageNames(version)
        runOnUiThread { beginLive("Downloading Roblox Studio ${version.version} (${packages.size} packages)…") }

        val zips = ArrayList<File>()
        for ((index, name) in packages.withIndex()) {
          val zip = File(downloads, name)
          fetcher.downloadPackageByName(version, name, zip) { done, total ->
            if (total > 0) {
              val percent = (done * 100 / total).toInt()
              runOnUiThread {
                binding.status.text = "[${index + 1}/${packages.size}] $name: $percent%"
              }
            }
          }
          zips.add(zip)
        }

        runOnUiThread { beginLive("Extracting Roblox Studio…") }
        val destination = File(roots.x86Rootfs, "opt/vodka/prefix/drive_c/RobloxStudio")
        destination.deleteRecursively()
        destination.mkdirs()
        var files = 0
        for (zip in zips) {
          java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
              val entry = stream.nextEntry ?: break
              val name = entry.name.replace('\\', '/').trimStart('/')
              if (name.isEmpty() || name == "." || name.contains("..")) {
                stream.closeEntry()
                continue
              }
              val out = File(destination, name)
              if (entry.isDirectory || name.endsWith("/")) {
                out.mkdirs()
              } else if (!out.isDirectory) {
                out.parentFile?.mkdirs()
                out.outputStream().use { stream.copyTo(it) }
                files++
              }
              stream.closeEntry()
              if (files % 200 == 0) {
                val count = files
                runOnUiThread { binding.status.text = "Extracting Roblox Studio… $count files" }
              }
            }
          }
          zip.delete()
        }
        runOnUiThread {
          binding.status.text = "Roblox Studio ${version.version} installed ($files files)"
          updateSetup()
        }
      } catch (e: Exception) {
        runOnUiThread {
          binding.status.text = "Studio install failed: ${e.message}"
          setBusy(false)
        }
      }
    }
  }

  private fun showDisplay() {
    studio.prepare()
    val fb = File(studio.fbDir, "Xvfb_screen0")
    startActivity(
      Intent(this, DisplayActivity::class.java).apply {
        putExtra("fb", fb.absolutePath)
        putExtra("width", 1280)
        putExtra("height", 720)
      },
    )
  }

  private fun describe(result: RootfsManager.InstallResult?): String = when (result) {
    null -> "skipped"
    is RootfsManager.InstallResult.Installed ->
      "${result.files} files, ${result.bytes / 1024 / 1024} MiB"
    is RootfsManager.InstallResult.Failed -> result.message
  }

  private fun runSetup() {
    val roots = (application as VodkaApp).rootfs
    when {
      !roots.isInstalled() -> {
        binding.status.text = getString(R.string.status_rootfs_missing)
        return
      }
      !roots.isX86Installed() -> {
        binding.status.text = getString(R.string.status_x86_missing)
        return
      }
      !roots.isFexInstalled() -> {
        binding.status.text = getString(R.string.status_fex_missing)
        return
      }
    }
    setBusy(true)
    beginLive(getString(R.string.status_setup))
    studio.runSetup(selectedBackend, { appendLive(it) }) { result ->
      report(result)
      runOnUiThread { updateSetup() }
    }
  }

  private fun launchStudio() {
    val roots = (application as VodkaApp).rootfs
    when {
      !roots.isInstalled() -> {
        binding.status.text = getString(R.string.status_rootfs_missing)
        return
      }
      !roots.isX86Installed() -> {
        binding.status.text = getString(R.string.status_x86_missing)
        return
      }
      !roots.isFexInstalled() -> {
        binding.status.text = getString(R.string.status_fex_missing)
        return
      }
    }
    val exe = studio.findStudioExe()
    if (exe == null) {
      binding.status.text = getString(R.string.status_studio_missing)
      return
    }
    setBusy(true)
    beginLive(getString(R.string.status_launching))
    studio.launchStudioX11(exe, selectedBackend, { appendLive(it) }) { report(it) }
    showDisplay()
  }

  private fun runSmokeTest() {
    val roots = (application as VodkaApp).rootfs
    if (!roots.isInstalled()) {
      binding.status.text = getString(R.string.status_rootfs_missing)
      return
    }
    setBusy(true)
    binding.status.text = getString(R.string.status_launching)
    session.launch(
      command = listOf("/bin/sh", "-c", "id; uname -a; echo vodka-ok"),
      backend = selectedBackend,
    ) { result -> report(result) }
  }

  private fun report(result: VodkaSession.Result) {
    val base = when (result) {
      is VodkaSession.Result.Exited -> "container exited ${result.code}"
      is VodkaSession.Result.Failed -> result.message
    }
    val text = if (result.log.isNotEmpty()) "$base\n\n${result.log}" else base
    runOnUiThread {
      binding.status.text = text
      setBusy(false)
    }
  }

  private fun installed(value: Boolean): String = if (value) "installed" else "not installed"

  private fun setBusy(busy: Boolean) {
    binding.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
    for (button in listOf(
      binding.primaryButton,
      binding.refreshButton,
      binding.advancedToggle,
      binding.installButton,
      binding.installX86Button,
      binding.installFexButton,
      binding.installInboxButton,
      binding.setupButton,
      binding.runButton,
      binding.downloadButton,
      binding.installStudioButton,
      binding.displayButton,
      binding.tokenButton,
    )) {
      button.isEnabled = !busy
    }
  }

  override fun onDestroy() {
    session.shutdown()
    io.shutdownNow()
    super.onDestroy()
  }
}

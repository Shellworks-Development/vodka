package dev.vodka

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.vodka.databinding.ActivityMainBinding
import dev.vodka.runtime.BindMount
import dev.vodka.runtime.ContainerBackend
import dev.vodka.runtime.RootfsManager
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
    val done = listOf(base, wine, fex, prefix).count { it }

    binding.stepChecklist.text = buildString {
      append(mark(base)).append(" base   ")
      append(mark(wine)).append(" wine   ")
      append(mark(fex)).append(" fex   ")
      append(mark(prefix)).append(" prefix")
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
      studio.findStudioExe() == null -> {
        binding.stepTitle.text = getString(R.string.step_title_launch_missing)
        binding.stepDetail.text = getString(R.string.step_detail_launch_missing)
        binding.primaryButton.text = getString(R.string.action_launch)
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
      !roots.isInstalled() -> pick(Payload.BASE)
      !roots.isX86Installed() -> pick(Payload.X86)
      !roots.isFexInstalled() -> pick(Payload.FEX)
      !roots.isPrefixReady() -> runSetup()
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
    binding.status.text = getString(R.string.status_setup)
    studio.runSetup(selectedBackend) { result ->
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
    binding.status.text = getString(R.string.status_launching)
    studio.launch(exe, selectedBackend) { report(it) }
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

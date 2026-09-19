package dev.vodka

import android.app.Application
import dev.vodka.runtime.RootfsManager

class VodkaApp : Application() {

  lateinit var rootfs: RootfsManager
    private set

  override fun onCreate() {
    super.onCreate()
    rootfs = RootfsManager(this).also { it.ensureLayout() }
  }
}

package dev.vodka.runtime

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class StudioFetcher(private val destDir: File) {

  data class Version(val version: String, val upload: String)

  fun latestVersion(): Version {
    val json = get(VERSION_URL)
    val objectJson = JSONObject(json)
    return Version(
      version = objectJson.getString("version"),
      upload = objectJson.getString("clientVersionUpload"),
    )
  }

  fun downloadPackage(
    version: Version,
    target: File,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
  ): File {
    target.parentFile?.mkdirs()
    val connection = URL("https://setup.rbxcdn.com/${version.upload}-RobloxStudio.zip")
      .openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", "Vodka")
    connection.connectTimeout = 20000
    connection.readTimeout = 180000
    val total = connection.contentLengthLong
    connection.inputStream.use { input ->
      target.outputStream().use { output ->
        val buffer = ByteArray(1 shl 16)
        var done = 0L
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          output.write(buffer, 0, read)
          done += read
          onProgress(done, total)
        }
      }
    }
    return target
  }

  fun downloadInstaller(version: Version, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
    val url = "https://setup.rbxcdn.com/${version.upload}-RobloxStudioInstaller.exe"
    val target = File(destDir, "RobloxStudioInstaller.exe")
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", "Vodka")
    connection.connectTimeout = 20000
    connection.readTimeout = 120000
    val total = connection.contentLengthLong
    connection.inputStream.use { input ->
      target.outputStream().use { output ->
        val buffer = ByteArray(1 shl 16)
        var readTotal = 0L
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          output.write(buffer, 0, read)
          readTotal += read
          onProgress(readTotal, total)
        }
      }
    }
    return target
  }

  private fun get(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", "Vodka")
    connection.connectTimeout = 20000
    connection.readTimeout = 60000
    return connection.inputStream.bufferedReader().use { it.readText() }
  }

  companion object {
    private const val VERSION_URL =
      "https://clientsettingscdn.roblox.com/v2/client-version/WindowsStudio64"
  }
}

package dev.vodka.runtime

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class RuntimeFetcher(private val destDir: File, private val token: String? = null) {

  data class Asset(val id: Long, val name: String, val url: String, val size: Long)

  fun listAssets(): List<Asset> {
    val json = request(RELEASE_URL).inputStream.bufferedReader().use { it.readText() }
    val assets = JSONObject(json).getJSONArray("assets")
    val out = ArrayList<Asset>(assets.length())
    for (i in 0 until assets.length()) {
      val asset = assets.getJSONObject(i)
      out += Asset(
        id = asset.getLong("id"),
        name = asset.getString("name"),
        url = asset.getString("browser_download_url"),
        size = asset.optLong("size"),
      )
    }
    return out
  }

  fun downloadText(asset: Asset): String {
    val connection = request(ASSET_URL.format(asset.id))
    connection.setRequestProperty("Accept", "application/octet-stream")
    return connection.inputStream.bufferedReader().use { it.readText() }.trim()
  }

  fun download(asset: Asset, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
    val target = File(destDir, asset.name)
    val connection = request(ASSET_URL.format(asset.id))
    connection.setRequestProperty("Accept", "application/octet-stream")
    connection.instanceFollowRedirects = true
    connection.inputStream.use { input ->
      target.outputStream().use { output ->
        val buffer = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          output.write(buffer, 0, read)
          total += read
          onProgress(total, asset.size)
        }
      }
    }
    return target
  }

  private fun request(url: String): HttpURLConnection {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", "Vodka")
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    if (!token.isNullOrEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
    connection.connectTimeout = 20000
    connection.readTimeout = 120000
    return connection
  }

  companion object {
    private const val RELEASE_URL =
      "https://api.github.com/repos/Shellworks-Development/vodka/releases/tags/runtime-latest"
    private const val ASSET_URL =
      "https://api.github.com/repos/Shellworks-Development/vodka/releases/assets/%d"
  }
}

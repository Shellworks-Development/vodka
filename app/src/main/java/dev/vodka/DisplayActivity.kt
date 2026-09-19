package dev.vodka

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.SurfaceHolder
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DisplayActivity : AppCompatActivity() {

  @Volatile private var running = true
  private var renderThread: Thread? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_display)

    val width = intent.getIntExtra("width", 1280)
    val height = intent.getIntExtra("height", 720)
    val frameBuffer = File(intent.getStringExtra("fb") ?: "")

    val surfaceView = findViewById<android.view.SurfaceView>(R.id.surface)
    surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        renderThread = Thread { render(holder, frameBuffer, width, height) }.also { it.start() }
      }

      override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

      override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
      }
    })
  }

  private fun render(holder: SurfaceHolder, file: File, width: Int, height: Int) {
    val pixels = IntArray(width * height)
    val raw = ByteArray(width * height * 4)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val destination = Rect()
    var raf: RandomAccessFile? = null

    while (running) {
      if (raf == null) {
        raf = try {
          RandomAccessFile(file, "r")
        } catch (_: Exception) {
          Thread.sleep(120); continue
        }
      }
      try {
        raf.seek(0)
        raf.readFully(raw)
      } catch (_: Exception) {
        Thread.sleep(120); continue
      }

      val buffer = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder())
      for (i in pixels.indices) {
        pixels[i] = 0xFF000000.toInt() or (buffer.getInt(i * 4) and 0x00FFFFFF)
      }
      bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

      val canvas = holder.lockCanvas() ?: continue
      try {
        val scale = minOf(canvas.width.toFloat() / width, canvas.height.toFloat() / height)
        val dw = (width * scale).toInt()
        val dh = (height * scale).toInt()
        destination.set(
          (canvas.width - dw) / 2,
          (canvas.height - dh) / 2,
          (canvas.width - dw) / 2 + dw,
          (canvas.height - dh) / 2 + dh,
        )
        canvas.drawBitmap(bitmap, null, destination, null)
      } finally {
        holder.unlockCanvasAndPost(canvas)
      }
      Thread.sleep(33)
    }
    raf?.close()
  }

  override fun onDestroy() {
    running = false
    renderThread?.join(500)
    super.onDestroy()
  }
}

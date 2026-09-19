package dev.vodka

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DisplayActivity : AppCompatActivity() {

  @Volatile private var running = true
  private var renderThread: Thread? = null

  @Volatile private var scale = 1f
  @Volatile private var offsetX = 0f
  @Volatile private var offsetY = 0f

  private var socket: Socket? = null
  private var output: OutputStream? = null
  private var inputPort = 5599

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_display)

    val width = intent.getIntExtra("width", 1280)
    val height = intent.getIntExtra("height", 720)
    inputPort = intent.getIntExtra("inputPort", 5599)
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

    val keyInput = findViewById<EditText>(R.id.keyInput)
    keyInput.requestFocus()
    keyInput.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence?, start: Int, before: Int, after: Int) {}
      override fun afterTextChanged(s: Editable?) {
        if (s == null) return
        val removed = s.length
        for (i in 0 until removed) {
          val code = s[i].code
          if (code >= 32) {
            send("k $code d")
            send("k $code u")
          }
        }
        s.clear()
      }
    })
    keyInput.setOnKeyListener { _, keyCode, event ->
      val keysym = androidKeyToKeysym(keyCode, event)
      if (keysym == 0) return@setOnKeyListener false
      send("k $keysym ${if (event.action == KeyEvent.ACTION_DOWN) "d" else "u"}")
      true
    }

    keyInput.post {
      val imm = getSystemService(InputMethodManager::class.java)
      imm?.showSoftInput(keyInput, InputMethodManager.SHOW_IMPLICIT)
    }
  }

  private fun connect() {
    if (output != null) return
    try {
      val s = Socket()
      s.connect(InetSocketAddress("127.0.0.1", inputPort), 500)
      socket = s
      output = s.getOutputStream()
      android.util.Log.i("VodkaInput", "connected to input server on $inputPort")
    } catch (error: Exception) {
      output = null
      android.util.Log.w("VodkaInput", "input server connect failed: $error")
    }
  }

  private fun send(line: String) {
    if (output == null) connect()
    val stream = output ?: return
    try {
      stream.write((line + "\n").toByteArray())
      stream.flush()
    } catch (error: Exception) {
      android.util.Log.w("VodkaInput", "send failed: $error")
      output = null
      socket = null
    }
  }

  private fun androidKeyToKeysym(keyCode: Int, event: KeyEvent): Int = when (keyCode) {
    KeyEvent.KEYCODE_ENTER -> 0xFF0D
    KeyEvent.KEYCODE_DEL -> 0xFF08
    KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
    KeyEvent.KEYCODE_TAB -> 0xFF09
    KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> 0xFF1B
    KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
    KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
    KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
    KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
    KeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1
    KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2
    KeyEvent.KEYCODE_CTRL_LEFT -> 0xFFE3
    KeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE4
    KeyEvent.KEYCODE_ALT_LEFT -> 0xFFE9
    KeyEvent.KEYCODE_ALT_RIGHT -> 0xFFEA
    else -> event.unicodeChar.takeIf { it > 0 } ?: 0
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (scale <= 0f) return true
    val x = ((event.x - offsetX) / scale).toInt()
    val y = ((event.y - offsetY) / scale).toInt()
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      android.util.Log.i("VodkaInput", "touch down at $x,$y (scale=$scale)")
    }
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        send("m $x $y")
        send("b 1 d")
      }
      MotionEvent.ACTION_MOVE -> send("m $x $y")
      MotionEvent.ACTION_UP -> {
        send("m $x $y")
        send("b 1 u")
      }
    }
    return true
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
        val s = minOf(canvas.width.toFloat() / width, canvas.height.toFloat() / height)
        val dw = (width * s).toInt()
        val dh = (height * s).toInt()
        offsetX = (canvas.width - dw) / 2f
        offsetY = (canvas.height - dh) / 2f
        scale = s
        destination.set(
          offsetX.toInt(),
          offsetY.toInt(),
          offsetX.toInt() + dw,
          offsetY.toInt() + dh,
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
    try {
      socket?.close()
    } catch (_: Exception) {
    }
    super.onDestroy()
  }
}

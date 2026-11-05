package com.droiddeck.launcher.chrome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.io.IOException
import kotlin.math.abs
import kotlin.math.min

class ChromeDisplayView(context: Context) : View(context) {
    var onFirstFrame: () -> Unit = {}
    var onFailure: (Throwable) -> Unit = {}
    private val imageLock = Any()
    private var image: Bitmap? = null
    private val destination = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pressed = mutableMapOf<Int, Int>()
    @Volatile private var connection: RfbConnection? = null
    @Volatile private var pendingSocket: LocalSocket? = null
    @Volatile private var generation = 0
    private var dragging = false
    private var twoFingers = false
    private var previousY = 0f
    private var composing = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.rgb(18, 36, 50))
    }

    fun connect(path: String, password: CharArray) {
        disconnect()
        val ticket = generation
        Thread({
            var received = false
            var client: RfbConnection? = null
            var socket: LocalSocket? = null
            try {
                val transport = LocalSocket()
                socket = transport
                if (ticket != generation) return@Thread
                pendingSocket = transport
                transport.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                transport.soTimeout = 10000
                if (transport.peerCredentials.uid != android.os.Process.myUid()) {
                    throw IOException("The display belongs to a different application")
                }
                client = RfbConnection(transport.inputStream, transport.outputStream, transport, object : RfbConnection.Listener {
                    override fun connected() { transport.soTimeout = 0 }
                    override fun resized(width: Int, height: Int) {
                        synchronized(imageLock) {
                            if (ticket == generation) image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        }
                    }
                    override fun rectangle(x: Int, y: Int, width: Int, height: Int, pixels: IntArray) {
                        synchronized(imageLock) {
                            if (ticket == generation) image?.setPixels(pixels, 0, width, x, y, width, height)
                        }
                    }
                    override fun frame() {
                        if (ticket != generation) return
                        postInvalidate()
                        if (!received) {
                            received = true
                            post { if (ticket == generation) onFirstFrame() }
                        }
                    }
                })
                if (ticket != generation) return@Thread
                connection = client
                client.run(password)
            } catch (error: IOException) {
                post { if (ticket == generation) onFailure(error) }
            } finally {
                password.fill('\u0000')
                runCatching { client?.close() }
                runCatching { socket?.close() }
            }
        }, "chrome-display").start()
    }

    fun disconnect() {
        generation++
        runCatching { connection?.close() }
        runCatching { pendingSocket?.close() }
        connection = null
        pendingSocket = null
        pressed.clear()
        composing = ""
        dragging = false
        twoFingers = false
        synchronized(imageLock) { image = null }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(imageLock) {
            val frame = image ?: return
            val scale = min(width.toFloat() / frame.width, height.toFloat() / frame.height)
            val w = frame.width * scale
            val h = frame.height * scale
            destination.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            canvas.drawBitmap(frame, null, destination, paint)
        }
    }

    private fun pointer(x: Float, y: Float, buttons: Int) {
        val point = synchronized(imageLock) {
            val frame = image ?: return
            if (destination.width() <= 0 || destination.height() <= 0) return
            Pair(((x - destination.left) * frame.width / destination.width()).toInt(),
                ((y - destination.top) * frame.height / destination.height()).toInt())
        }
        send { pointer(point.first, point.second, buttons) }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            requestFocus()
            pointer(e.x, e.y, 0)
            return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            pointer(e.x, e.y, 1)
            pointer(e.x, e.y, 0)
            performClick()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            pointer(e.x, e.y, 1)
            pointer(e.x, e.y, 0)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            pointer(e.x, e.y, 4)
            pointer(e.x, e.y, 0)
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!dragging && e1 != null) pointer(e1.x, e1.y, 1)
            dragging = true
            pointer(e2.x, e2.y, 1)
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1) {
            val middleY = (event.getY(0) + event.getY(1)) / 2
            if (!twoFingers) {
                val cancel = MotionEvent.obtain(event)
                cancel.action = MotionEvent.ACTION_CANCEL
                gestures.onTouchEvent(cancel)
                cancel.recycle()
                pointer(event.x, event.y, 0)
                twoFingers = true
                dragging = false
                previousY = middleY
            } else if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val step = 24 * resources.displayMetrics.density
                val delta = middleY - previousY
                val count = min(8, (abs(delta) / step).toInt())
                repeat(count) {
                    pointer(event.x, event.y, if (delta < 0) 16 else 8)
                    pointer(event.x, event.y, 0)
                }
                if (count > 0) previousY = middleY
            }
            return true
        }
        if (!twoFingers) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging || twoFingers) pointer(event.x, event.y, 0)
            dragging = false
            twoFingers = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = ""
                text?.let { typeText(it.toString()) }
                return true
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composing = text?.toString().orEmpty()
                return true
            }
            override fun finishComposingText(): Boolean {
                val text = composing
                composing = ""
                typeText(text)
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composing.isNotEmpty()) composing = composing.dropLast(min(beforeLength, composing.length))
                else repeat(beforeLength.coerceIn(0, 100)) { tapKey(0xff08) }
                repeat(afterLength.coerceIn(0, 100)) { tapKey(0xffff) }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean = dispatchKeyEvent(event)
            override fun performEditorAction(editorAction: Int): Boolean {
                tapKey(0xff0d)
                return true
            }
        }
    }

    fun typeText(text: String) {
        var position = 0
        while (position < text.length) {
            val codepoint = text.codePointAt(position)
            tapKey(if (codepoint <= 255) codepoint else 0x01000000 or codepoint)
            position += Character.charCount(codepoint)
        }
    }

    fun tapKey(keysym: Int) {
        send { key(keysym, true); key(keysym, false) }
    }

    fun shortcut(keysym: Int) {
        send { key(0xffe3, true); key(keysym, true); key(keysym, false); key(0xffe3, false) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val symbol = specialKey(keyCode) ?: event.getUnicodeChar(event.metaState and
            KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_ALT_MASK.inv() and KeyEvent.META_META_MASK.inv())
            .takeIf { it in 1..0x10ffff }?.let { if (it <= 255) it else 0x01000000 or it }
            ?: return super.onKeyDown(keyCode, event)
        pressed[keyCode] = symbol
        send { key(symbol, true) }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val symbol = pressed.remove(keyCode) ?: return super.onKeyUp(keyCode, event)
        send { key(symbol, false) }
        return true
    }

    private fun specialKey(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_ENTER -> 0xff0d
        KeyEvent.KEYCODE_TAB -> 0xff09
        KeyEvent.KEYCODE_DEL -> 0xff08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
        KeyEvent.KEYCODE_ESCAPE -> 0xff1b
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
        KeyEvent.KEYCODE_DPAD_UP -> 0xff52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
        KeyEvent.KEYCODE_MOVE_END -> 0xff57
        KeyEvent.KEYCODE_PAGE_UP -> 0xff55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
        KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
        KeyEvent.KEYCODE_META_LEFT -> 0xffeb
        KeyEvent.KEYCODE_META_RIGHT -> 0xffec
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xffbe + code - KeyEvent.KEYCODE_F1
        else -> null
    }

    private inline fun send(action: RfbConnection.() -> Unit) {
        try { connection?.takeIf { it.isReady }?.action() }
        catch (error: IOException) { onFailure(error) }
    }
}

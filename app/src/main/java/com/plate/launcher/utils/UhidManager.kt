package com.plate.launcher.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.OutputStream

/**
 * UHID (User space HID) support for YoukiDex.
 *
 * Allows the phone to act as a USB HID keyboard/mouse device when connected
 * to a computer via USB OTG — exactly what `scrcpy --otg` does internally.
 *
 * How it works:
 *   1. Opens /dev/uhid via Shizuku or root shell (needs elevated privilege).
 *   2. Writes a UHID_CREATE2 packet with a standard HID report descriptor
 *      (combined keyboard + mouse) so the host PC sees a single composite
 *      HID device.
 *   3. Subsequent UHID_INPUT2 packets carry the actual key/mouse reports.
 *   4. UHID_DESTROY tears it down cleanly on stop().
 *
 * Requirements:
 *   - Shizuku (preferred) or root access — /dev/uhid needs shell-level perms.
 *   - USB connected in data mode (not charge-only) to a host PC.
 *
 * Usage:
 *   val uhid = UhidManager.getInstance(context)
 *   uhid.start(shizukuManager)          // or uhid.startAsRoot(rootManager)
 *   uhid.sendKey(keyCode, modifier)     // send key press
 *   uhid.sendMouseMove(dx, dy, buttons) // send mouse event
 *   uhid.stop()
 */
class UhidManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "UhidManager"
        private const val UHID_PATH = "/dev/uhid"

        // ── UHID event types ──────────────────────────────────────────────
        private const val UHID_CREATE2 : Short = 11
        private const val UHID_INPUT2  : Short = 12
        private const val UHID_DESTROY : Short = 1

        // ── HID report IDs ────────────────────────────────────────────────
        private const val REPORT_ID_KEYBOARD : Byte = 1
        private const val REPORT_ID_MOUSE    : Byte = 2

        @Volatile private var instance: UhidManager? = null
        fun getInstance(ctx: Context) =
            instance ?: synchronized(this) {
                instance ?: UhidManager(ctx.applicationContext).also { instance = it }
            }

        // ── Standard HID report descriptor: keyboard (report 1) + relative
        //    mouse (report 2). Identical to what scrcpy --otg registers. ──
        private val HID_REPORT_DESCRIPTOR = byteArrayOf(
            // ── Keyboard (report ID 1) ────────────────────────────────────
            0x05.toByte(), 0x01.toByte(),  // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(),  // Usage (Keyboard)
            0xa1.toByte(), 0x01.toByte(),  // Collection (Application)
            0x85.toByte(), 0x01.toByte(),  //   Report ID (1)
            0x05.toByte(), 0x07.toByte(),  //   Usage Page (Keyboard)
            0x19.toByte(), 0xe0.toByte(),  //   Usage Minimum (Left Ctrl)
            0x29.toByte(), 0xe7.toByte(),  //   Usage Maximum (Right GUI)
            0x15.toByte(), 0x00.toByte(),  //   Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(),  //   Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(),  //   Report Size (1)
            0x95.toByte(), 0x08.toByte(),  //   Report Count (8)
            0x81.toByte(), 0x02.toByte(),  //   Input (Data, Variable, Absolute) — modifier byte
            0x95.toByte(), 0x01.toByte(),  //   Report Count (1)
            0x75.toByte(), 0x08.toByte(),  //   Report Size (8)
            0x81.toByte(), 0x01.toByte(),  //   Input (Constant) — reserved byte
            0x95.toByte(), 0x06.toByte(),  //   Report Count (6)
            0x75.toByte(), 0x08.toByte(),  //   Report Size (8)
            0x15.toByte(), 0x00.toByte(),  //   Logical Minimum (0)
            0x25.toByte(), 0x65.toByte(),  //   Logical Maximum (101)
            0x05.toByte(), 0x07.toByte(),  //   Usage Page (Keyboard)
            0x19.toByte(), 0x00.toByte(),  //   Usage Minimum (0)
            0x29.toByte(), 0x65.toByte(),  //   Usage Maximum (101)
            0x81.toByte(), 0x00.toByte(),  //   Input (Data, Array) — 6 keycodes
            0xc0.toByte(),                 // End Collection

            // ── Mouse (report ID 2) ───────────────────────────────────────
            0x05.toByte(), 0x01.toByte(),  // Usage Page (Generic Desktop)
            0x09.toByte(), 0x02.toByte(),  // Usage (Mouse)
            0xa1.toByte(), 0x01.toByte(),  // Collection (Application)
            0x85.toByte(), 0x02.toByte(),  //   Report ID (2)
            0x09.toByte(), 0x01.toByte(),  //   Usage (Pointer)
            0xa1.toByte(), 0x00.toByte(),  //   Collection (Physical)
            0x05.toByte(), 0x09.toByte(),  //     Usage Page (Buttons)
            0x19.toByte(), 0x01.toByte(),  //     Usage Minimum (1)
            0x29.toByte(), 0x03.toByte(),  //     Usage Maximum (3)
            0x15.toByte(), 0x00.toByte(),  //     Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(),  //     Logical Maximum (1)
            0x95.toByte(), 0x03.toByte(),  //     Report Count (3)
            0x75.toByte(), 0x01.toByte(),  //     Report Size (1)
            0x81.toByte(), 0x02.toByte(),  //     Input (Data, Variable, Absolute) — 3 buttons
            0x95.toByte(), 0x01.toByte(),  //     Report Count (1)
            0x75.toByte(), 0x05.toByte(),  //     Report Size (5)
            0x81.toByte(), 0x01.toByte(),  //     Input (Constant) — padding
            0x05.toByte(), 0x01.toByte(),  //     Usage Page (Generic Desktop)
            0x09.toByte(), 0x30.toByte(),  //     Usage (X)
            0x09.toByte(), 0x31.toByte(),  //     Usage (Y)
            0x09.toByte(), 0x38.toByte(),  //     Usage (Wheel)
            0x15.toByte(), 0x81.toByte(),  //     Logical Minimum (-127)
            0x25.toByte(), 0x7f.toByte(),  //     Logical Maximum (127)
            0x75.toByte(), 0x08.toByte(),  //     Report Size (8)
            0x95.toByte(), 0x03.toByte(),  //     Report Count (3)
            0x81.toByte(), 0x06.toByte(),  //     Input (Data, Variable, Relative) — X, Y, Wheel
            0xc0.toByte(),                 //   End Collection
            0xc0.toByte()                  // End Collection
        )
    }

    // ── State ─────────────────────────────────────────────────────────────
    private var uhidStream    : OutputStream? = null
    private var shizuku       : ShizukoManager? = null
    private var scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile var isRunning   = false
        private set

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Start UHID via Shizuku. Call from any thread; the heavy work goes to IO.
     * [onReady] is called on the main thread when the device is registered.
     * [onError] is called on the main thread if anything fails.
     */
    fun start(
        shizukuManager: ShizukoManager,
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isRunning) { onReady(); return }
        shizuku = shizukuManager
        scope.launch {
            try {
                openViaShizuku(shizukuManager)
                writeCreate2()
                isRunning = true
                withContext(Dispatchers.Main) { onReady() }
            } catch (e: Exception) {
                Log.e(TAG, "UHID start failed", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Start UHID via root (su). Alternative to Shizuku.
     */
    fun startAsRoot(
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isRunning) { onReady(); return }
        scope.launch {
            try {
                openViaRoot()
                writeCreate2()
                isRunning = true
                withContext(Dispatchers.Main) { onReady() }
            } catch (e: Exception) {
                Log.e(TAG, "UHID start (root) failed", e)
                withContext(Dispatchers.Main) { onError(e.message ?: "Unknown error") }
            }
        }
    }

    /** Send a keyboard report. modifier = bitmap of Ctrl/Shift/Alt/GUI bits. */
    fun sendKey(keycodeHid: Byte, modifier: Byte = 0, release: Boolean = false) {
        if (!isRunning) return
        scope.launch {
            val report = byteArrayOf(
                REPORT_ID_KEYBOARD,
                modifier,
                0,                                          // reserved
                if (release) 0 else keycodeHid, 0, 0, 0, 0, 0  // 6 keycodes
            )
            writeInput2(report)
            if (!release) {
                // Auto-release after 16 ms (one frame)
                delay(16)
                val released = byteArrayOf(REPORT_ID_KEYBOARD, 0, 0, 0, 0, 0, 0, 0, 0)
                writeInput2(released)
            }
        }
    }

    /**
     * Send a relative mouse report.
     * [dx]/[dy] = movement delta (-127..127).
     * [buttons] = button bitmap: bit0=left, bit1=right, bit2=middle.
     * [wheel]   = scroll delta (-127..127).
     */
    fun sendMouseMove(dx: Int, dy: Int, buttons: Byte = 0, wheel: Int = 0) {
        if (!isRunning) return
        scope.launch {
            val report = byteArrayOf(
                REPORT_ID_MOUSE,
                buttons,
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte()
            )
            writeInput2(report)
        }
    }

    /** Send a mouse button click (press + release). */
    fun sendMouseClick(button: Byte = 0x01) {
        if (!isRunning) return
        scope.launch {
            // Press
            writeInput2(byteArrayOf(REPORT_ID_MOUSE, button, 0, 0, 0))
            delay(16)
            // Release
            writeInput2(byteArrayOf(REPORT_ID_MOUSE, 0, 0, 0, 0))
        }
    }

    /** Stop UHID and release /dev/uhid. */
    fun stop() {
        if (!isRunning) return
        scope.launch {
            runCatching { writeDestroy() }
            runCatching { uhidStream?.close() }
            uhidStream = null
            isRunning  = false
        }
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Opens /dev/uhid for writing via Shizuku by launching a shell process
     * that cats stdin to /dev/uhid, giving us a writable OutputStream.
     */
    private fun openViaShizuku(shizukuManager: ShizukoManager) {
        // Shizuku lets us spawn a process as shell (uid 2000), which has
        // rw access to /dev/uhid on stock Android.
        val process = shizukuManager.newProcessPublic("sh -c 'cat > $UHID_PATH'")
        uhidStream  = process.outputStream
        Log.d(TAG, "UHID stream opened via Shizuku")
    }

    private fun openViaRoot() {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat > $UHID_PATH"))
        uhidStream  = process.outputStream
        Log.d(TAG, "UHID stream opened via root")
    }

    /**
     * Writes a UHID_CREATE2 event registering our composite HID device.
     * Packet layout (Linux uhid.h, struct uhid_create2_req):
     *   2 bytes  — event type (UHID_CREATE2 = 11, little-endian)
     *   128 bytes — name (null-padded UTF-8)
     *   64 bytes  — phys (physical location, can be empty)
     *   64 bytes  — uniq (unique ID, can be empty)
     *   2 bytes  — rd_size (report descriptor length, LE)
     *   1 byte   — bus (BUS_USB = 0x03)
     *   4 bytes  — vendor (little-endian)
     *   4 bytes  — product (little-endian)
     *   4 bytes  — version
     *   4 bytes  — country
     *   4096 bytes — rd_data (report descriptor, zero-padded)
     * Total: 4369 bytes
     */
    private fun writeCreate2() {
        val buf = ByteArray(4369)
        var pos = 0

        fun putShortLE(v: Short) {
            buf[pos++] = (v.toInt() and 0xFF).toByte()
            buf[pos++] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        fun putIntLE(v: Int) {
            buf[pos++] = (v and 0xFF).toByte()
            buf[pos++] = ((v shr 8) and 0xFF).toByte()
            buf[pos++] = ((v shr 16) and 0xFF).toByte()
            buf[pos++] = ((v shr 24) and 0xFF).toByte()
        }
        fun putString(s: String, len: Int) {
            val bytes = s.toByteArray(Charsets.UTF_8).take(len - 1).toByteArray()
            bytes.copyInto(buf, pos)
            pos += len
        }
        fun putBytes(src: ByteArray, len: Int) {
            src.copyInto(buf, pos, 0, minOf(src.size, len))
            pos += len
        }

        putShortLE(UHID_CREATE2)            // event type
        putString("PLate HID", 128)        // name
        putString("", 64)                   // phys
        putString("", 64)                   // uniq
        putShortLE(HID_REPORT_DESCRIPTOR.size.toShort()) // rd_size
        buf[pos++] = 0x03.toByte()          // bus = BUS_USB
        pos += 3                            // padding to align
        putIntLE(0x0000)                    // vendor  (generic)
        putIntLE(0x0000)                    // product (generic)
        putIntLE(0x0000)                    // version
        putIntLE(0x0000)                    // country
        putBytes(HID_REPORT_DESCRIPTOR, 4096) // rd_data (zero-padded)

        uhidStream?.write(buf)
        uhidStream?.flush()
        Log.d(TAG, "UHID_CREATE2 written (${HID_REPORT_DESCRIPTOR.size} bytes descriptor)")
    }

    /**
     * Writes a UHID_INPUT2 event carrying one HID report.
     * Packet layout (struct uhid_input2_req):
     *   2 bytes  — event type (UHID_INPUT2 = 12, LE)
     *   2 bytes  — size (report length, LE)
     *   4096 bytes — data (report, zero-padded)
     * Total: 4100 bytes
     */
    private fun writeInput2(report: ByteArray) {
        val buf = ByteArray(4100)
        buf[0] = (UHID_INPUT2.toInt() and 0xFF).toByte()
        buf[1] = ((UHID_INPUT2.toInt() shr 8) and 0xFF).toByte()
        buf[2] = (report.size and 0xFF).toByte()
        buf[3] = ((report.size shr 8) and 0xFF).toByte()
        report.copyInto(buf, 4)
        uhidStream?.write(buf)
        uhidStream?.flush()
    }

    /**
     * Writes a UHID_DESTROY event — tells the kernel to unregister the device.
     * Packet: just 2 bytes (event type = 1, LE) — no payload.
     */
    private fun writeDestroy() {
        val buf = byteArrayOf(
            (UHID_DESTROY.toInt() and 0xFF).toByte(),
            ((UHID_DESTROY.toInt() shr 8) and 0xFF).toByte()
        )
        uhidStream?.write(buf)
        uhidStream?.flush()
        Log.d(TAG, "UHID_DESTROY written")
    }
}

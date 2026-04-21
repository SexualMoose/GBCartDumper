package com.tyler.gbcartdumper.flasher

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Thin wrapper over usb-serial-for-android's FT232 driver that exposes
 * exactly what the flasher protocol needs: open/close, send bytes,
 * recv exactly N bytes (with a deadline), drain any pending input.
 */
class FtdiTransport private constructor(private val port: UsbSerialPort) : AutoCloseable {

    /** Apply 8N1 + flow-off + the chosen baud + a short latency timer (matches host libftdi path). */
    fun configure(baud: Int) {
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        // Do NOT assert DTR/RTS — on the rev.c flasher those lines are commonly wired
        // to the ATmega's RESET pin, so asserting them would hold the MCU in reset
        // and we'd never see any bytes on the wire.
        runCatching { port.setDTR(false) }
        runCatching { port.setRTS(false) }
        // Match the host tool: shrink the FT232 latency timer from its 16ms default
        // to 2ms so small (< 62-byte) responses aren't buffered by the chip.
        runCatching {
            // FtdiSerialPort is an inner class of FtdiSerialDriver — reach the
            // `setLatencyTimer(int)` method reflectively so we don't pull the
            // inner-class reference into a stable import.
            val m = port.javaClass.getMethod("setLatencyTimer", Int::class.javaPrimitiveType)
            m.invoke(port, 2)
        }
        runCatching { port.purgeHwBuffers(true, true) }
    }

    fun writeByte(b: Byte) = write(byteArrayOf(b))

    fun write(buf: ByteArray) {
        // usb-serial-for-android 3.x: write(byte[], int) returns Unit and throws on failure.
        port.write(buf, WRITE_TIMEOUT_MS)
    }

    /**
     * Block until exactly [n] bytes arrive (or [deadlineMs] elapses).
     *
     * Note: usb-serial-for-android's FT232 driver strips the 2-byte FTDI modem-status
     * prefix in-place, so the destination buffer MUST be larger than 2 bytes. We always
     * use an oversized staging buffer and copy what we need out of it.
     */
    suspend fun readExact(n: Int, deadlineMs: Long = READ_DEADLINE_MS): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArray(n)
        val tmp = ByteArray(STAGING_BUF)  // must be > 2 to satisfy FtdiSerialPort
        // Bytes we've already received but haven't returned yet (in case an inbound
        // USB packet straddles the requested boundary).
        var leftover = carry
        var carryLen = carryUsed
        var got = 0
        val end = System.currentTimeMillis() + deadlineMs
        while (got < n) {
            if (carryLen > 0) {
                val take = (n - got).coerceAtMost(carryLen)
                System.arraycopy(leftover, 0, out, got, take)
                got += take
                if (take < carryLen) {
                    System.arraycopy(leftover, take, leftover, 0, carryLen - take)
                }
                carryLen -= take
                continue
            }
            if (System.currentTimeMillis() > end) {
                carry = leftover; carryUsed = carryLen
                throw java.io.IOException("Timeout waiting for $n bytes (got $got)")
            }
            val r = port.read(tmp, SHORT_READ_TIMEOUT_MS)
            if (r > 0) {
                val take = (n - got).coerceAtMost(r)
                System.arraycopy(tmp, 0, out, got, take)
                got += take
                val extra = r - take
                if (extra > 0) {
                    if (extra > leftover.size) leftover = ByteArray(extra.coerceAtLeast(STAGING_BUF))
                    System.arraycopy(tmp, take, leftover, 0, extra)
                    carryLen = extra
                }
            } else if (r < 0) {
                carry = leftover; carryUsed = carryLen
                throw java.io.IOException("USB read error")
            }
            if (Thread.interrupted()) {
                carry = leftover; carryUsed = carryLen
                throw CancellationException()
            }
        }
        carry = leftover; carryUsed = carryLen
        out
    }

    /** Discard anything already in the OS-side FTDI buffer. Called before each transaction. */
    suspend fun flushInput() = withContext(Dispatchers.IO) {
        carryUsed = 0
        val tmp = ByteArray(STAGING_BUF)
        val end = System.currentTimeMillis() + 80
        while (System.currentTimeMillis() < end) {
            if (port.read(tmp, 20) <= 0) break
        }
    }

    // Carry-over buffer so an oversized read from the USB layer doesn't drop bytes
    // the caller didn't ask for this turn.
    private var carry: ByteArray = ByteArray(STAGING_BUF)
    private var carryUsed: Int = 0

    override fun close() {
        runCatching { port.close() }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 2000
        private const val SHORT_READ_TIMEOUT_MS = 200
        private const val STAGING_BUF = 256  // > 2 is required by FtdiSerialPort
        const val READ_DEADLINE_MS = 5000L

        /** True if [device] is an FTDI FT232R — the chip inside the rev.c flasher. */
        fun matches(device: UsbDevice): Boolean =
            device.vendorId == 0x0403 && device.productId == 0x6001

        /**
         * Open the first FT232R currently attached and granted permission. Caller must
         * have already obtained permission for the [UsbDevice] via [UsbManager.requestPermission].
         */
        fun open(context: Context, device: UsbDevice): FtdiTransport {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = FtdiSerialDriver(device)
            val connection = usbManager.openDevice(device)
                ?: throw java.io.IOException("UsbManager.openDevice returned null (permission?)")
            val port = driver.ports[0]
            port.open(connection)
            return FtdiTransport(port)
        }

        /** Find the first FTDI-class device currently attached. */
        fun findDevice(context: Context): UsbDevice? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val prober = UsbSerialProber.getDefaultProber()
            val driver = prober.findAllDrivers(usbManager)
                .firstOrNull { it.device.vendorId == 0x0403 && it.device.productId == 0x6001 }
            return driver?.device ?: usbManager.deviceList.values.firstOrNull { matches(it) }
        }
    }
}

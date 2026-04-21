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
        runCatching { port.setDTR(true) }
        runCatching { port.setRTS(true) }
    }

    fun writeByte(b: Byte) = write(byteArrayOf(b))

    fun write(buf: ByteArray) {
        // usb-serial-for-android 3.x: write(byte[], int) returns Unit and throws on failure.
        port.write(buf, WRITE_TIMEOUT_MS)
    }

    /** Block until exactly [n] bytes arrive or [deadlineMs] elapses. */
    suspend fun readExact(n: Int, deadlineMs: Long = READ_DEADLINE_MS): ByteArray = withContext(Dispatchers.IO) {
        val out = ByteArray(n)
        val tmp = ByteArray(n)
        var got = 0
        val end = System.currentTimeMillis() + deadlineMs
        while (got < n) {
            if (System.currentTimeMillis() > end) {
                throw java.io.IOException("Timeout waiting for $n bytes (got $got)")
            }
            val r = port.read(tmp, SHORT_READ_TIMEOUT_MS)
            if (r > 0) {
                System.arraycopy(tmp, 0, out, got, r.coerceAtMost(n - got))
                got += r
            } else if (r < 0) {
                throw java.io.IOException("USB read error")
            }
            if (Thread.interrupted()) throw CancellationException()
        }
        out
    }

    /** Discard anything already in the OS-side FTDI buffer. Called before each transaction. */
    suspend fun flushInput() = withContext(Dispatchers.IO) {
        val tmp = ByteArray(256)
        val end = System.currentTimeMillis() + 50
        while (System.currentTimeMillis() < end) {
            if (port.read(tmp, 20) <= 0) break
        }
    }

    override fun close() {
        runCatching { port.close() }
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 2000
        private const val SHORT_READ_TIMEOUT_MS = 200
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

package com.example.controller

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Owns everything USB-serial: discovery, open/close, async TX, bounded RX buffer.
 *
 * Implements [SerialInputOutputManager.Listener] internally (Bug #7: removed
 * the circular MainViewModel → UsbSerialManager → MainViewModel delegation).
 * Wire [onRunError] to be notified of port failures on the serial-IO thread.
 */
class UsbSerialManager(private val context: Context) : SerialInputOutputManager.Listener {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    // Inbound: raw byte chunks queued here, drained by JS receive() call.
    private val rxQueue = ConcurrentLinkedQueue<ByteArray>()
    private val rxCount = AtomicInteger(0)

    // Outbound: commands from the JS bridge are enqueued here and written by
    // a dedicated writer thread so a slow port.write() never blocks the caller.
    private val txQueue = LinkedBlockingQueue<ByteArray>(2048)
    @Volatile private var writerRunning = false
    private var writerThread: Thread? = null

    /**
     * True while a serial port is open and ready.
     * Bug #6 fixed: @Volatile ensures cross-thread visibility (JS-bridge thread
     * reads this, serial/IO thread writes it).
     */
    @Volatile var isConnected = false
        private set

    /**
     * Called on the serial-IO thread when the port errors or disconnects.
     */
    var onRunError: ((Exception) -> Unit)? = null

    /**
     * Called on the serial-IO thread whenever new data arrives.
     */
    var onDataReceived: ((ByteArray) -> Unit)? = null

    // ── Discovery ────────────────────────────────────────────────────────

    fun findDriver(): UsbSerialDriver? {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        Log.i(Config.USB_TAG, "Found ${drivers.size} USB serial driver(s)")
        return drivers.firstOrNull()
    }

    fun hasPermission(driver: UsbSerialDriver): Boolean =
        usbManager.hasPermission(driver.device)

    // ── Open / close ─────────────────────────────────────────────────────

    /**
     * Opens the serial port and starts the writer thread.
     * MUST be called from a background thread — performs blocking USB I/O.
     * Returns true on success.
     */
    fun openPort(driver: UsbSerialDriver): Boolean {
        val connection = usbManager.openDevice(driver.device) ?: run {
            Log.e(Config.USB_TAG, "openDevice returned null")
            return false
        }
        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(Config.BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
        } catch (e: Exception) {
            Log.e(Config.USB_TAG, "ERROR opening port: ${e.message}")
            try { port.close() } catch (_: Exception) {}
            return false
        }
        serialPort = port
        ioManager = SerialInputOutputManager(port, this).also { it.start() }
        startWriter(port)
        isConnected = true
        return true
    }

    /**
     * Closes the port and stops the writer thread. Safe to call from any thread.
     * Note: do NOT call this directly from within the [onRunError] callback —
     * that runs on the serial-IO thread and stopping the IO manager from its own
     * thread can deadlock. Dispatch to main or another thread first (see ViewModel).
     */
    fun closePort() {
        isConnected = false
        stopWriter()
        try { ioManager?.stop() } catch (_: Exception) {}
        try { serialPort?.close() } catch (_: Exception) {}
        ioManager = null
        serialPort = null
        rxQueue.clear()
        rxCount.set(0)
    }

    // ── Send / receive ───────────────────────────────────────────────────

    /**
     * Enqueues [command] + newline for async transmission.
     * Returns "ok:<bytes>" immediately or an error string. Never blocks.
     */
    fun sendCommand(command: String): String {
        if (!isConnected || serialPort == null) return "error:not_connected"
        val data = (command + "\n").toByteArray(Charsets.UTF_8)
        return if (txQueue.offer(data)) "ok:${data.size}" else "error:queue_full"
    }

    /** Drains all buffered inbound data and returns it as a UTF-8 string. */
    fun receiveData(): String {
        val bos = ByteArrayOutputStream()
        while (true) {
            val chunk = rxQueue.poll() ?: break
            rxCount.decrementAndGet()
            bos.write(chunk)
        }
        return bos.toString("UTF-8")
    }

    // ── SerialInputOutputManager.Listener ────────────────────────────────
    // Bug #7: UsbSerialManager now owns its own listener implementation.
    // The ViewModel no longer needs to implement this interface, eliminating
    // the circular MainViewModel → onNewData → UsbSerialManager.onNewData path.

    override fun onNewData(data: ByteArray) {
        rxQueue.add(data)
        // Drop the oldest chunk when buffer is full so a stalled UI can't cause OOM.
        if (rxCount.incrementAndGet() > Config.RX_MAX_CHUNKS) {
            if (rxQueue.poll() != null) rxCount.decrementAndGet()
        }
        // Notify any observers that new data has arrived
        onDataReceived?.invoke(data)
    }

    override fun onRunError(e: Exception) {
        Log.e(Config.USB_TAG, "Serial run error: ${e.message}")
        // Notify ViewModel via callback. The ViewModel MUST dispatch closePort()
        // to a non-serial-IO thread to avoid a potential deadlock.
        onRunError?.invoke(e)
    }

    // ── Writer thread ────────────────────────────────────────────────────

    private fun startWriter(port: UsbSerialPort) {
        txQueue.clear()
        writerRunning = true
        writerThread = thread(name = "serial-writer", isDaemon = true) {
            while (writerRunning) {
                val data = try {
                    txQueue.poll(Config.WRITER_THREAD_SLEEP_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    break
                } ?: continue
                try {
                    port.write(data, Config.WRITE_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.e(Config.USB_TAG, "Serial write failed: ${e.message}")
                }
            }
        }
    }

    private fun stopWriter() {
        writerRunning = false
        writerThread?.interrupt()
        writerThread = null
        txQueue.clear()
    }
}

package com.example.controller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class UsbSerialManager(
    private val context: Context,
    private val listener: SerialInputOutputManager.Listener
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    
    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    
    private val rxQueue = ConcurrentLinkedQueue<ByteArray>()
    private val rxCount = AtomicInteger(0)
    private val txQueue = LinkedBlockingQueue<ByteArray>(2048)

    @Volatile private var writerRunning = false
    private var writerThread: Thread? = null

    var isConnected = false
        private set

    fun findDriver(): UsbSerialDriver? {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        Log.i(Config.USB_TAG, "Found ${drivers.size} USB serial driver(s)")
        return drivers.firstOrNull()
    }

    fun hasPermission(driver: UsbSerialDriver): Boolean {
        return usbManager.hasPermission(driver.device)
    }

    fun requestPermission(driver: UsbSerialDriver) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val permIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(Config.ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(driver.device, permIntent)
    }

    fun openPort(driver: UsbSerialDriver): Boolean {
        val connection = usbManager.openDevice(driver.device) ?: return false
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
        ioManager = SerialInputOutputManager(port, listener).also { it.start() }
        startWriter(port)
        isConnected = true
        return true
    }

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

    private fun startWriter(port: UsbSerialPort) {
        txQueue.clear()
        writerRunning = true
        writerThread = thread(name = "serial-writer", isDaemon = true) {
            while (writerRunning) {
                val data = try {
                    txQueue.poll(Config.WRITER_THREAD_SLEEP_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
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

    fun sendCommand(command: String): String {
        if (!isConnected || serialPort == null) return "error:not_connected"
        val data = (command + "\n").toByteArray(Charsets.UTF_8)
        return if (txQueue.offer(data)) {
            "ok:${data.size}"
        } else {
            "error:queue_full"
        }
    }

    fun receiveData(): String {
        val bos = java.io.ByteArrayOutputStream()
        while (true) {
            val chunk = rxQueue.poll() ?: break
            rxCount.decrementAndGet()
            bos.write(chunk)
        }
        return bos.toString("UTF-8")
    }
    
    fun onNewData(data: ByteArray) {
        rxQueue.add(data)
        if (rxCount.incrementAndGet() > Config.RX_MAX_CHUNKS) {
            if (rxQueue.poll() != null) rxCount.decrementAndGet()
        }
    }
}

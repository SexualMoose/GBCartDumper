package com.tyler.gbcartdumper

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.tyler.gbcartdumper.flasher.FtdiTransport
import com.tyler.gbcartdumper.ui.AccentState
import com.tyler.gbcartdumper.ui.DumperScreen
import com.tyler.gbcartdumper.ui.DumperViewModel
import com.tyler.gbcartdumper.ui.GBCartDumperTheme
import androidx.compose.runtime.remember
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private val vm: DumperViewModel by viewModels()

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            vm.refreshDevicePresence()
            if (!granted) return
            // Re-run whatever triggered the permission ask. Scan is always safe to retry.
            vm.scanCart(::requestUsbPermission)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = vm.refreshDevicePresence()
            },
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        setContent {
            // Fresh random accent hue every time the Activity is created —
            // i.e. every cold open of the app.
            val accentState = remember { AccentState(initialHue = Random.nextFloat()) }
            GBCartDumperTheme(accentState = accentState) {
                DumperScreen(
                    viewModel = vm,
                    onNeedUsbPermission = ::requestUsbPermission,
                    onFolderPicked = ::persistAndSetFolder,
                )
            }
        }

        vm.refreshDevicePresence()
        // Cold-launched via USB attach intent? Device will be in the intent extras.
        handleAttachIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAttachIntent(intent)
    }

    private fun handleAttachIntent(intent: Intent?) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device != null && FtdiTransport.matches(device)) {
            vm.refreshDevicePresence()
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags
        )
        usb.requestPermission(device, pi)
    }

    private fun persistAndSetFolder(uri: android.net.Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        val label = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
        vm.setSaveFolder(uri, label)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.tyler.gbcartdumper.USB_PERMISSION"
    }
}

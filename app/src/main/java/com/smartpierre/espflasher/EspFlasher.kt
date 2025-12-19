
package com.smartpierre.espflasher

import android.content.Context
import android.content.res.Resources
import com.hoho.android.usbserial.driver.UsbSerialPort

class EspFlasher(
    private val port: UsbSerialPort,
    private val resources: Resources,
    private val context: Context,
    private val log: (String) -> Unit
) {
    private val usb = UsbEspFlasher(port, resources, context, log)

    suspend fun connectAndSync(tryAlternate: Boolean = false, skipBootloaderSequence: Boolean = false): Boolean {
        if (!skipBootloaderSequence) {
            if (!tryAlternate) {
                usb.enterBootloaderSequence()  // alap polaritás
                if (usb.syncRom()) return true
            }
            // alternatív polaritás: felcserélt RTS/DTR sorrend (néhány USB-serial-nál inverz a vonal)
            usb.enterBootloaderSequenceAlternate()
        }
        return usb.syncRom()
    }

    suspend fun flashAll(includeBootloader: Boolean = false): String {
        return usb.flashEmbeddedBins(includeBootloader)
    }
}

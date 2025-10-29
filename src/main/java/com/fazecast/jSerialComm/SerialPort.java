package com.fazecast.jSerialComm;

import android.hardware.usb.UsbDevice;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;

public class SerialPort {

    private final UsbSerialPort port;
    private int readTimeout = 2000;
    private int writeTimeout = 2000;

    private int currentBaudRate = 115200;
    private int currentDataBits = 8;
    private int currentStopBits = UsbSerialPort.STOPBITS_1;
    private int currentParity   = UsbSerialPort.PARITY_NONE;

    private static UsbSerialPort defaultPort;

    public static void setDefaultUsbSerialPort(UsbSerialPort p) { defaultPort = p; }

    public static SerialPort[] getCommPorts() {
        if (defaultPort == null) return new SerialPort[0];
        return new SerialPort[] { new SerialPort(defaultPort) };
    }

    public SerialPort(UsbSerialPort port) { this.port = port; }

    public boolean openPort() { return true; }

    public void closePort() { try { port.close(); } catch (Throwable ignored) {} }

    public void setComPortParameters(int baudRate, int dataBits, int stopBits, int parity) {
        try {
            this.currentBaudRate = baudRate;
            this.currentDataBits = dataBits;
            this.currentStopBits = stopBits;
            this.currentParity   = parity;
            port.setParameters(baudRate, dataBits, stopBits, parity);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public void setBaudRate(int baudRate) {
        try {
            this.currentBaudRate = baudRate;
            port.setParameters(baudRate, currentDataBits, currentStopBits, currentParity);
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public void setFlowControl(int mode) { /* no-op */ }

    public void setComPortTimeouts(int mode, int readTimeout, int writeTimeout) {
        this.readTimeout = readTimeout;
        this.writeTimeout = writeTimeout;
    }

    public int readBytes(byte[] dest, long bytesToRead) {
        try {
            int timeout = readTimeout > 0 ? readTimeout : 2000;
            int n = port.read(dest, timeout);
            return n < 0 ? 0 : n;
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public int writeBytes(byte[] src, long bytesToWrite) {
        try {
            int timeout = writeTimeout > 0 ? writeTimeout : 2000;
            int toWrite = (int) Math.min(bytesToWrite, (long) src.length);
            if (toWrite == src.length) {
                port.write(src, timeout);
            } else {
                byte[] tmp = new byte[toWrite];
                System.arraycopy(src, 0, tmp, 0, toWrite);
                port.write(tmp, timeout);
            }
            return toWrite;
        } catch (Throwable t) { throw new RuntimeException(t); }
    }

    public void setRTS() { try { port.setRTS(true); } catch (Throwable t) { throw new RuntimeException(t); } }
    public void clearRTS() { try { port.setRTS(false); } catch (Throwable t) { throw new RuntimeException(t); } }
    public void setDTR() { try { port.setDTR(true); } catch (Throwable t) { throw new RuntimeException(t); } }
    public void clearDTR() { try { port.setDTR(false); } catch (Throwable t) { throw new RuntimeException(t); } }

    public void setRTS(boolean v) { try { port.setRTS(v); } catch (Throwable t) { throw new RuntimeException(t); } }
    public void setDTR(boolean v) { try { port.setDTR(v); } catch (Throwable t) { throw new RuntimeException(t); } }

    public String getDescriptivePortName() {
        try {
            UsbSerialDriver drv = port.getDriver();
            if (drv != null) {
                UsbDevice dev = drv.getDevice();
                String vendor = String.format("%04X", dev.getVendorId());
                String product = String.format("%04X", dev.getProductId());
                return "USB " + vendor + ":" + product + " port " + port.getPortNumber();
            }
        } catch (Throwable ignored) {}
        return "USB-Serial Port";
    }

    public String getSystemPortName() { return getDescriptivePortName(); }

    public boolean flushIOBuffers() {
        try { port.purgeHwBuffers(true, true); return true; }
        catch (Throwable t) { return false; }
    }
}

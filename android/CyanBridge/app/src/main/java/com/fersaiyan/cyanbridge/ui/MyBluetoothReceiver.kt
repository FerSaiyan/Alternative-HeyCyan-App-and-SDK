package com.fersaiyan.cyanbridge.ui
import android.bluetooth.BluetoothDevice
import android.nfc.Tag
import android.os.UserManager
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.Constants
import com.oudmon.ble.base.communication.LargeDataHandler
import org.greenrobot.eventbus.EventBus

/**
 * @author hzy ,
 * @date  2021/1/15
 * <p>
 * "Programs should be written for other people to read,
 * and only incidentally for machines to execute"
 **/
class MyBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {
    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.e("connectStatue","---connectStatue")
        if(device !=null && connected){
            val deviceName = try {
                device.name
            } catch (_: SecurityException) {
                null
            }
            if (deviceName != null) {
                DeviceManager.getInstance().deviceName = deviceName
            }
        }else{
            EventBus.getDefault().post(BluetoothEvent(false))
        }
    }

    override fun onServiceDiscovered() {
        //do init
        LargeDataHandler.getInstance().initEnable()
        // Must receive a callback before other instructions can be issued
        // eg. set time, sync settings, etc.
        EventBus.getDefault().post(BluetoothEvent(true))
        Log.e("onServiceDiscovered","---onServiceDiscovered")
        BleOperateManager.getInstance().isReady=true
    }

    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        if (data != null) {
            // Feed all notifications into BleIpBridge so it can try to
            // detect any IP information broadcast over BLE.
            bleIpBridge.onCharacteristicChanged("notify:$uuid", data)
        }
    }

    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid != null && data != null) {
            val version = String(data, Charsets.UTF_8)
            when(uuid){
                Constants.CHAR_FIRMWARE_REVISION.toString() -> {
                    Log.e("rom----", version)
                    // rom version
                    MyApplication.getInstance().firmwareVersion = version
                }
                Constants.CHAR_HW_REVISION.toString() -> {
                    // hardware version
                    Log.e("hardware----", version)
                    MyApplication.getInstance().hardwareVersion = version
                }
                else -> {
                    // Also send any other characteristic reads through BleIpBridge
                    bleIpBridge.onCharacteristicChanged("read:$uuid", data)
                }
            }
        }
    }


}

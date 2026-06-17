package com.glasssutdio.wear.wifi.wifiConnect;

import android.net.wifi.ScanResult;
import java.util.List;

public interface ConnectionScanResultsListener {
    ScanResult onConnectWithScanResult(List<ScanResult> scanResults);
}

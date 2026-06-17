package com.glasssutdio.wear.wifi.wifiScan;

import android.net.wifi.ScanResult;
import java.util.List;

public interface ScanResultsListener {
    void onScanResults(List<ScanResult> scanResults);
}

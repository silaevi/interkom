// File: app/src/main/java/com/example/motointercom/WiFiDirectBroadcastReceiver.java
package com.example.motointercom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.util.List;

public class WiFiDirectBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "WiFiDirectReceiver";
    private final WifiP2pManager manager;
    private final WifiP2pManager.Channel channel;
    private final WeakReference<MainActivity> activityRef;

    public WiFiDirectBroadcastReceiver(WifiP2pManager manager, WifiP2pManager.Channel channel, WeakReference<MainActivity> activityRef) {
        this.manager = manager;
        this.channel = channel;
        this.activityRef = activityRef;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        MainActivity activity = activityRef.get();
        if (activity == null) return;

        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                activity.runOnUiThread(() -> activity.statusText.setText(activity.getString(R.string.status_wifi_off)));
            }
        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            if (manager != null) {
                manager.requestPeers(channel, peers -> activity.updatePeers(peers.getDeviceList()));
            }
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo.class);
            if (networkInfo != && networkInfo.isConnected()) {
                manager.requestConnectionInfo(channel, info -> {
                    if (info.groupFormed && info.isGroupOwner) {
                        activity.runOnUiThread(() -> activity.statusText.setText(activity.getString(R.string.status_server)));
                    } else if (info.groupFormed) {
                        activity.onConnectionEstablished(info.groupOwnerAddress);
                    }
                });
            }
        }
    }
}

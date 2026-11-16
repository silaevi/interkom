package com.example.autointercom;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;

import java.net.InetAddress;

public class WiFiDirectBroadcastReceiver extends BroadcastReceiver {

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private MainActivity activity;

    public WiFiDirectBroadcastReceiver(WifiP2pManager manager, WifiP2pManager.Channel channel, MainActivity activity) {
        this.manager = manager;
        this.channel = channel;
        this.activity = activity;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
            int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
            if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                activity.statusText.setText("Wi-Fi Direct âûêëþ÷åí");
            }
        } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
            if (manager != null) {
                manager.requestPeers(channel, peers -> activity.updatePeers(peers.getDeviceList()));
            }
        } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
            NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
            if (networkInfo.isConnected()) {
                manager.requestConnectionInfo(channel, info -> {
                    WifiP2pDevice device = info.groupOwnerInfo;
                    if (info.groupFormed && !device.isGroupOwner) {
                        // Ìû êëèåíò, ïîäêëþ÷àåìñÿ ê ñåðâåðó
                        activity.onConnectionEstablished(info.groupOwnerAddress);
                    } else if (info.groupFormed && device.isGroupOwner) {
                        // Ìû ñåðâåð, ïðîñòî æäåì âõîäÿùèõ äàííûõ
                        activity.statusText.setText("Ðåæèì: Ñåðâåð\nÎæèäàíèå êëèåíòà...");
                    }
                });
            }
        }
    }
}

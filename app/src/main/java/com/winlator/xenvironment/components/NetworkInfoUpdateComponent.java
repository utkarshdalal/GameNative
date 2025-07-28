package com.winlator.xenvironment.components;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.core.NetworkHelper;
import com.winlator.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class NetworkInfoUpdateComponent extends EnvironmentComponent {
    private BroadcastReceiver broadcastReceiver;

    @Override
    public void start() {
        Log.d("NetworkInfoUpdateComponent", "Starting...");
        Context context = environment.getContext();
        final NetworkHelper networkHelper = new NetworkHelper(context);
        updateIFAddrsFile(networkHelper.getIFAddresses());
        updateEtcHostsFile(networkHelper.getIPv4Address());
        this.broadcastReceiver = new BroadcastReceiver() { // from class: com.winlator.xenvironment.components.NetworkInfoUpdateComponent.1
            @Override // android.content.BroadcastReceiver
            public void onReceive(Context context2, Intent intent) {
                NetworkInfoUpdateComponent.this.updateIFAddrsFile(networkHelper.getIFAddresses());
                NetworkInfoUpdateComponent.this.updateEtcHostsFile(networkHelper.getIPv4Address());
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(this.broadcastReceiver, filter);
    }

    @Override
    public void stop() {
        Log.d("NetworkInfoUpdateComponent", "Stopping...");
        if (broadcastReceiver != null) {
            try {
                environment.getContext().unregisterReceiver(broadcastReceiver);
            } catch(Exception e) {
                Log.e("NetworkInfoUpdateComponent", "Failed to unregister broadcast receiver: " + e);
            }
            broadcastReceiver = null;
        }
    }

    private void updateAdapterInfoFile(int ipAddress, int netmask, int gateway) {
        File file = new File(environment.getImageFs().getTmpDir(), "adapterinfo");
        FileUtils.writeString(file, "Android Wi-Fi Adapter,"+NetworkHelper.formatIpAddress(ipAddress)+","+NetworkHelper.formatNetmask(netmask)+","+NetworkHelper.formatIpAddress(gateway));
    }

    public void updateIFAddrsFile(List<NetworkHelper.IFAddress> ifAddresses) {
        File file = new File(environment.getImageFs().getTmpDir(), "ifaddrs");
        String content = "";
        if (!ifAddresses.isEmpty()) {
            for (NetworkHelper.IFAddress ifAddress : ifAddresses) {
                StringBuilder sb = new StringBuilder();
                sb.append(content);
                sb.append(!content.isEmpty() ? "\n" : "");
                sb.append(ifAddress.toString());
                content = sb.toString();
            }
        } else {
            content = new NetworkHelper.IFAddress().toString();
        }
        FileUtils.writeString(file, content);
    }

    public void updateEtcHostsFile(String ipAddress) {
        String ip = ipAddress != null ? ipAddress : "127.0.0.1";
        File file = new File(environment.getImageFs().getRootDir(), "etc/hosts");
        FileUtils.writeString(file, ip + "\tlocalhost\n");
    }
}

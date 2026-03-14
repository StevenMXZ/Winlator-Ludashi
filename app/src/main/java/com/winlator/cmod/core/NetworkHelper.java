package com.winlator.cmod.core;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;

/* loaded from: classes10.dex */
public class NetworkHelper {
    private final WifiManager wifiManager;

    public NetworkHelper(Context context) {
        this.wifiManager = (WifiManager) context.getSystemService("wifi");
    }

    public int getIpAddress() {
        if (this.wifiManager != null) {
            return this.wifiManager.getConnectionInfo().getIpAddress();
        }
        return 0;
    }

    public int getNetmask() {
        DhcpInfo dhcpInfo;
        if (this.wifiManager == null || (dhcpInfo = this.wifiManager.getDhcpInfo()) == null) {
            return 0;
        }
        int netmask = Integer.bitCount(dhcpInfo.netmask);
        if (dhcpInfo.netmask < 8 || dhcpInfo.netmask > 32) {
            try {
                InetAddress inetAddress = InetAddress.getByName(formatIpAddress(getIpAddress()));
                NetworkInterface networkInterface = NetworkInterface.getByInetAddress(inetAddress);
                if (networkInterface != null) {
                    for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                        if (inetAddress != null && inetAddress.equals(address.getAddress())) {
                            return address.getNetworkPrefixLength();
                        }
                    }
                    return netmask;
                }
                return netmask;
            } catch (SocketException e) {
                return netmask;
            } catch (UnknownHostException e2) {
                return netmask;
            }
        }
        return netmask;
    }

    public int getGateway() {
        DhcpInfo dhcpInfo;
        if (this.wifiManager == null || (dhcpInfo = this.wifiManager.getDhcpInfo()) == null) {
            return 0;
        }
        return dhcpInfo.gateway;
    }

    public static String formatIpAddress(int ipAddress) {
        return (ipAddress & 255) + "." + ((ipAddress >> 8) & 255) + "." + ((ipAddress >> 16) & 255) + "." + ((ipAddress >> 24) & 255);
    }

    public static String formatNetmask(int netmask) {
        return netmask == 24 ? "255.255.255.0" : netmask == 16 ? "255.255.0.0" : netmask == 8 ? "255.0.0.0" : "0.0.0.0";
    }
}

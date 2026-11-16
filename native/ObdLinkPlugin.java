package com.imal.drivora;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Raw serial link to an ELM327 OBD2 adapter over Bluetooth (SPP) or Wi-Fi (TCP).
 * The ELM327 / OBD protocol itself lives in the web layer (index.html), so it can be
 * tested without a car. This class only moves bytes.
 *
 * JS: Capacitor.Plugins.ObdLink
 *   listPaired()              -> { devices: [{ name, address }] }
 *   connect({ address })      -> Bluetooth
 *   connectTcp({ host, port }) -> Wi-Fi
 *   write({ data })
 *   disconnect()
 *   events: "data" { data }, "state" { connected }
 */
@CapacitorPlugin(
    name = "ObdLink",
    permissions = { @Permission(alias = "bt", strings = { "android.permission.BLUETOOTH_CONNECT" }) }
)
public class ObdLinkPlugin extends Plugin {

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Object lock = new Object();
    private BluetoothSocket btSocket;
    private Socket tcpSocket;
    private OutputStream out;
    private int session = 0;

    private boolean needsBtPermission() {
        return Build.VERSION.SDK_INT >= 31 && getPermissionState("bt") != PermissionState.GRANTED;
    }

    @PluginMethod
    public void listPaired(PluginCall call) {
        if (needsBtPermission()) {
            requestPermissionForAlias("bt", call, "onPermList");
            return;
        }
        doListPaired(call);
    }

    @PermissionCallback
    private void onPermList(PluginCall call) {
        if (needsBtPermission()) {
            call.reject("Bluetooth permission was not allowed.");
            return;
        }
        doListPaired(call);
    }

    private void doListPaired(PluginCall call) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                call.reject("This device has no Bluetooth.");
                return;
            }
            if (!adapter.isEnabled()) {
                call.reject("Bluetooth is turned off. Turn it on and try again.");
                return;
            }
            JSArray list = new JSArray();
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    JSObject o = new JSObject();
                    String name = d.getName();
                    o.put("name", name == null ? "" : name);
                    o.put("address", d.getAddress());
                    list.put(o);
                }
            }
            JSObject ret = new JSObject();
            ret.put("devices", list);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Could not read Bluetooth devices: " + e.getMessage());
        }
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (needsBtPermission()) {
            requestPermissionForAlias("bt", call, "onPermConnect");
            return;
        }
        doConnect(call);
    }

    @PermissionCallback
    private void onPermConnect(PluginCall call) {
        if (needsBtPermission()) {
            call.reject("Bluetooth permission was not allowed.");
            return;
        }
        doConnect(call);
    }

    private void doConnect(final PluginCall call) {
        final String address = call.getString("address");
        if (address == null || address.length() == 0) {
            call.reject("No adapter address given.");
            return;
        }
        closeAll();
        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    BluetoothSocket s = null;
                    try {
                        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                        if (adapter == null || !adapter.isEnabled()) {
                            call.reject("Bluetooth is turned off. Turn it on and try again.");
                            return;
                        }
                        BluetoothDevice dev = adapter.getRemoteDevice(address);
                        try {
                            s = dev.createRfcommSocketToServiceRecord(SPP);
                            s.connect();
                        } catch (IOException first) {
                            closeQuietly(s);
                            /* many cheap ELM327 clones only accept an insecure link */
                            s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                            s.connect();
                        }
                        InputStream in = s.getInputStream();
                        OutputStream o = s.getOutputStream();
                        synchronized (lock) {
                            btSocket = s;
                            out = o;
                            session++;
                            startReader(in, session);
                        }
                        call.resolve();
                    } catch (Exception e) {
                        closeQuietly(s);
                        call.reject("Could not connect to the adapter. Is it plugged in and the ignition on?");
                    }
                }
            }
        )
            .start();
    }

    @PluginMethod
    public void connectTcp(final PluginCall call) {
        final String host = call.getString("host", "192.168.0.10");
        final int port = call.getInt("port", 35000);
        closeAll();
        new Thread(
            new Runnable() {
                @Override
                public void run() {
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(host, port), 6000);
                        s.setTcpNoDelay(true);
                        InputStream in = s.getInputStream();
                        OutputStream o = s.getOutputStream();
                        synchronized (lock) {
                            tcpSocket = s;
                            out = o;
                            session++;
                            startReader(in, session);
                        }
                        call.resolve();
                    } catch (Exception e) {
                        try {
                            s.close();
                        } catch (Exception ignored) {}
                        call.reject("Could not reach the Wi-Fi adapter. Join its Wi-Fi network first.");
                    }
                }
            }
        )
            .start();
    }

    /* call with lock held */
    private void startReader(final InputStream in, final int mySession) {
        Thread t = new Thread(
            new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[512];
                    try {
                        while (true) {
                            int n = in.read(buf);
                            if (n < 0) break;
                            if (n > 0) {
                                JSObject o = new JSObject();
                                o.put("data", new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                                notifyListeners("data", o);
                            }
                        }
                    } catch (Exception ignored) {}
                    boolean dropped;
                    synchronized (lock) {
                        dropped = (mySession == session);
                    }
                    if (dropped) {
                        closeAll();
                        JSObject st = new JSObject();
                        st.put("connected", false);
                        notifyListeners("state", st);
                    }
                }
            }
        );
        t.setDaemon(true);
        t.start();
    }

    @PluginMethod
    public void write(PluginCall call) {
        String data = call.getString("data");
        OutputStream o;
        synchronized (lock) {
            o = out;
        }
        if (o == null) {
            call.reject("Not connected.");
            return;
        }
        if (data == null) data = "";
        try {
            o.write(data.getBytes(StandardCharsets.ISO_8859_1));
            o.flush();
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not send to the adapter.");
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        closeAll();
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        closeAll();
    }

    private void closeAll() {
        BluetoothSocket b;
        Socket t;
        synchronized (lock) {
            session++;
            b = btSocket;
            t = tcpSocket;
            btSocket = null;
            tcpSocket = null;
            out = null;
        }
        closeQuietly(b);
        if (t != null) {
            try {
                t.close();
            } catch (Exception ignored) {}
        }
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {}
        }
    }
}

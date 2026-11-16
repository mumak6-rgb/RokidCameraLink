package sk.bukero.rokidcamera.phone;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.*;
import java.net.*;
import java.util.Enumeration;

public class MainActivity extends Activity {
    private TextView ipView, status;
    private ImageView preview;
    private Button saveButton;
    private volatile byte[] latestFrame;
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private volatile boolean beaconRunning = true;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        ipView = findViewById(R.id.ip);
        status = findViewById(R.id.status);
        preview = findViewById(R.id.preview);
        saveButton = findViewById(R.id.saveButton);

        String ip = findIp();
        ipView.setText(ip == null ? "IP mobilu sa nenašla" : "IP MOBILU: " + ip + "   PORT: 9090");

        saveButton.setOnClickListener(v -> saveCurrentFrame());
        startServer();
        startBeacon();
    }

    private void startServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("0.0.0.0", 9090));
                while (running) {
                    runOnUiThread(() -> status.setText("Čakám na Rokid okuliare…"));
                    Socket socket = serverSocket.accept();
                    runOnUiThread(() -> status.setText("PRIPOJENÉ • prijímam živý obraz"));
                    DataInputStream in = new DataInputStream(
                            new BufferedInputStream(socket.getInputStream()));

                    try {
                        while (running && !socket.isClosed()) {
                            int len = in.readInt();
                            if (len <= 0 || len > 5_000_000) throw new IOException("Neplatný rámec");
                            byte[] frame = new byte[len];
                            in.readFully(frame);
                            latestFrame = frame;

                            Bitmap bmp = BitmapFactory.decodeByteArray(frame, 0, frame.length);
                            if (bmp != null) {
                                runOnUiThread(() -> preview.setImageBitmap(bmp));
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try { socket.close(); } catch (Exception ignored) {}
                        runOnUiThread(() -> status.setText("Okuliare odpojené. Čakám…"));
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Server zlyhal: " + e.getMessage()));
            }
        }, "PhoneServer").start();
    }

    private void startBeacon() {
        new Thread(() -> {
            DatagramSocket ds = null;
            try {
                ds = new DatagramSocket();
                ds.setBroadcast(true);
                byte[] data = "ROKID_PHONE:9090".getBytes("UTF-8");
                while (beaconRunning) {
                    DatagramPacket p = new DatagramPacket(
                            data, data.length,
                            InetAddress.getByName("255.255.255.255"), 9091);
                    ds.send(p);
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                }
            } catch (Exception ignored) {
            } finally {
                if (ds != null) ds.close();
            }
        }, "PhoneBeacon").start();
    }

    private void saveCurrentFrame() {
        byte[] frame = latestFrame;
        if (frame == null) {
            status.setText("Ešte nemám žiadny obraz.");
            return;
        }

        try {
            String name = "Rokid_" + System.currentTimeMillis() + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RokidCameraLink");

            android.net.Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (uri == null) throw new IOException("MediaStore insert failed");

            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                out.write(frame);
            }

            status.setText("Fotka uložená do Pictures/RokidCameraLink");
        } catch (Exception e) {
            status.setText("Uloženie zlyhalo: " + e.getMessage());
        }
    }

    private String findIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface nic = ifaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;

                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override protected void onDestroy() {
        running = false;
        beaconRunning = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}

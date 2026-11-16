package sk.bukero.rokidcamera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Size;
import android.widget.TextView;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 10;
    private TextView status, address;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile byte[] latestJpeg;
    private HttpServer httpServer;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        address = findViewById(R.id.address);
        String ip = findIp();
        address.setText(ip == null ? "Wi‑Fi IP sa nenašla" : "http://" + ip + ":8080");

        cameraThread = new HandlerThread("RokidCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        httpServer = new HttpServer();
        httpServer.start();

        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            openCamera();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            status.setText("Povoľ prístup ku kamere.");
        }
    }

    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String chosen = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { chosen = id; break; }
                if (chosen == null) chosen = id;
            }
            if (chosen == null) { status.setText("YodaOS nesprístupnil kameru aplikácii."); return; }

            CameraCharacteristics cc = manager.getCameraCharacteristics(chosen);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = new Size(1280, 720);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                if (sizes != null && sizes.length > 0) size = choosePreviewSize(sizes);
            }

            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 3);
            reader.setOnImageAvailableListener(r -> {
                Image image = null;
                try {
                    image = r.acquireLatestImage();
                    if (image == null) return;
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    latestJpeg = data;
                } catch (Exception ignored) {
                } finally {
                    if (image != null) image.close();
                }
            }, cameraHandler);

            manager.openCamera(chosen, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    status.setText("Kamera odpojená.");
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    status.setText("Chyba kamery: " + error);
                }
            }, cameraHandler);
        } catch (Exception e) {
            status.setText("Kamera sa nedá otvoriť: " + e.getMessage());
        }
    }

    private Size choosePreviewSize(Size[] sizes) {
        Size best = sizes[0];
        long target = 1280L * 720L;
        long bestDiff = Math.abs((long)best.getWidth() * best.getHeight() - target);
        for (Size s : sizes) {
            long area = (long)s.getWidth() * s.getHeight();
            long d = Math.abs(area - target);
            if (d < bestDiff) { best = s; bestDiff = d; }
        }
        return best;
    }

    private void startPreview() {
        try {
            cameraDevice.createCaptureSession(
                    Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(reader.getSurface());
                                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                b.set(CaptureRequest.JPEG_QUALITY, (byte)85);
                                session.setRepeatingRequest(b.build(), null, cameraHandler);
                                runOnUiThread(() -> status.setText("Kamera beží • živý náhľad pripravený"));
                            } catch (Exception e) {
                                runOnUiThread(() -> status.setText("Preview zlyhal: " + e.getMessage()));
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            runOnUiThread(() -> status.setText("YodaOS odmietol kamerový stream."));
                        }
                    },
                    cameraHandler
            );
        } catch (Exception e) {
            status.setText("Stream sa nedá spustiť: " + e.getMessage());
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

    private class HttpServer {
        private volatile boolean running;
        private ServerSocket server;

        void start() {
            running = true;
            new Thread(() -> {
                try {
                    server = new ServerSocket(8080);
                    while (running) {
                        Socket s = server.accept();
                        new Thread(() -> handle(s), "RokidHttpClient").start();
                    }
                } catch (Exception ignored) {}
            }, "RokidHttpServer").start();
        }

        void handle(Socket socket) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
                String line = in.readLine();
                if (line == null) return;
                String path = "/";
                String[] parts = line.split(" ");
                if (parts.length >= 2) path = parts[1];
                while ((line = in.readLine()) != null && !line.isEmpty()) {}

                if (path.startsWith("/frame")) {
                    byte[] jpg = latestJpeg;
                    if (jpg == null) {
                        byte[] msg = "Camera starting".getBytes("UTF-8");
                        write(out, "503 Service Unavailable", "text/plain", msg, null);
                    } else {
                        write(out, "200 OK", "image/jpeg", jpg, "Cache-Control: no-store\r\n");
                    }
                } else if (path.startsWith("/capture")) {
                    byte[] jpg = latestJpeg;
                    if (jpg == null) {
                        byte[] msg = "No frame yet".getBytes("UTF-8");
                        write(out, "503 Service Unavailable", "text/plain", msg, null);
                    } else {
                        write(out, "200 OK", "image/jpeg", jpg,
                                "Content-Disposition: attachment; filename=RokidPhoto.jpg\r\nCache-Control: no-store\r\n");
                    }
                } else {
                    String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                            + "<title>Rokid Camera Link</title><style>body{background:#090909;color:white;font-family:sans-serif;text-align:center;margin:0;padding:16px}"
                            + "h2{color:#00e676}img{width:100%;max-width:900px;border-radius:16px;background:#222}"
                            + "button{font-size:20px;padding:14px 22px;margin:12px;border:0;border-radius:12px;background:#00e676;color:#000;font-weight:bold}</style></head>"
                            + "<body><h2>Rokid Camera Link</h2><img id='cam' src='/frame'><br>"
                            + "<button onclick=\"location.href='/capture'\">📷 Uložiť fotku</button>"
                            + "<script>setInterval(()=>{document.getElementById('cam').src='/frame?t='+Date.now()},250);</script></body></html>";
                    write(out, "200 OK", "text/html; charset=utf-8", html.getBytes("UTF-8"), "Cache-Control: no-store\r\n");
                }
            } catch (Exception ignored) {
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        void write(OutputStream out, String code, String type, byte[] body, String extra) throws IOException {
            String h = "HTTP/1.1 " + code + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n" + (extra == null ? "" : extra) + "\r\n";
            out.write(h.getBytes("UTF-8"));
            out.write(body);
            out.flush();
        }

        void stop() {
            running = false;
            try { if (server != null) server.close(); } catch (Exception ignored) {}
        }
    }

    @Override protected void onDestroy() {
        if (httpServer != null) httpServer.stop();
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }
}

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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 10;
    private TextView status;
    private EditText phoneIp;
    private Button connectButton;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile byte[] latestJpeg;
    private volatile boolean streaming = false;
    private Socket socket;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        phoneIp = findViewById(R.id.phoneIp);
        connectButton = findViewById(R.id.connectButton);

        cameraThread = new HandlerThread("RokidCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        connectButton.setOnClickListener(v -> startStreaming());

        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            openCamera();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            status.setText("Povoľ aplikácii kameru.");
        }
    }

    private void openCamera() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            String chosen = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosen = id;
                    break;
                }
                if (chosen == null) chosen = id;
            }

            if (chosen == null) {
                status.setText("YodaOS nesprístupnil kameru.");
                return;
            }

            Size size = new Size(640, 480);
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
                    runOnUiThread(() -> status.setText("Kamera odpojená."));
                }

                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    runOnUiThread(() -> status.setText("Chyba kamery: " + error));
                }
            }, cameraHandler);
        } catch (Exception e) {
            status.setText("Kamera sa nedá otvoriť: " + e.getMessage());
        }
    }

    private void startPreview() {
        try {
            cameraDevice.createCaptureSession(
                    Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(reader.getSurface());
                                b.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                b.set(CaptureRequest.JPEG_QUALITY, (byte)75);
                                session.setRepeatingRequest(b.build(), null, cameraHandler);
                                runOnUiThread(() -> status.setText("Kamera pripravená. Zadaj IP mobilu."));
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

    private void startStreaming() {
        String ip = phoneIp.getText().toString().trim();
        if (ip.isEmpty()) {
            status.setText("Zadaj IP adresu S24 Ultra.");
            return;
        }

        if (streaming) {
            status.setText("Stream už beží.");
            return;
        }

        status.setText("Pripájam sa k " + ip + ":9090…");

        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new java.net.InetSocketAddress(ip, 9090), 5000);
                socket = s;
                streaming = true;
                socket.setTcpNoDelay(true);
                DataOutputStream out = new DataOutputStream(
                        new BufferedOutputStream(socket.getOutputStream()));

                runOnUiThread(() -> status.setText("PRIPOJENÉ • obraz sa posiela do mobilu"));

                while (streaming && !socket.isClosed()) {
                    byte[] frame = latestJpeg;
                    if (frame != null && frame.length > 0) {
                        out.writeInt(frame.length);
                        out.write(frame);
                        out.flush();
                    }
                    try { Thread.sleep(180); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                runOnUiThread(() -> status.setText("NEPRIPOJENÉ • " + msg));
            } finally {
                streaming = false;
                try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            }
        }, "RokidStream").start();
    }

    @Override protected void onDestroy() {
        streaming = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (cameraDevice != null) cameraDevice.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        if (cameraThread != null) cameraThread.quitSafely();
        super.onDestroy();
    }
}

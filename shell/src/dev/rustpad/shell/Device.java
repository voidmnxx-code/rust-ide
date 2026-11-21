package dev.rustpad.shell;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * أوامر الجهاز التي يطلبها Rust (cmds في JSON). النتائج تعود كأحداث:
 * location "lat,lon,acc" | photo "path" | picked "path" | sensor "kind:x,y,z" |
 * network "wifi|mobile|none" | permission "NAME:granted|denied" | clipboard "text" | error "msg".
 */
final class Device {
    interface Sink { void send(String id, String value); }

    private static final int RC_PERM = 9100, RC_PHOTO = 9101, RC_PICK = 9102;

    private final Activity act;
    private final Sink sink;
    private String pendingPerm, pendingCmd, pendingArg;
    private Uri photoUri;
    private final Map<String, SensorEventListener> sensors = new HashMap<>();
    private final Map<String, Long> last = new HashMap<>();
    private boolean torch = false;

    Device(Activity act, Sink sink) { this.act = act; this.sink = sink; }

    void handle(String cmd, String arg) {
        try {
            switch (cmd) {
                case "vibrate": {
                    Vibrator v = (Vibrator) act.getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null) v.vibrate(parse(arg, 200));
                    break;
                }
                case "copy": {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("text", arg));
                    break;
                }
                case "paste": {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    CharSequence t = "";
                    if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0)
                        t = cm.getPrimaryClip().getItemAt(0).coerceToText(act);
                    sink.send("clipboard", t.toString());
                    break;
                }
                case "share": {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, arg);
                    act.startActivity(Intent.createChooser(i, null));
                    break;
                }
                case "open_url":
                    act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(arg)));
                    break;
                case "network": sink.send("network", network()); break;
                case "permission": askPermission(arg, null, null); break;
                case "notify": notifyUser(arg); break;
                case "location": needs(Manifest.permission.ACCESS_FINE_LOCATION, cmd, arg); break;
                case "camera": needs(Manifest.permission.CAMERA, cmd, arg); break;
                case "flashlight": needs(Manifest.permission.CAMERA, cmd, arg); break;
                case "pick_image":
                    act.startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), RC_PICK);
                    break;
                case "sensor": startSensor(arg); break;
                case "sensor_stop": stopSensor(arg); break;
                default: sink.send("error", "unknown command: " + cmd);
            }
        } catch (Throwable t) {
            sink.send("error", cmd + ": " + t);
        }
    }

    private static int parse(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; } }

    private String network() {
        ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
        if (ni == null || !ni.isConnected()) return "none";
        return ni.getType() == ConnectivityManager.TYPE_WIFI ? "wifi" : "mobile";
    }

    // ---------- الأذونات ----------
    private boolean granted(String perm) {
        return Build.VERSION.SDK_INT < 23 || act.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void needs(String perm, String cmd, String arg) {
        if (granted(perm)) run(cmd, arg); else askPermission(perm, cmd, arg);
    }

    private void askPermission(String name, String cmd, String arg) {
        String perm = name.indexOf('.') >= 0 ? name : "android.permission." + name;
        if (granted(perm)) {
            if (cmd == null) sink.send("permission", name + ":granted"); else run(cmd, arg);
            return;
        }
        pendingPerm = perm; pendingCmd = cmd; pendingArg = arg;
        act.requestPermissions(new String[]{perm}, RC_PERM);
    }

    void onPermission(int code, String[] perms, int[] res) {
        if (code != RC_PERM || pendingPerm == null) return;
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        String p = pendingPerm, c = pendingCmd, a = pendingArg;
        pendingPerm = null; pendingCmd = null; pendingArg = null;
        sink.send("permission", p.replace("android.permission.", "") + (ok ? ":granted" : ":denied"));
        if (ok && c != null) run(c, a);
    }

    private void run(String cmd, String arg) {
        try {
            if (cmd.equals("location")) location();
            else if (cmd.equals("camera")) camera();
            else if (cmd.equals("flashlight")) flashlight("on".equals(arg) || "true".equals(arg));
        } catch (Throwable t) { sink.send("error", cmd + ": " + t); }
    }

    // ---------- الموقع ----------
    private void location() {
        final LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        for (String p : lm.getProviders(true)) {
            Location l = lm.getLastKnownLocation(p);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
        }
        if (best != null) sink.send("location", fmt(best));
        String prov = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
                : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
        if (prov == null) { if (best == null) sink.send("error", "location: no provider (turn on location)"); return; }
        lm.requestSingleUpdate(prov, new LocationListener() {
            @Override public void onLocationChanged(Location l) { sink.send("location", fmt(l)); }
            @Override public void onStatusChanged(String p, int s, Bundle e) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        }, act.getMainLooper());
    }

    private static String fmt(Location l) { return l.getLatitude() + "," + l.getLongitude() + "," + l.getAccuracy(); }

    // ---------- الكاميرا ----------
    private void camera() {
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "rustpad_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            photoUri = act.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            i.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
        } else photoUri = null;
        act.startActivityForResult(i, RC_PHOTO);
    }

    void onActivityResult(int req, int res, Intent data) {
        if (req != RC_PHOTO && req != RC_PICK) return;
        if (res != Activity.RESULT_OK) { sink.send("error", "cancelled"); return; }
        try {
            File out = new File(act.getCacheDir(), (req == RC_PHOTO ? "photo_" : "picked_") + System.currentTimeMillis() + ".jpg");
            Uri src = req == RC_PHOTO ? photoUri : (data == null ? null : data.getData());
            if (src == null && req == RC_PHOTO && data != null && data.getExtras() != null) {
                android.graphics.Bitmap b = (android.graphics.Bitmap) data.getExtras().get("data");
                try (OutputStream os = new FileOutputStream(out)) { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, os); }
            } else {
                try (InputStream in = act.getContentResolver().openInputStream(src); OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            sink.send(req == RC_PHOTO ? "photo" : "picked", out.getPath());
        } catch (Throwable t) { sink.send("error", "image: " + t); }
    }

    // ---------- المصباح ----------
    private void flashlight(boolean on) throws Exception {
        CameraManager cm = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean f = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (f != null && f) { cm.setTorchMode(id, on); torch = on; return; }
        }
        sink.send("error", "no flashlight");
    }

    // ---------- الإشعارات ----------
    private void notifyUser(String arg) {
        if (Build.VERSION.SDK_INT >= 33 && !granted("android.permission.POST_NOTIFICATIONS")) {
            askPermission("POST_NOTIFICATIONS", "notify", arg); return;
        }
        String title = arg, body = "";
        int tab = arg.indexOf('\t');
        if (tab >= 0) { title = arg.substring(0, tab); body = arg.substring(tab + 1); }
        NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("app", "App", NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(act, "app");
        } else b = new Notification.Builder(act);
        int icon = act.getApplicationInfo().icon;
        b.setSmallIcon(icon != 0 ? icon : android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body).setAutoCancel(true);
        nm.notify((int) (System.currentTimeMillis() & 0xfffffff), b.build());
    }

    // ---------- الحساسات ----------
    private int sensorType(String kind) {
        switch (kind) {
            case "accel": return Sensor.TYPE_ACCELEROMETER;
            case "gyro": return Sensor.TYPE_GYROSCOPE;
            case "light": return Sensor.TYPE_LIGHT;
            case "magnet": return Sensor.TYPE_MAGNETIC_FIELD;
            default: return -1;
        }
    }

    private void startSensor(final String kind) {
        if (sensors.containsKey(kind)) return;
        SensorManager sm = (SensorManager) act.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sensorType(kind) < 0 ? null : sm.getDefaultSensor(sensorType(kind));
        if (s == null) { sink.send("error", "sensor not available: " + kind); return; }
        SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                long now = System.currentTimeMillis();
                Long t = last.get(kind);
                if (t != null && now - t < 200) return;
                last.put(kind, now);
                StringBuilder sb = new StringBuilder(kind).append(':');
                for (int i = 0; i < e.values.length && i < 3; i++) { if (i > 0) sb.append(','); sb.append(e.values[i]); }
                sink.send("sensor", sb.toString());
            }
            @Override public void onAccuracyChanged(Sensor s, int a) { }
        };
        sensors.put(kind, l);
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI);
    }

    private void stopSensor(String kind) {
        SensorEventListener l = sensors.remove(kind);
        if (l != null) ((SensorManager) act.getSystemService(Context.SENSOR_SERVICE)).unregisterListener(l);
    }

    void stopAll() {
        for (String k : new java.util.ArrayList<>(sensors.keySet())) stopSensor(k);
    }
}

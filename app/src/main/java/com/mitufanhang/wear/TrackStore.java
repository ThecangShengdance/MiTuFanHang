package com.mitufanhang.wear;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Durable JSON storage for the active and the most recent completed track. */
public final class TrackStore {
    private static final String TAG = "TrackStore";
    private static final String ACTIVE = "active_track.json";
    private static final String LAST = "last_track.json";

    private TrackStore() {
    }

    private static File file(Context context, String name) {
        return new File(context.getFilesDir(), name);
    }

    public static boolean hasActive(Context context) {
        File f = file(context, ACTIVE);
        return f.exists() && f.length() > 0;
    }

    public static void saveActive(Context context, TrackSession session) {
        write(context, ACTIVE, session);
    }

    public static TrackSession loadActive(Context context) {
        return read(context, ACTIVE);
    }

    public static void clearActive(Context context) {
        File f = file(context, ACTIVE);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "could not delete " + f);
        }
    }

    public static void saveCompleted(Context context, TrackSession session) {
        session.active = false;
        // Only drop the active copy after the completed copy is safely on disk.
        if (write(context, LAST, session)) {
            clearActive(context);
        }
    }

    public static TrackSession loadLast(Context context) {
        return read(context, LAST);
    }

    private static boolean write(Context context, String name, TrackSession session) {
        AtomicFile atomicFile = new AtomicFile(file(context, name));
        FileOutputStream out = null;
        try {
            JSONObject json = session.toJson();
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            out = atomicFile.startWrite();
            out.write(bytes);
            out.flush();
            atomicFile.finishWrite(out);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "save failed: " + name, e);
            if (out != null) {
                atomicFile.failWrite(out);
            }
            return false;
        }
    }

    private static TrackSession read(Context context, String name) {
        FileInputStream in = null;
        try {
            File f = file(context, name);
            if (!f.exists()) {
                return null;
            }
            in = new FileInputStream(f);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                bytes.write(buffer, 0, n);
            }
            return TrackSession.fromJson(new JSONObject(bytes.toString("UTF-8")));
        } catch (Exception e) {
            Log.e(TAG, "read failed: " + name, e);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}

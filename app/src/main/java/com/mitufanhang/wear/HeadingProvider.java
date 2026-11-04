package com.mitufanhang.wear;

import android.content.Context;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Compass heading from the rotation-vector sensor, with an accelerometer +
 * magnetometer fallback for older Wear OS watches. Output is true north
 * (magnetic heading corrected with the local declination).
 */
public final class HeadingProvider implements SensorEventListener {
    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Sensor accelerometer;
    private final Sensor magnetometer;

    private boolean started;
    private boolean hasHeading;
    private float smoothedTrue = Float.NaN;
    private long lastTimestamp;
    private float declination;
    private int lastAccuracy = -1;
    private int magAccuracy = -1;

    private final float[] accelValues = new float[3];
    private final float[] magValues = new float[3];
    private boolean hasAccel;
    private boolean hasMag;
    private boolean magReliable = true;
    private boolean fallbackRegistered;

    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    public HeadingProvider(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sensorManager == null ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    public void start() {
        if (started || sensorManager == null) {
            return;
        }
        started = true;
        hasHeading = false;
        smoothedTrue = Float.NaN;
        lastTimestamp = 0;
        lastAccuracy = -1;
        magAccuracy = -1;
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        // Register the raw sensors briefly as a safety net. Some Wear OS 2
        // watches expose a rotation-vector sensor that never delivers events.
        // As soon as a real rotation-vector heading arrives we unregister them
        // again to keep battery use low.
        registerFallback(rotationSensor == null
                ? SensorManager.SENSOR_DELAY_GAME : SensorManager.SENSOR_DELAY_UI);
    }

    private void registerFallback(int rate) {
        boolean any = false;
        if (accelerometer != null) {
            any |= sensorManager.registerListener(this, accelerometer, rate);
        }
        if (magnetometer != null) {
            any |= sensorManager.registerListener(this, magnetometer, rate);
        }
        fallbackRegistered = any;
    }

    private void unregisterFallback() {
        if (!fallbackRegistered) {
            return;
        }
        fallbackRegistered = false;
        if (accelerometer != null) {
            sensorManager.unregisterListener(this, accelerometer);
        }
        if (magnetometer != null) {
            sensorManager.unregisterListener(this, magnetometer);
        }
        hasAccel = false;
        hasMag = false;
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        started = false;
        hasHeading = false;
        smoothedTrue = Float.NaN;
        lastTimestamp = 0;
        lastAccuracy = -1;
        magAccuracy = -1;
        hasAccel = false;
        hasMag = false;
        fallbackRegistered = false;
    }

    /** True when the watch exposes a rotation vector or a magnetometer. */
    public boolean hasCompassSensor() {
        return rotationSensor != null || magnetometer != null;
    }

    public boolean hasHeading() {
        return hasHeading && !Float.isNaN(smoothedTrue);
    }

    /** True-north heading in degrees, or NaN before the first reading. */
    public float getHeading() {
        return hasHeading ? smoothedTrue : Float.NaN;
    }

    /** Update magnetic declination for the current position. */
    public void setLocation(double lat, double lon, double altitude) {
        try {
            GeomagneticField field = new GeomagneticField((float) lat, (float) lon,
                    (float) altitude, System.currentTimeMillis());
            declination = field.getDeclination();
        } catch (Exception ignored) {
            declination = 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor == rotationSensor) {
            if (event.accuracy >= 0) {
                lastAccuracy = event.accuracy;
            }
            applyRotationVector(event.values);
        } else if (event.sensor == accelerometer) {
            lowPass(event.values, accelValues, 0.25f);
            hasAccel = true;
            tryComputeFallback();
        } else if (event.sensor == magnetometer) {
            if (event.accuracy >= 0) {
                magAccuracy = event.accuracy;
            }
            lowPass(event.values, magValues, 0.25f);
            hasMag = true;
            tryComputeFallback();
        }
    }

    private void applyRotationVector(float[] values) {
        if (values.length < 3) {
            return;
        }
        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values);
            SensorManager.getOrientation(rotationMatrix, orientation);
            float magnetic = (float) Math.toDegrees(orientation[0]);
            updateHeading(magnetic, System.nanoTime());
            unregisterFallback();
        } catch (Exception ignored) {
            // fall through to the accelerometer + magnetometer path
        }
    }

    private void tryComputeFallback() {
        if (!hasAccel || !hasMag) {
            return;
        }
        float[] r = new float[9];
        float[] i = new float[9];
        if (SensorManager.getRotationMatrix(r, i, accelValues, magValues)) {
            SensorManager.getOrientation(r, orientation);
            float magnetic = (float) Math.toDegrees(orientation[0]);
            lastAccuracy = magAccuracy;
            updateHeading(magnetic, System.nanoTime());
        }
    }

    private void updateHeading(float magneticDegrees, long timestamp) {
        if (!magReliable && rotationSensor == null) {
            return;
        }
        float trueHeading = (float) Geo.normalizeDegrees(magneticDegrees + declination);
        if (!hasHeading || Float.isNaN(smoothedTrue)) {
            smoothedTrue = trueHeading;
            hasHeading = true;
        } else {
            long dtNs = Math.max(1, timestamp - lastTimestamp);
            double dt = dtNs / 1_000_000_000.0;
            // ~7 Hz time constant: calm enough for a swinging wrist, still responsive.
            float alpha = (float) (1.0 - Math.exp(-dt * 7.0));
            alpha = Math.max(0.04f, Math.min(0.35f, alpha));
            float delta = (float) Geo.angleDelta(smoothedTrue, trueHeading);
            smoothedTrue = (float) Geo.normalizeDegrees(smoothedTrue + delta * alpha);
        }
        lastTimestamp = timestamp;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor == null) {
            return;
        }
        if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            magAccuracy = accuracy;
            magReliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE;
            if (rotationSensor == null) {
                lastAccuracy = accuracy;
            }
        } else if (sensor == rotationSensor) {
            lastAccuracy = accuracy;
        }
    }

    /** SensorManager accuracy (0..3), or -1 before the first reading. */
    public int getAccuracy() {
        return lastAccuracy;
    }

    /** True when the heading is fresh and the sensor reports usable accuracy. */
    public boolean isReliable() {
        if (!hasHeading || lastAccuracy < 0) {
            return false;
        }
        if (lastAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            return false;
        }
        return System.nanoTime() - lastTimestamp < 3_000_000_000L;
    }

    /**
     * True when the compass needs the user to wave the watch in a figure 8:
     * no heading yet, low accuracy, or no event for three seconds.
     */
    public boolean needsCalibration() {
        return !hasHeading || !isReliable();
    }

    private static void lowPass(float[] input, float[] output, float alpha) {
        for (int i = 0; i < 3; i++) {
            output[i] = output[i] + alpha * (input[i] - output[i]);
        }
    }
}

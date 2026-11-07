/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.audiofx.backends;

import android.media.AudioDeviceInfo;
import android.media.audiofx.AudioEffect;
import android.util.Log;

import org.lineageos.audiofx.Constants;

import java.util.Arrays;
import java.util.UUID;

/**
 * Dolby Audio Processing (DAP), running globally on the output mix.
 */
class DolbyEffects extends EffectSet {

    private static final String TAG = "AudioFx-DolbyEffects";

    private static final UUID EFFECT_TYPE_DS =
            UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210");
    static final UUID EFFECT_TYPE_DAP = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa");
    private static final int EFFECT_PRIORITY = 1;

    // Commands, all sent through the values parameter
    private static final int PARAM_VALUES = 5;
    private static final int CMD_DS_ON = 0;
    private static final int CMD_SET_PROFILE_PARAMETER = 0x1000000;
    private static final int CMD_PROFILE = 0xA000000;

    private static final int PROFILE_SMART = 0;
    private static final int PROFILE_MUSIC = 2;

    // Profile parameters
    private static final int DAP_HEADPHONE_VIRTUALIZER = 101; // vdhe
    private static final int DAP_VOLUME_LEVELER = 103; // dvle
    private static final int DAP_IEQ_PRESET = 104; // ieid
    private static final int DAP_DIALOGUE_ENHANCER = 105; // deon
    private static final int DAP_GEQ_ENABLE = 106; // geon
    private static final int DAP_GEQ_BAND_GAINS = 110; // gebg
    private static final int DAP_BASS_ENHANCER = 111; // beon

    private static final int GEQ_BANDS = 20;
    // Gains are in 1/16 dB
    private static final int GEQ_STEPS_PER_DB = 16;

    // Every other GEQ band, the ones in between are interpolated
    private static final int[] CENTER_FREQS_HZ = {
            32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
    };
    private static final short[] LEVEL_RANGE_MB = {-1200, 1200};

    // The AOSP equalizer presets, interpolated to the bands above
    private static final String[] PRESET_NAMES = {
            "Normal", "Classical", "Dance", "Flat", "Folk", "Heavy Metal", "Hip Hop", "Jazz",
            "Pop", "Rock"
    };
    private static final int[][] PRESET_LEVELS_MB = {
            {300, 286, 136, 0, 0, 0, 0, 23, 176, 300},
            {500, 490, 391, 270, 18, -159, 144, 400, 400, 400},
            {600, 571, 272, 12, 113, 214, 315, 377, 224, 100},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {300, 286, 136, 0, 0, 14, 115, 177, 24, -100},
            {400, 386, 236, 149, 552, 859, 556, 277, 124, 0},
            {500, 490, 391, 282, 131, 7, 57, 116, 218, 300},
            {400, 390, 291, 176, -26, -173, 29, 223, 376, 500},
            {-100, -86, 64, 218, 369, 473, 271, 77, -76, -200},
            {500, 490, 391, 276, 74, -73, 129, 316, 418, 500},
    };

    private AudioEffect mDap;

    private int mProfile = PROFILE_SMART;

    private boolean mEqualizerEnabled;
    private final int[] mLevelsMb = new int[CENTER_FREQS_HZ.length];
    private int[] mSentGains;

    private boolean mBassBoostEnabled;
    private short mBassBoostStrength;

    private boolean mVirtualizerEnabled;
    private short mVirtualizerStrength;

    // null keeps the tuned default of the profile
    private Integer mIntelligentEq;
    private Boolean mDialogueEnhancer;
    private Boolean mVolumeLeveler;

    private boolean mDeferUpdates;

    DolbyEffects(int sessionId, AudioDeviceInfo deviceInfo) {
        super(sessionId, deviceInfo);
    }

    @Override
    protected void onCreate() {
        super.onCreate();
        // Without a device this only describes the backend, don't touch the global effect
        if (getDevice() != null) {
            createDap();
        }
    }

    private void createDap() {
        mDap = new AudioEffect(EFFECT_TYPE_DS, EFFECT_TYPE_DAP, EFFECT_PRIORITY, mSessionId);
        Log.i(TAG, "Created DAP for session " + mSessionId);
        sendCommand(CMD_PROFILE, mProfile);
    }

    @Override
    public void release() {
        super.release();
        if (mDap != null) {
            mDap.release();
            mDap = null;
        }
    }

    @Override
    public int getBrand() {
        return Constants.EFFECT_TYPE_DOLBY;
    }

    @Override
    public void setGlobalEnabled(boolean globalEnabled) {
        super.setGlobalEnabled(globalEnabled);
        if (mDap == null) {
            return;
        }
        sendCommand(CMD_DS_ON, globalEnabled ? 1 : 0);
        try {
            mDap.setEnabled(globalEnabled);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Failed to enable DAP", e);
        }
    }

    @Override
    public void setDevice(AudioDeviceInfo deviceInfo) {
        super.setDevice(deviceInfo);
        if (mDap == null && deviceInfo != null) {
            createDap();
            setGlobalEnabled(mGlobalEnabled);
            commitUpdate();
        } else {
            applyBassBoost();
            applyVirtualizer();
        }
    }

    @Override
    public boolean beginUpdate() {
        mDeferUpdates = true;
        return true;
    }

    @Override
    public boolean commitUpdate() {
        mDeferUpdates = false;
        applyEqualizer();
        applyBassBoost();
        applyVirtualizer();
        applyDolbySettings();
        return true;
    }

    /* ---- Equalizer ---- */

    @Override
    public void enableEqualizer(boolean enable) {
        mEqualizerEnabled = enable;
        applyEqualizer();
    }

    @Override
    public void setEqualizerLevelsDecibels(float[] levels) {
        for (int i = 0; i < mLevelsMb.length && i < levels.length; i++) {
            mLevelsMb[i] = clampLevel(Math.round(levels[i] * 100));
        }
        applyEqualizer();
    }

    @Override
    public short getNumEqualizerBands() {
        return (short) CENTER_FREQS_HZ.length;
    }

    @Override
    public void setEqualizerBandLevel(short band, float level) {
        mLevelsMb[band] = clampLevel(Math.round(level));
        applyEqualizer();
    }

    @Override
    public int getEqualizerBandLevel(short band) {
        return mLevelsMb[band];
    }

    @Override
    public String getEqualizerPresetName(short preset) {
        return PRESET_NAMES[preset];
    }

    @Override
    public void useEqualizerPreset(short preset) {
        System.arraycopy(PRESET_LEVELS_MB[preset], 0, mLevelsMb, 0, mLevelsMb.length);
        applyEqualizer();
    }

    @Override
    public short getNumEqualizerPresets() {
        return (short) PRESET_NAMES.length;
    }

    @Override
    public short[] getEqualizerBandLevelRange() {
        return LEVEL_RANGE_MB.clone();
    }

    @Override
    public int getCenterFrequency(short band) {
        return CENTER_FREQS_HZ[band] * 1000;
    }

    /* ---- Bass boost ---- */

    @Override
    public boolean hasBassBoost() {
        return true;
    }

    @Override
    public void enableBassBoost(boolean enable) {
        mBassBoostEnabled = enable;
        applyBassBoost();
    }

    @Override
    public void setBassBoostStrength(short strength) {
        mBassBoostStrength = strength;
        applyBassBoost();
    }

    /* ---- Virtualizer ---- */

    @Override
    public boolean hasVirtualizer() {
        return true;
    }

    @Override
    public void enableVirtualizer(boolean enable) {
        mVirtualizerEnabled = enable;
        applyVirtualizer();
    }

    @Override
    public void setVirtualizerStrength(short strength) {
        mVirtualizerStrength = strength;
        applyVirtualizer();
    }

    /* ---- Dolby settings ---- */

    @Override
    public boolean hasDolbySettings() {
        return true;
    }

    @Override
    public void setDolbyProfile(int profile) {
        if (profile < PROFILE_SMART || profile > PROFILE_MUSIC || profile == mProfile) {
            return;
        }
        mProfile = profile;
        sendCommand(CMD_PROFILE, mProfile);
        // The other settings are per profile, apply them again
        mSentGains = null;
        if (!mDeferUpdates) {
            commitUpdate();
        }
    }

    @Override
    public void setDolbyIntelligentEq(int preset) {
        mIntelligentEq = preset;
        applyDolbySettings();
    }

    @Override
    public void setDolbyDialogueEnhancer(boolean enable) {
        mDialogueEnhancer = enable;
        applyDolbySettings();
    }

    @Override
    public void setDolbyVolumeLeveler(boolean enable) {
        mVolumeLeveler = enable;
        applyDolbySettings();
    }

    /* ---- DAP ---- */

    private void applyEqualizer() {
        if (mDeferUpdates) {
            return;
        }
        // GEQ bands sit between the shown ones, interpolate them
        final int[] gains = new int[GEQ_BANDS];
        for (int i = 0; i < GEQ_BANDS; i++) {
            final int band = i / 2;
            final int levelMb = i % 2 == 1 && band + 1 < mLevelsMb.length
                    ? (mLevelsMb[band] + mLevelsMb[band + 1]) / 2
                    : mLevelsMb[band];
            gains[i] = levelMb * GEQ_STEPS_PER_DB / 100;
        }
        setProfileParameter(DAP_GEQ_ENABLE, mEqualizerEnabled ? 1 : 0);
        if (!Arrays.equals(gains, mSentGains)) {
            mSentGains = gains;
            setProfileParameter(DAP_GEQ_BAND_GAINS, gains);
        }
    }

    private void applyBassBoost() {
        if (mDeferUpdates) {
            return;
        }
        // Only headphones, the speaker keeps its tuning
        final boolean enable = mBassBoostEnabled && mBassBoostStrength > 0;
        setProfileParameter(DAP_BASS_ENHANCER, enable && isHeadphone(getDevice()) ? 1 : 0);
    }

    private void applyVirtualizer() {
        if (mDeferUpdates) {
            return;
        }
        // Only headphones, the speaker keeps its tuning
        final boolean enable = mVirtualizerEnabled && mVirtualizerStrength > 0;
        setProfileParameter(DAP_HEADPHONE_VIRTUALIZER, enable && isHeadphone(getDevice()) ? 1 : 0);
    }

    private void applyDolbySettings() {
        if (mDeferUpdates) {
            return;
        }
        if (mIntelligentEq != null) {
            setProfileParameter(DAP_IEQ_PRESET, mIntelligentEq);
        }
        if (mDialogueEnhancer != null) {
            setProfileParameter(DAP_DIALOGUE_ENHANCER, mDialogueEnhancer ? 1 : 0);
        }
        if (mVolumeLeveler != null) {
            setProfileParameter(DAP_VOLUME_LEVELER, mVolumeLeveler ? 1 : 0);
        }
    }

    private static boolean isHeadphone(AudioDeviceInfo device) {
        if (device == null) {
            return false;
        }
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return true;
            default:
                return false;
        }
    }

    private static int clampLevel(int levelMb) {
        return Math.max(LEVEL_RANGE_MB[0], Math.min(LEVEL_RANGE_MB[1], levelMb));
    }

    // command, count, value
    private void sendCommand(int command, int value) {
        setValues(new int[]{command, 1, value});
    }

    // command, count, profile, parameter, values...
    private void setProfileParameter(int parameter, int... values) {
        final int[] data = new int[values.length + 4];
        data[0] = CMD_SET_PROFILE_PARAMETER;
        data[1] = values.length + 1;
        data[2] = mProfile;
        data[3] = parameter;
        System.arraycopy(values, 0, data, 4, values.length);
        setValues(data);
    }

    private void setValues(int[] data) {
        if (mDap == null) {
            return;
        }
        final byte[] buf = new byte[data.length * 4];
        for (int i = 0; i < data.length; i++) {
            buf[i * 4] = (byte) data[i];
            buf[i * 4 + 1] = (byte) (data[i] >>> 8);
            buf[i * 4 + 2] = (byte) (data[i] >>> 16);
            buf[i * 4 + 3] = (byte) (data[i] >>> 24);
        }
        try {
            final int status = mDap.setParameter(PARAM_VALUES, buf);
            if (status != AudioEffect.SUCCESS) {
                Log.w(TAG, "setParameter failed: " + status);
            }
        } catch (IllegalStateException | UnsupportedOperationException e) {
            Log.e(TAG, "setParameter failed", e);
        }
    }
}

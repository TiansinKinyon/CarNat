package com.baidu.carlife.mixing.aidl;

interface ISystemAudioCallback {
    void onAudioStreamReady(in byte[] data, int offset, int length, int sampleRate, int channels);
    void onAudioStateChange(int stream, int state);
    void onAudioStateChangeWithParams(int stream, int state, int sampleRate, int channels, int format);
}

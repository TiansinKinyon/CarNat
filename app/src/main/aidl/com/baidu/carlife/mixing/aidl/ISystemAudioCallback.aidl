package com.baidu.carlife.mixing.aidl;

interface ISystemAudioCallback {
    void onAudioStreamReady(in byte[] data, int stream, int sampleRate, int channels, int format);
    void onAudioStateChange(int stream, int state);
    void onAudioStateChangeWithParams(int stream, int state, int sampleRate, int channels, int format);
}

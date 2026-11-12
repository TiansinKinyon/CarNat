package com.baidu.carlife.mixing.aidl;

interface IRemoteAudioListener {
    void onReceiveAudioStream(in byte[] data, int offset, int length, int format);
}

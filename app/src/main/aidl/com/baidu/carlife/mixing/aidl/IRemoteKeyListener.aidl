package com.baidu.carlife.mixing.aidl;

import android.view.KeyEvent;
interface IRemoteKeyListener {
    boolean onKeyEvent(in KeyEvent event);
}

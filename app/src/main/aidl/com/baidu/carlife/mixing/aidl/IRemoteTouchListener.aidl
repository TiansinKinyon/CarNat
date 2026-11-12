package com.baidu.carlife.mixing.aidl;

import android.view.MotionEvent;
interface IRemoteTouchListener {
    boolean onTouchEvent(in MotionEvent event);
}

package com.baidu.carlife.xiaomi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.baidu.carlife.xiaomi.ui.projection.ProjectionScreen
import com.baidu.carlife.xiaomi.ui.theme.CarNatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CarNatTheme { ProjectionScreen() } }
    }
}
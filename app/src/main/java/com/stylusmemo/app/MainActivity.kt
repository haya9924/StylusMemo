package com.stylusmemo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.stylusmemo.app.ui.AppNavHost
import com.stylusmemo.app.ui.StylusMemoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StylusMemoTheme {
                AppNavHost()
            }
        }
    }
}

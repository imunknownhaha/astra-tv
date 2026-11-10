package com.astratv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import com.astratv.player.core.ui.AstraTheme
import com.astratv.player.feature.MainViewModel
import com.astratv.player.feature.AstraApp

@UnstableApi
class MainActivity: ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowInsetsControllerCompat(window,window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        setContent { AstraTheme { AstraApp(model) } }
    }
    override fun onStop() { model.background();super.onStop() }
}

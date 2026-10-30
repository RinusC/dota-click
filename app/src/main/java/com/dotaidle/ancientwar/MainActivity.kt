package com.dotaidle.ancientwar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.dotaidle.ancientwar.ui.GameApp
import com.dotaidle.ancientwar.ui.GameViewModel
import com.dotaidle.ancientwar.ui.theme.DotaIdleTheme

class MainActivity : ComponentActivity() {
    private val vm: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DotaIdleTheme {
                GameApp(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume() // offline progress since last save + restart the tick loop
    }

    override fun onPause() {
        vm.onPause() // stop ticking + synchronous save
        super.onPause()
    }
}

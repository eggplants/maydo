package io.github.eggplants.maydo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.eggplants.maydo.ui.MaydoRoot
import io.github.eggplants.maydo.ui.theme.MaydoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaydoTheme { MaydoRoot(container) } }
    }
}

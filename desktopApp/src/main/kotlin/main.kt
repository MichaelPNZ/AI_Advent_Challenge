import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.pozyalov.ai_advent_challenge.App
import com.pozyalov.ai_advent_challenge.di.desktopAppModule
import com.pozyalov.ai_advent_challenge.di.initKoin
import com.pozyalov.ai_advent_challenge.initLogs
import java.awt.Dimension

fun main() = application {
    initLogs()
    initKoin(appModule = desktopAppModule())

    Window(
        title = "AI_Advent_Challenge_#4",
        state = rememberWindowState(width = 800.dp, height = 600.dp),
        onCloseRequest = ::exitApplication,
    ) {
        window.minimumSize = Dimension(350, 600)
        App()
    }
}

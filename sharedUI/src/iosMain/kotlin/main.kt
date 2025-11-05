import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.pozyalov.ai_advent_challenge.App
import com.pozyalov.ai_advent_challenge.di.initKoin
import com.pozyalov.ai_advent_challenge.initLogs
import org.koin.dsl.module
import platform.UIKit.UIApplication
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIViewController
import platform.UIKit.setStatusBarStyle

fun MainViewController(): UIViewController = ComposeUIViewController {
    remember {
        initLogs()
        initKoin(appModule = module {})
    }
    App(onThemeChanged = { ThemeChanged(it) })
}

@Composable
private fun ThemeChanged(isDark: Boolean) {
    LaunchedEffect(isDark) {
        UIApplication.sharedApplication.setStatusBarStyle(
            if (isDark) UIStatusBarStyleDarkContent else UIStatusBarStyleLightContent
        )
    }
}

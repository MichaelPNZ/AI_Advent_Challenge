@file:OptIn(ExperimentalForeignApi::class)

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.pozyalov.ai_advent_challenge.App
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.data.RoomChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.data.local.ChatDatabase
import com.pozyalov.ai_advent_challenge.di.initKoin
import com.pozyalov.ai_advent_challenge.initLogs
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.dsl.module
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent
import platform.UIKit.UIViewController
import platform.UIKit.setStatusBarStyle

fun MainViewController(): UIViewController = ComposeUIViewController {
    remember {
        initLogs()
        initKoin(appModule = iosRoomModule())
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

private fun iosRoomModule() = module {
    single {
        Room.databaseBuilder<ChatDatabase>(name = iosChatDatabasePath())
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    single { get<ChatDatabase>().chatMessageDao() }
    single<ChatHistoryDataSource> {
        RoomChatHistoryDataSource(dao = get())
    }
}

private fun iosChatDatabasePath(): String {
    val fileManager = NSFileManager.defaultManager
    val documentsUrl = fileManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first()
        as? NSURL
    val directory = documentsUrl?.path ?: NSTemporaryDirectory()
    if (!fileManager.fileExistsAtPath(directory)) {
        fileManager.createDirectoryAtPath(directory, true, null, null)
    }
    return directory.trimEnd('/') + "/chat_history.db"
}

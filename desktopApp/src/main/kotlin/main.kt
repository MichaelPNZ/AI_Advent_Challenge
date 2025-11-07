import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.pozyalov.ai_advent_challenge.App
import com.pozyalov.ai_advent_challenge.chat.data.ChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.data.RoomChatHistoryDataSource
import com.pozyalov.ai_advent_challenge.chat.data.local.ChatDatabase
import com.pozyalov.ai_advent_challenge.di.initKoin
import com.pozyalov.ai_advent_challenge.initLogs
import org.koin.dsl.module
import java.awt.Dimension
import java.io.File

fun main() = application {
    initLogs()
    initKoin(appModule = desktopRoomModule())

    Window(
        title = "AI_Advent_Challenge_#4",
        state = rememberWindowState(width = 800.dp, height = 600.dp),
        onCloseRequest = ::exitApplication,
    ) {
        window.minimumSize = Dimension(350, 600)
        App()
    }
}

private fun desktopRoomModule() = module {
    single {
        val dbPath = desktopChatDatabasePath()
        Room.databaseBuilder<ChatDatabase>(name = dbPath)
            .setDriver(BundledSQLiteDriver())
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }
    single { get<ChatDatabase>().chatMessageDao() }
    single<ChatHistoryDataSource> {
        RoomChatHistoryDataSource(dao = get())
    }
}

private fun desktopChatDatabasePath(): String {
    val userHome = System.getProperty("user.home").orEmpty().ifBlank { "." }
    val directory = File(userHome, ".ai_advent")
    if (!directory.exists()) {
        directory.mkdirs()
    }
    return File(directory, "chat_history.db").absolutePath
}

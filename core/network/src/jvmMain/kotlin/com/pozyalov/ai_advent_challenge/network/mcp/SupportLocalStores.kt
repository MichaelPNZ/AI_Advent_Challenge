package com.pozyalov.ai_advent_challenge.network.mcp

import java.io.File
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SupportTicket(
    val id: String,
    val userId: String,
    val title: String,
    val description: String,
    val category: String,
    val priority: String,
    val status: String,
    val comments: List<TicketComment> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class TicketComment(
    val author: String,
    val text: String,
    val timestamp: Long
)

data class TicketStats(
    val total: Int,
    val open: Int,
    val inProgress: Int,
    val resolved: Int,
    val closed: Int,
    val byCategory: Map<String, Int>
)

class SupportTicketStore(
    private val file: File = defaultFile(),
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true; explicitNulls = false }
) {
    private val mutex = Mutex()
    private var tickets: List<SupportTicket> = load()

    private fun load(): List<SupportTicket> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<SupportTicket>>(file.readText()) }
            .getOrElse { emptyList() }
    }

    private suspend fun persist() {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        file.writeText(json.encodeToString(tickets))
    }

    suspend fun createTicket(
        userId: String,
        title: String,
        description: String,
        category: String,
        priority: String
    ): SupportTicket = mutex.withLock {
        val now = System.currentTimeMillis()
        val ticket = SupportTicket(
            id = UUID.randomUUID().toString().take(8),
            userId = userId,
            title = title,
            description = description,
            category = category,
            priority = priority,
            status = "open",
            comments = emptyList(),
            createdAt = now,
            updatedAt = now
        )
        tickets = tickets + ticket
        persist()
        ticket
    }

    suspend fun getTicket(ticketId: String): SupportTicket? = mutex.withLock {
        tickets.firstOrNull { it.id == ticketId }
    }

    suspend fun updateTicketStatus(ticketId: String, status: String): SupportTicket? = mutex.withLock {
        val ticket = tickets.firstOrNull { it.id == ticketId } ?: return@withLock null
        val updated = ticket.copy(status = status, updatedAt = System.currentTimeMillis())
        tickets = tickets.map { if (it.id == ticketId) updated else it }
        persist()
        updated
    }

    suspend fun addComment(ticketId: String, author: String, text: String): SupportTicket? = mutex.withLock {
        val ticket = tickets.firstOrNull { it.id == ticketId } ?: return@withLock null
        val comment = TicketComment(author = author, text = text, timestamp = System.currentTimeMillis())
        val updated = ticket.copy(
            comments = ticket.comments + comment,
            updatedAt = System.currentTimeMillis()
        )
        tickets = tickets.map { if (it.id == ticketId) updated else it }
        persist()
        updated
    }

    suspend fun listTickets(
        userId: String?,
        status: String?,
        category: String?,
        limit: Int
    ): List<SupportTicket> = mutex.withLock {
        tickets
            .filter { userId == null || it.userId == userId }
            .filter { status == null || it.status.equals(status, ignoreCase = true) }
            .filter { category == null || it.category.equals(category, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
            .take(limit)
    }

    suspend fun getStats(): TicketStats = mutex.withLock {
        TicketStats(
            total = tickets.size,
            open = tickets.count { it.status == "open" },
            inProgress = tickets.count { it.status == "in_progress" },
            resolved = tickets.count { it.status == "resolved" },
            closed = tickets.count { it.status == "closed" },
            byCategory = tickets.groupingBy { it.category }.eachCount()
        )
    }

    suspend fun previewUserTickets(userId: String, limit: Int = 5): List<SupportTicket> =
        listTickets(userId = userId, status = null, category = null, limit = limit)

    companion object {
        fun defaultFile(): File {
            val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
            val directory = File(home, ".ai_advent")
            if (!directory.exists()) directory.mkdirs()
            return File(directory, "support_tickets.json")
        }
    }
}

@Serializable
data class SupportUser(
    val id: String,
    val name: String,
    val email: String? = null,
    val plan: String? = null,
    val company: String? = null,
    val segment: String? = null,
    val notes: String? = null
)

class SupportUserStore(
    private val file: File? = defaultFile(),
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }
) {
    private val fallback = listOf(
        SupportUser(
            id = "user123",
            name = "Иван Петров",
            email = "ivan@example.com",
            plan = "pro",
            company = "Acme",
            segment = "mobile",
            notes = "Часто спрашивает про RAG и авторизацию."
        ),
        SupportUser(
            id = "user456",
            name = "Мария Смирнова",
            email = "maria@example.com",
            plan = "starter",
            company = "Contoso",
            segment = "web",
            notes = "Есть открытый тикет по сборке."
        ),
        SupportUser(
            id = "user789",
            name = "Alex Lee",
            email = "alex@example.com",
            plan = "enterprise",
            company = "Globex",
            segment = "enterprise",
            notes = "Запросы по SLA и отчетности."
        )
    )

    fun getUser(userId: String): SupportUser? {
        val users = loadUsers()
        return users.firstOrNull { it.id == userId }
    }

    fun listUsers(limit: Int = 20): List<SupportUser> {
        val users = loadUsers()
        return users.take(limit.coerceAtLeast(1))
    }

    private fun loadUsers(): List<SupportUser> {
        val target = file ?: return fallback
        val located = locateRelativePath(target)
        if (!located.exists()) return fallback
        return runCatching { json.decodeFromString<List<SupportUser>>(located.readText()) }
            .getOrElse { fallback }
    }

    private fun locateRelativePath(path: File): File {
        if (path.isAbsolute) return path
        var current: File? = File(System.getProperty("user.dir", ".")).canonicalFile
        repeat(6) {
            current ?: return@repeat
            val candidate = File(current, path.path)
            if (candidate.exists()) return candidate
            current = current.parentFile
        }
        return path
    }

    companion object {
        fun defaultFile(): File? {
            val envPath = System.getProperty("ai.advent.support.users.path")
                ?: System.getenv("SUPPORT_USERS_PATH")
            if (!envPath.isNullOrBlank()) return File(envPath)
            return File("docs/support-users.json")
        }
    }
}

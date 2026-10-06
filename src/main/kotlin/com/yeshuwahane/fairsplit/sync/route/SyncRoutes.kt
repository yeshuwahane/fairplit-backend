package com.yeshuwahane.fairsplit.sync.route

import com.yeshuwahane.fairsplit.common.api.ApiResponse
import com.yeshuwahane.fairsplit.domain.model.*
import com.yeshuwahane.fairsplit.domain.repository.*
import com.yeshuwahane.fairsplit.domain.service.*
import com.yeshuwahane.fairsplit.infrastructure.auth.FairSplitPrincipal
import com.yeshuwahane.fairsplit.user.repository.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

// Mobile-compatible DTOs
@Serializable
data class SyncMemberDto(
    val id: String,
    val name: String,
    val avatarInitials: String = "",
    val avatarColorHex: Long = 0xFF10B981,
    val avatarUrl: String? = null,
    val isCurrentUser: Boolean = false,
    val totalSpent: Double = 0.0,
    val balance: Double = 0.0
)

@Serializable
data class SyncMemberShareDto(
    val member: SyncMemberDto,
    val shareAmount: Double
)

@Serializable
data class SyncCategoryBreakdownDto(
    val category: String,
    val amount: Double,
    val percentage: Float
)

@Serializable
data class SyncSettlementDto(
    val id: String,
    val fromMember: SyncMemberDto,
    val toMember: SyncMemberDto,
    val amount: Double,
    val status: String = "PENDING"
)

@Serializable
data class SyncExpenseDto(
    val id: String,
    val epicId: String,
    val epicName: String = "",
    val title: String,
    val amount: Double,
    val paidBy: SyncMemberDto,
    val participants: List<SyncMemberDto> = emptyList(),
    val date: String = "Today",
    val category: String = "FOOD_AND_DRINKS",
    val note: String? = null,
    val receiptUrl: String? = null,
    val shares: List<SyncMemberShareDto> = emptyList(),
    val yourShare: Double = 0.0,
    val yourBalanceImpact: Double = 0.0,
    val createdBy: SyncMemberDto? = null,
    val createdAtTime: String = "Just now"
)

@Serializable
data class SyncEpicDto(
    val id: String,
    val name: String,
    val description: String = "",
    val totalSpent: Double = 0.0,
    val memberCount: Int = 0,
    val members: List<SyncMemberDto> = emptyList(),
    val yourSpent: Double = 0.0,
    val yourBalance: Double = 0.0,
    val status: String = "ACTIVE",
    val categoryBreakdowns: List<SyncCategoryBreakdownDto> = emptyList(),
    val recentExpenses: List<SyncExpenseDto> = emptyList(),
    val settlements: List<SyncSettlementDto> = emptyList(),
    val createdAt: String = "Sep 2026",
    val inviteCode: String = ""
)

@Serializable
data class SyncTransactionDto(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val amount: Double,
    val epicName: String = "",
    val date: String = "Today",
    val time: String = "Just now",
    val status: String = "COMPLETED",
    val fromMember: SyncMemberDto,
    val toMember: SyncMemberDto,
    val epicId: String = ""
)

@Serializable
data class SyncActivityDto(
    val id: String,
    val epicId: String,
    val epicName: String,
    val type: String,
    val title: String,
    val description: String,
    val amount: Double? = null,
    val actor: SyncMemberDto,
    val timestamp: String = "Just now",
    val date: String = "Today",
    val receiptUrl: String? = null,
    val expenseId: String? = null
)

@Serializable
data class JoinEpicRequest(
    val inviteCode: String,
    val member: SyncMemberDto
)

@Serializable
data class SyncImageUploadRequest(
    val fileName: String,
    val base64Data: String,
    val mimeType: String = "image/jpeg"
)

@Serializable
data class SyncImageUploadResponse(
    val url: String,
    val fileName: String
)

fun Application.configureSyncRoutes(
    epicService: EpicService,
    expenseService: ExpenseService,
    settlementService: SettlementService,
    epicRepository: EpicRepository,
    expenseRepository: ExpenseRepository,
    settlementRepository: SettlementRepository,
    activityRepository: ActivityRepository,
    userRepository: UserRepository,
    idempotencyService: IdempotencyService,
    epicRealtimeBroadcaster: com.yeshuwahane.fairsplit.realtime.broadcaster.EpicRealtimeBroadcaster,
    storageConfig: com.yeshuwahane.fairsplit.config.StorageConfig = com.yeshuwahane.fairsplit.config.StorageConfig()
) {
    // Helper to map an Epic + its members + server-derived balances into a SyncEpicDto
    suspend fun buildSyncEpicDto(epic: Epic, callerId: UUID?): SyncEpicDto {
        val members = epicRepository.getMembers(epic.id)
        val memberUserIds = members.map { it.userId }.toSet()
        val expenses = expenseRepository.findByEpicId(epic.id, limit = 500)
        val allSplits = expenseRepository.getSplitsForEpic(epic.id)
        val settlements = settlementRepository.findByEpicId(epic.id, limit = 500)

        val allUserIds = (memberUserIds + expenses.map { it.paidByUserId } + allSplits.map { it.userId } + settlements.flatMap { listOf(it.fromUserId, it.toUserId) }).toSet()
        val users = allUserIds.mapNotNull { userRepository.findById(it) }.associateBy { it.id }

        // Authoritative server balance calculation
        val netBalances = BalanceCalculator.calculateNetBalances(memberUserIds, expenses, allSplits, settlements)

        val syncMembers = members.map { m ->
            val u = users[m.userId]
            val bal = netBalances[m.userId]
            val netBal = (bal?.netBalanceMinor ?: 0L) / 100.0
            val totalPaid = (bal?.totalPaidMinor ?: 0L) / 100.0

            SyncMemberDto(
                id = m.userId.toString(),
                name = u?.name?.ifBlank { "User ${m.userId.toString().take(4)}" } ?: "User",
                avatarInitials = (u?.name?.take(2)?.uppercase()) ?: "FS",
                avatarUrl = u?.avatarUrl,
                isCurrentUser = (callerId != null && m.userId == callerId),
                totalSpent = totalPaid,
                balance = netBal
            )
        }

        val totalSpentMajor = expenses.sumOf { it.amountMinor } / 100.0
        val callerBalance = callerId?.let { netBalances[it]?.netBalanceMinor }?.let { it / 100.0 } ?: 0.0
        val callerSpent = callerId?.let { netBalances[it]?.totalPaidMinor }?.let { it / 100.0 } ?: 0.0

        val syncExpenses = expenses.map { exp ->
            val splits = allSplits.filter { it.expenseId == exp.id }
            val paidByUser = users[exp.paidByUserId]
            val paidByMember = SyncMemberDto(
                id = exp.paidByUserId.toString(),
                name = paidByUser?.name ?: "User",
                avatarUrl = paidByUser?.avatarUrl
            )
            val participantMembers = splits.map { sp ->
                val pu = users[sp.userId]
                SyncMemberDto(
                    id = sp.userId.toString(),
                    name = pu?.name ?: "User",
                    avatarUrl = pu?.avatarUrl
                )
            }
            val shares = splits.map { sp ->
                val pu = users[sp.userId]
                SyncMemberShareDto(
                    member = SyncMemberDto(id = sp.userId.toString(), name = pu?.name ?: "User", avatarUrl = pu?.avatarUrl),
                    shareAmount = sp.amountMinor / 100.0
                )
            }
            val callerShare = if (callerId != null) {
                splits.firstOrNull { it.userId == callerId }?.amountMinor?.let { it / 100.0 } ?: 0.0
            } else 0.0

            SyncExpenseDto(
                id = exp.id.toString(),
                epicId = exp.epicId.toString(),
                epicName = epic.title,
                title = exp.title,
                amount = exp.amountMinor / 100.0,
                paidBy = paidByMember,
                participants = participantMembers,
                date = exp.createdAt.toString().take(10),
                receiptUrl = exp.receiptUrl,
                note = exp.notes,
                shares = shares,
                yourShare = callerShare
            )
        }

        val splitsByExpense = allSplits.groupBy { it.expenseId }
        val pairwiseDebts = BalanceCalculator.calculatePairwiseDebts(expenses, splitsByExpense, settlements)

        val syncSettlements = pairwiseDebts.map { debt ->
            val debtorUser = users[debt.debtorId]
            val creditorUser = users[debt.creditorId]
            val debtorBal = netBalances[debt.debtorId]
            val creditorBal = netBalances[debt.creditorId]
            SyncSettlementDto(
                id = "${epic.id}_${debt.debtorId}_${debt.creditorId}",
                fromMember = SyncMemberDto(
                    id = debt.debtorId.toString(),
                    name = debtorUser?.name?.ifBlank { "User ${debt.debtorId.toString().take(4)}" } ?: "User",
                    avatarInitials = (debtorUser?.name?.take(2)?.uppercase()) ?: "FS",
                    avatarUrl = debtorUser?.avatarUrl,
                    isCurrentUser = (callerId != null && debt.debtorId == callerId),
                    totalSpent = (debtorBal?.totalPaidMinor ?: 0L) / 100.0,
                    balance = (debtorBal?.netBalanceMinor ?: 0L) / 100.0
                ),
                toMember = SyncMemberDto(
                    id = debt.creditorId.toString(),
                    name = creditorUser?.name?.ifBlank { "User ${debt.creditorId.toString().take(4)}" } ?: "User",
                    avatarInitials = creditorUser?.name?.take(2)?.uppercase() ?: "FS",
                    avatarUrl = creditorUser?.avatarUrl,
                    isCurrentUser = (callerId != null && debt.creditorId == callerId),
                    totalSpent = (creditorBal?.totalPaidMinor ?: 0L) / 100.0,
                    balance = (creditorBal?.netBalanceMinor ?: 0L) / 100.0
                ),
                amount = debt.amountMinor / 100.0,
                status = "PENDING"
            )
        }

        return SyncEpicDto(
            id = epic.id.toString(),
            name = epic.title,
            description = epic.description.orEmpty(),
            totalSpent = totalSpentMajor,
            memberCount = syncMembers.size,
            members = syncMembers,
            yourSpent = callerSpent,
            yourBalance = callerBalance,
            recentExpenses = syncExpenses,
            settlements = syncSettlements,
            inviteCode = epic.inviteCode
        )
    }

    routing {
        fun Route.syncEndpoints() {
            route("/sync") {
                // Image uploads
                route("/upload") {
                    post {
                        try {
                            val req = call.receive<SyncImageUploadRequest>()
                            val uploadsDir = File(storageConfig.uploadDir).canonicalFile
                            uploadsDir.mkdirs()
                            val rawName = File(req.fileName).name // Strips any directory traversal like ../
                            val ext = rawName.substringAfterLast('.', "jpg").lowercase().filter { it.isLetterOrDigit() }
                            val allowedExts = setOf("jpg", "jpeg", "png", "webp", "gif")
                            val safeExt = if (ext in allowedExts) ext else "jpg"
                            val cleanName = "${UUID.randomUUID()}_${System.currentTimeMillis()}.$safeExt"
                            val targetFile = File(uploadsDir, cleanName).canonicalFile
                            if (!targetFile.toPath().startsWith(uploadsDir.toPath())) {
                                call.respond(HttpStatusCode.BadRequest, ApiResponse.error("INVALID_FILENAME", "Invalid file name"))
                                return@post
                            }
                            val cleanBase64 = req.base64Data.substringAfter("base64,").trim()
                            val bytes = java.util.Base64.getDecoder().decode(cleanBase64)
                            if (bytes.size > 15 * 1024 * 1024) { // 15MB limit
                                call.respond(HttpStatusCode.PayloadTooLarge, ApiResponse.error("PAYLOAD_TOO_LARGE", "Image exceeds 15MB limit"))
                                return@post
                            }
                            targetFile.writeBytes(bytes)
                            val publicUrl = "/api/v1/sync/uploads/$cleanName"
                            call.respond(HttpStatusCode.OK, ApiResponse.success(SyncImageUploadResponse(url = publicUrl, fileName = cleanName)))
                        } catch (e: Exception) {
                            call.respond(HttpStatusCode.BadRequest, ApiResponse.error("UPLOAD_FAILED", e.message ?: "Failed to upload image"))
                        }
                    }
                }

                route("/uploads/{fileName}") {
                    get {
                        val fileName = call.parameters["fileName"] ?: ""
                        val uploadsDir = File(storageConfig.uploadDir).canonicalFile
                        val file = File(uploadsDir, File(fileName).name).canonicalFile
                        if (file.exists() && file.isFile && file.toPath().startsWith(uploadsDir.toPath())) {
                            val ext = file.extension.lowercase()
                            val contentType = when (ext) {
                                "png" -> io.ktor.http.ContentType.Image.PNG
                                "gif" -> io.ktor.http.ContentType.Image.GIF
                                "webp" -> io.ktor.http.ContentType.parse("image/webp")
                                else -> io.ktor.http.ContentType.Image.JPEG
                            }
                            call.response.header(io.ktor.http.HttpHeaders.ContentType, contentType.toString())
                            call.respondFile(file)
                        } else {
                            call.respond(HttpStatusCode.NotFound, ApiResponse.error("NOT_FOUND", "File not found"))
                        }
                    }
                }

                post("/reset-database") {
                    try {
                        com.yeshuwahane.fairsplit.infrastructure.database.dbQuery {
                            exec("TRUNCATE TABLE expense_splits, expenses, settlements, activity_logs, epic_members, epics, idempotency_keys, user_devices, user_preferences, refresh_sessions, phone_otp_challenges, auth_identities, users CASCADE;")
                        }
                        call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("status" to "ok", "message" to "All tables truncated successfully")))
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.InternalServerError, ApiResponse.error("RESET_FAILED", e.message ?: "Failed to reset database"))
                    }
                }

                fun parseOrGenerateUuid(idStr: String?): UUID {
                    if (idStr.isNullOrBlank()) return UUID.randomUUID()
                    return try {
                        UUID.fromString(idStr)
                    } catch (_: Exception) {
                        UUID.nameUUIDFromBytes(idStr.toByteArray(Charsets.UTF_8))
                    }
                }

                fun resolveCallerId(call: ApplicationCall, candidateId: String?): UUID {
                    val principal = call.principal<FairSplitPrincipal>()
                    if (principal != null) return principal.userId
                    val headerId = call.request.headers["X-User-Id"] ?: call.request.queryParameters["userId"]
                    if (!headerId.isNullOrBlank()) return parseOrGenerateUuid(headerId)
                    if (!candidateId.isNullOrBlank()) return parseOrGenerateUuid(candidateId)
                    return UUID.randomUUID()
                }

                // Authenticated sync routes (with fallback user resolution)
                authenticate("auth-jwt", optional = true) {
                    // EPICS
                    route("/epics") {
                        get {
                            val principal = call.principal<FairSplitPrincipal>()
                            val headerId = call.request.headers["X-User-Id"] ?: call.request.queryParameters["userId"]
                            val callerId = principal?.userId ?: if (!headerId.isNullOrBlank()) parseOrGenerateUuid(headerId) else null
                            val epics = if (callerId != null) {
                                epicService.listMyEpics(callerId)
                            } else {
                                emptyList()
                            }
                            val dtoList = epics.map { buildSyncEpicDto(it, callerId) }
                            call.respond(HttpStatusCode.OK, ApiResponse.success(dtoList))
                        }

                        post {
                            val req = call.receive<SyncEpicDto>()
                            val callerId = resolveCallerId(call, req.members.firstOrNull()?.id)

                            // Ensure user exists in PostgreSQL
                            if (userRepository.findById(callerId) == null) {
                                val callerName = req.members.firstOrNull { it.id == callerId.toString() || parseOrGenerateUuid(it.id) == callerId }?.name ?: "User"
                                userRepository.create(id = callerId, name = callerName)
                            }

                            // Check if an epic already exists with this inviteCode or ID to avoid duplicating
                            val existingByCode = if (req.inviteCode.isNotBlank()) {
                                try { epicService.getEpicByInviteCode(req.inviteCode) } catch (_: Exception) { null }
                            } else null
                            val existingById = if (req.id.isNotBlank()) {
                                try { epicRepository.findById(parseOrGenerateUuid(req.id)) } catch (_: Exception) { null }
                            } else null
                            val existing = existingByCode ?: existingById
                            if (existing != null) {
                                val fullDto = buildSyncEpicDto(existing, callerId)
                                return@post call.respond(HttpStatusCode.OK, ApiResponse.success(fullDto))
                            }

                            val epicResult = epicService.createEpic(
                                userId = callerId,
                                title = req.name,
                                description = req.description,
                                iconUrl = null,
                                customInviteCode = req.inviteCode.takeIf { it.isNotBlank() }
                            )
                            val created = epicResult.data

                            // Add any other initial members
                            for (m in req.members) {
                                val mId = parseOrGenerateUuid(m.id)
                                if (mId != callerId) {
                                    if (userRepository.findById(mId) == null) {
                                        userRepository.create(id = mId, name = m.name)
                                    }
                                    epicRepository.addMember(created.id, mId, EpicMemberRole.MEMBER)
                                }
                            }

                            // Post-commit publish
                            epicRealtimeBroadcaster.publish(epicResult.events)

                            val fullDto = buildSyncEpicDto(created, callerId)
                            call.respond(HttpStatusCode.OK, ApiResponse.success(fullDto))
                        }

                        get("/invite/{code}") {
                            val code = call.parameters["code"] ?: ""
                            val callerId = resolveCallerId(call, null)
                            val epic = epicService.getEpicByInviteCode(code)
                            val dto = buildSyncEpicDto(epic, callerId)
                            call.respond(HttpStatusCode.OK, ApiResponse.success(dto))
                        }

                        get("/{id}") {
                            val idStr = call.parameters["id"] ?: ""
                            val epicId = UUID.fromString(idStr)
                            val callerId = resolveCallerId(call, null)
                            val epic = epicRepository.findById(epicId)
                                ?: return@get call.respond(HttpStatusCode.NotFound, ApiResponse.error("NOT_FOUND", "Epic not found"))
                            val dto = buildSyncEpicDto(epic, callerId)
                            call.respond(HttpStatusCode.OK, ApiResponse.success(dto))
                        }

                        put("/{id}") {
                            val idStr = call.parameters["id"] ?: ""
                            val epicId = UUID.fromString(idStr)
                            val callerId = resolveCallerId(call, null).let { id ->
                                if (userRepository.findById(id) == null) {
                                    epicRepository.getMembers(epicId).firstOrNull { it.role == EpicMemberRole.OWNER }?.userId ?: id
                                } else id
                            }
                            val req = call.receive<SyncEpicDto>()
                            val result = epicService.updateEpic(callerId, epicId, req.name, req.description, null)
                            epicRealtimeBroadcaster.publish(result.events)
                            val dto = buildSyncEpicDto(result.data, callerId)
                            call.respond(HttpStatusCode.OK, ApiResponse.success(dto))
                        }

                        delete("/{id}") {
                            val idStr = call.parameters["id"] ?: ""
                            val epicId = UUID.fromString(idStr)
                            val callerId = resolveCallerId(call, null).let { id ->
                                if (userRepository.findById(id) == null) {
                                    epicRepository.getMembers(epicId).firstOrNull { it.role == EpicMemberRole.OWNER }?.userId ?: id
                                } else id
                            }
                            val result = epicService.deleteEpic(callerId, epicId)
                            if (result.data) {
                                epicRealtimeBroadcaster.publish(result.events)
                            }
                            call.respond(HttpStatusCode.OK, ApiResponse.success(result.data))
                        }

                        delete("/{id}/members/{memberId}") {
                            val epicId = UUID.fromString(call.parameters["id"] ?: "")
                            val targetMemberId = parseOrGenerateUuid(call.parameters["memberId"])
                            val callerId = resolveCallerId(call, null).let { id ->
                                if (userRepository.findById(id) == null) {
                                    epicRepository.getMembers(epicId).firstOrNull { it.role == EpicMemberRole.OWNER }?.userId ?: id
                                } else id
                            }
                            val result = epicService.removeMember(callerId, epicId, targetMemberId)
                            if (result.data) {
                                epicRealtimeBroadcaster.publish(result.events)
                            }
                            call.respond(HttpStatusCode.OK, ApiResponse.success(result.data))
                        }

                        post("/join") {
                            val req = call.receive<JoinEpicRequest>()
                            val memberId = resolveCallerId(call, req.member.id)

                            // Ensure user exists in PostgreSQL
                            if (userRepository.findById(memberId) == null) {
                                userRepository.create(id = memberId, name = req.member.name)
                            }

                            val joinResult = epicService.joinEpic(memberId, req.inviteCode)
                            val epic = joinResult.data

                            // Post-commit publish
                            epicRealtimeBroadcaster.publish(joinResult.events)

                            val dto = buildSyncEpicDto(epic, memberId)
                            call.respond(HttpStatusCode.OK, ApiResponse.success(dto))
                        }

                        // EXPENSES
                        route("/{id}/expenses") {
                            get {
                                val epicId = UUID.fromString(call.parameters["id"] ?: "")
                                val principal = call.principal<FairSplitPrincipal>()
                                val epic = epicRepository.findById(epicId)
                                    ?: return@get call.respond(HttpStatusCode.NotFound, ApiResponse.error("NOT_FOUND", "Epic not found"))
                                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200)
                                val offset = (call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L).coerceAtLeast(0L)
                                val expenses = expenseRepository.findByEpicId(epicId, limit = limit, offset = offset)
                                val splits = expenseRepository.getSplitsForEpic(epicId)
                                val members = epicRepository.getMembers(epicId).map { it.userId }.toSet()
                                val users = members.mapNotNull { userRepository.findById(it) }.associateBy { it.id }

                                val dtoList = expenses.map { exp ->
                                    val expSplits = splits.filter { it.expenseId == exp.id }
                                    val pu = users[exp.paidByUserId]
                                    SyncExpenseDto(
                                        id = exp.id.toString(),
                                        epicId = exp.epicId.toString(),
                                        epicName = epic.title,
                                        title = exp.title,
                                        amount = exp.amountMinor / 100.0,
                                        paidBy = SyncMemberDto(id = exp.paidByUserId.toString(), name = pu?.name ?: "User", avatarUrl = pu?.avatarUrl),
                                        participants = expSplits.map {
                                            val su = users[it.userId]
                                            SyncMemberDto(id = it.userId.toString(), name = su?.name ?: "User", avatarUrl = su?.avatarUrl)
                                        },
                                        shares = expSplits.map {
                                            val su = users[it.userId]
                                            SyncMemberShareDto(
                                                member = SyncMemberDto(id = it.userId.toString(), name = su?.name ?: "User", avatarUrl = su?.avatarUrl),
                                                shareAmount = it.amountMinor / 100.0
                                            )
                                        },
                                        receiptUrl = exp.receiptUrl,
                                        note = exp.notes,
                                        date = exp.createdAt.toString().take(10)
                                    )
                                }
                                call.respond(HttpStatusCode.OK, ApiResponse.success(dtoList))
                            }

                            post {
                                val epicId = UUID.fromString(call.parameters["id"] ?: "")
                                val rawJson = call.receiveText()
                                val req = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SyncExpenseDto>(rawJson)
                                val idempotencyKey = call.request.headers["Idempotency-Key"]
                                val callerId = resolveCallerId(call, req.paidBy.id)

                                var eventsToPublish = emptyList<com.yeshuwahane.fairsplit.realtime.model.RealtimeEvent>()
                                val (created, _) = idempotencyService.executeWithIdempotency<SyncExpenseDto>(
                                    userId = callerId,
                                    key = idempotencyKey,
                                    endpoint = "/sync/epics/$epicId/expenses",
                                    requestBody = rawJson,
                                    serializer = { exp -> kotlinx.serialization.json.Json.encodeToString(SyncExpenseDto.serializer(), exp) },
                                    deserializer = { json -> kotlinx.serialization.json.Json.decodeFromString(SyncExpenseDto.serializer(), json) }
                                ) {
                                    val paidByUuid = parseOrGenerateUuid(req.paidBy.id)
                                    if (userRepository.findById(paidByUuid) == null) {
                                        userRepository.create(id = paidByUuid, name = req.paidBy.name)
                                    }

                                    val participantUuids = req.participants.map { p ->
                                        val pId = parseOrGenerateUuid(p.id)
                                        if (userRepository.findById(pId) == null) {
                                            userRepository.create(id = pId, name = p.name)
                                        }
                                        pId
                                    }.ifEmpty { listOf(paidByUuid) }

                                    // Ensure all are members of the epic
                                    val currentMembers = epicRepository.getMembers(epicId).map { it.userId }.toSet()
                                    if (paidByUuid !in currentMembers) epicRepository.addMember(epicId, paidByUuid, EpicMemberRole.MEMBER)
                                    for (pId in participantUuids) {
                                        if (pId !in currentMembers) epicRepository.addMember(epicId, pId, EpicMemberRole.MEMBER)
                                    }

                                    val amountMinor = Math.round(req.amount * 100.0)

                                    val splitDetails = if (req.shares.isNotEmpty()) {
                                        req.shares.mapNotNull { sh ->
                                            try {
                                                val sId = parseOrGenerateUuid(sh.member.id)
                                                ExpenseService.SplitDetail(
                                                    userId = sId,
                                                    exactAmountMinor = Math.round(sh.shareAmount * 100.0)
                                                )
                                            } catch (_: Exception) { null }
                                        }
                                    } else emptyList()

                                    val splitType = if (splitDetails.isNotEmpty() && splitDetails.sumOf { it.exactAmountMinor ?: 0L } == amountMinor) {
                                        SplitType.EXACT
                                    } else {
                                        SplitType.EQUAL
                                    }

                                    val result = expenseService.createExpense(
                                        callerId = callerId,
                                        epicId = epicId,
                                        title = req.title,
                                        amountMinor = amountMinor,
                                        paidByUserId = paidByUuid,
                                        splitType = splitType,
                                        participantIds = participantUuids,
                                        splitDetails = splitDetails,
                                        receiptUrl = req.receiptUrl,
                                        notes = req.note
                                    )

                                    eventsToPublish = result.events
                                    req.copy(id = result.data.id.toString())
                                }

                                // Post-commit publish
                                if (eventsToPublish.isNotEmpty()) {
                                    epicRealtimeBroadcaster.publish(eventsToPublish)
                                }

                                call.respond(HttpStatusCode.OK, ApiResponse.success(created))
                            }

                            delete("/{expenseId}") {
                                val epicId = UUID.fromString(call.parameters["id"] ?: "")
                                val expenseId = UUID.fromString(call.parameters["expenseId"] ?: "")
                                val callerId = resolveCallerId(call, null)

                                val deleteResult = expenseService.deleteExpense(callerId, epicId, expenseId)
                                if (deleteResult.data) {
                                    epicRealtimeBroadcaster.publish(deleteResult.events)
                                }
                                call.respond(HttpStatusCode.OK, ApiResponse.success(deleteResult.data))
                            }
                        }
                    }

                    // TRANSACTIONS / SETTLEMENTS
                    route("/transactions") {
                        get {
                            val epicIdParam = call.request.queryParameters["epicId"]
                            val settlements = if (!epicIdParam.isNullOrBlank()) {
                                val epicId = try { UUID.fromString(epicIdParam) } catch (_: Exception) { null }
                                if (epicId != null) settlementRepository.findByEpicId(epicId, limit = 100) else emptyList()
                            } else {
                                val callerId = resolveCallerId(call, null)
                                val myEpics = epicRepository.findEpicsByUserId(callerId)
                                myEpics.flatMap { settlementRepository.findByEpicId(it.id, limit = 20) }
                                    .sortedByDescending { it.createdAt }
                                    .take(100)
                            }

                            val dtoList = settlements.map { set ->
                                val epic = epicRepository.findById(set.epicId)
                                val fromUser = userRepository.findById(set.fromUserId)
                                val toUser = userRepository.findById(set.toUserId)
                                SyncTransactionDto(
                                    id = set.id.toString(),
                                    title = "${fromUser?.name ?: "Member"} paid ${toUser?.name ?: "Member"}",
                                    subtitle = set.notes.orEmpty(),
                                    amount = set.amountMinor / 100.0,
                                    epicName = epic?.title.orEmpty(),
                                    date = set.createdAt.toString().take(10),
                                    time = set.createdAt.toString().take(16).replace("T", " "),
                                    status = set.status,
                                    fromMember = SyncMemberDto(id = set.fromUserId.toString(), name = fromUser?.name ?: "User", avatarUrl = fromUser?.avatarUrl),
                                    toMember = SyncMemberDto(id = set.toUserId.toString(), name = toUser?.name ?: "User", avatarUrl = toUser?.avatarUrl),
                                    epicId = set.epicId.toString()
                                )
                            }
                            call.respond(HttpStatusCode.OK, ApiResponse.success(dtoList))
                        }

                        post {
                            val rawJson = call.receiveText()
                            val req = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SyncTransactionDto>(rawJson)
                            val idempotencyKey = call.request.headers["Idempotency-Key"]

                            val fromId = parseOrGenerateUuid(req.fromMember.id)
                            val toId = parseOrGenerateUuid(req.toMember.id)
                            val epicId = try { UUID.fromString(req.epicId) } catch (_: Exception) {
                                val epics1 = epicRepository.findEpicsByUserId(fromId).map { it.id }.toSet()
                                val epics2 = epicRepository.findEpicsByUserId(toId).map { it.id }.toSet()
                                (epics1 intersect epics2).firstOrNull() ?: UUID.randomUUID()
                            }

                            val callerId = resolveCallerId(call, req.fromMember.id)

                            if (userRepository.findById(fromId) == null) userRepository.create(id = fromId, name = req.fromMember.name)
                            if (userRepository.findById(toId) == null) userRepository.create(id = toId, name = req.toMember.name)

                            val amountMinor = Math.round(req.amount * 100.0)

                            val (saved, _) = idempotencyService.executeWithIdempotency(
                                userId = callerId,
                                key = idempotencyKey,
                                endpoint = "/sync/transactions",
                                requestBody = rawJson,
                                serializer = { tx -> kotlinx.serialization.json.Json.encodeToString(SyncTransactionDto.serializer(), tx) },
                                deserializer = { json -> kotlinx.serialization.json.Json.decodeFromString(SyncTransactionDto.serializer(), json) }
                            ) {
                                val setResult = settlementService.createSettlement(
                                    callerId = callerId,
                                    epicId = epicId,
                                    fromUserId = fromId,
                                    toUserId = toId,
                                    amountMinor = amountMinor,
                                    method = "CASH",
                                    notes = req.subtitle
                                )
                                // Post-commit publish
                                epicRealtimeBroadcaster.publish(setResult.events)
                                req.copy(id = setResult.data.id.toString(), status = setResult.data.status)
                            }

                            call.respond(HttpStatusCode.OK, ApiResponse.success(saved))
                        }

                        put("/{transactionId}") {
                            val txIdStr = call.parameters["transactionId"] ?: ""
                            val settlementId = UUID.fromString(txIdStr)
                            val rawJson = call.receiveText()
                            val req = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SyncTransactionDto>(rawJson)
                            val callerId = resolveCallerId(call, req.fromMember.id)
                            val epicId = try { UUID.fromString(req.epicId) } catch (_: Exception) {
                                settlementRepository.findById(settlementId)?.epicId
                                    ?: return@put call.respond(HttpStatusCode.NotFound, ApiResponse.error("NOT_FOUND", "Settlement not found"))
                            }
                            val newAmountMinor = Math.round(req.amount * 100.0)

                            val updateResult = settlementService.updateSettlementAmount(
                                callerId = callerId,
                                epicId = epicId,
                                settlementId = settlementId,
                                newAmountMinor = newAmountMinor,
                                notes = req.subtitle
                            )
                            epicRealtimeBroadcaster.publish(updateResult.events)
                            val s = updateResult.data
                            val fromUser = userRepository.findById(s.fromUserId)
                            val toUser = userRepository.findById(s.toUserId)
                            val epic = epicRepository.findById(s.epicId)
                            val dto = SyncTransactionDto(
                                id = s.id.toString(),
                                title = "${fromUser?.name ?: "Member"} paid ${toUser?.name ?: "Member"}",
                                subtitle = s.notes.orEmpty(),
                                amount = s.amountMinor / 100.0,
                                epicName = epic?.title.orEmpty(),
                                date = s.createdAt.toString().take(10),
                                time = s.createdAt.toString().take(16).replace("T", " "),
                                status = s.status,
                                fromMember = SyncMemberDto(id = s.fromUserId.toString(), name = fromUser?.name ?: "User", avatarUrl = fromUser?.avatarUrl),
                                toMember = SyncMemberDto(id = s.toUserId.toString(), name = toUser?.name ?: "User", avatarUrl = toUser?.avatarUrl),
                                epicId = s.epicId.toString()
                            )
                            call.respond(HttpStatusCode.OK, ApiResponse.success(dto))
                        }

                        delete("/{transactionId}") {
                            val txIdStr = call.parameters["transactionId"] ?: ""
                            val settlementId = UUID.fromString(txIdStr)
                            val existing = settlementRepository.findById(settlementId)
                                ?: return@delete call.respond(HttpStatusCode.NotFound, ApiResponse.error("NOT_FOUND", "Settlement not found"))
                            val callerId = resolveCallerId(call, existing.fromUserId.toString())

                            val voidResult = settlementService.voidSettlement(
                                callerId = callerId,
                                epicId = existing.epicId,
                                settlementId = settlementId
                            )
                            epicRealtimeBroadcaster.publish(voidResult.events)
                            call.respond(HttpStatusCode.OK, ApiResponse.success(true))
                        }
                    }

                    // ACTIVITIES
                    route("/activities") {
                        get {
                            val epicIdParam = call.request.queryParameters["epicId"]
                            val activities = if (!epicIdParam.isNullOrBlank()) {
                                val epicId = try { UUID.fromString(epicIdParam) } catch (_: Exception) { null }
                                if (epicId != null) activityRepository.findByEpicId(epicId, limit = 100) else emptyList()
                            } else {
                                val callerId = resolveCallerId(call, null)
                                val myEpics = epicRepository.findEpicsByUserId(callerId)
                                myEpics.flatMap { activityRepository.findByEpicId(it.id, limit = 20) }
                                    .sortedByDescending { it.createdAt }
                                    .take(100)
                            }

                            val epicMap = mutableMapOf<UUID, String>()
                            val userMap = mutableMapOf<UUID, com.yeshuwahane.fairsplit.user.model.User?>()

                            val dtoList = activities.map { act ->
                                val epicTitle = epicMap.getOrPut(act.epicId) {
                                    epicRepository.findById(act.epicId)?.title ?: "Group"
                                }
                                val user = userMap.getOrPut(act.actorId) {
                                    userRepository.findById(act.actorId)
                                }
                                val actorDto = SyncMemberDto(
                                    id = act.actorId.toString(),
                                    name = user?.name ?: "User",
                                    avatarUrl = user?.avatarUrl,
                                    avatarInitials = user?.name?.take(2)?.uppercase() ?: "US"
                                )
                                val mappedType = when (act.actionType) {
                                    "MEMBER_JOINED" -> "MEMBER_ADDED"
                                    else -> act.actionType
                                }
                                val title = when (act.actionType) {
                                    "EPIC_CREATED" -> "Group Created"
                                    "MEMBER_JOINED", "MEMBER_ADDED" -> "Member Joined"
                                    "MEMBER_REMOVED" -> "Member Removed"
                                    "EPIC_UPDATED" -> "Group Updated"
                                    "EXPENSE_ADDED" -> "Expense Added"
                                    "EXPENSE_UPDATED" -> "Expense Updated"
                                    "EXPENSE_DELETED" -> "Expense Deleted"
                                    "SETTLEMENT_RECORDED" -> "Settlement Completed"
                                    else -> act.actionType.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                                }
                                SyncActivityDto(
                                    id = act.id.toString(),
                                    epicId = act.epicId.toString(),
                                    epicName = epicTitle,
                                    type = mappedType,
                                    title = title,
                                    description = if (act.description.startsWith(user?.name ?: "")) act.description else "${user?.name ?: "User"} ${act.description}",
                                    actor = actorDto,
                                    timestamp = act.createdAt.toString().take(16).replace("T", " "),
                                    date = act.createdAt.toString().take(10)
                                )
                            }
                            call.respond(HttpStatusCode.OK, ApiResponse.success(dtoList))
                        }

                        post {
                            val req = call.receive<SyncActivityDto>()
                            call.respond(HttpStatusCode.OK, ApiResponse.success(req))
                        }
                    }
                }
            }
        }

        route("/api/v1") {
            syncEndpoints()
        }
        syncEndpoints()
    }
}

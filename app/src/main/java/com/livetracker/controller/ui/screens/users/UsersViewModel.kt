package com.livetracker.controller.ui.screens.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livetracker.controller.core.auth.domain.AccountStatus
import com.livetracker.controller.core.auth.domain.Role
import com.livetracker.controller.core.auth.domain.repository.SessionRepository
import com.livetracker.controller.core.tracker.supabase.DeviceAssignmentRecord
import com.livetracker.controller.core.tracker.supabase.DeviceFeaturePermissionRecord
import com.livetracker.controller.core.tracker.supabase.DeviceRecord
import com.livetracker.controller.core.tracker.supabase.SupabaseFleetManager
import com.livetracker.controller.core.tracker.supabase.UserProfileRecord
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class UserFilter {
    ALL,
    VISITORS,
    ACTIVE,
    SUSPENDED_DISABLED
}

data class UserDeviceAssignmentUiState(
    val assignmentId: Long,
    val deviceId: String,
    val deviceName: String,
    val features: Map<String, Boolean>
)

data class UserItemUiState(
    val userId: String,
    val displayName: String,
    val email: String,
    val role: Role,
    val status: AccountStatus,
    val assignedDevices: List<UserDeviceAssignmentUiState> = emptyList(),
    val activePermissionsCount: Int = 0,
    val isPending: Boolean = role == Role.VISITOR || status == AccountStatus.PENDING,
    val isVisitor: Boolean = role == Role.VISITOR,
    val isOwner: Boolean = role == Role.OWNER,
    val avatarUrl: String? = null
) {
    val assignedDevicesCount: Int get() = assignedDevices.size
    val isAdmin: Boolean get() = role == Role.ADMIN || role == Role.OWNER
}

data class UsersUiState(
    val isLoading: Boolean = false,
    val currentUserRole: Role = Role.USER,
    val currentUserId: String? = null,
    val isVisitor: Boolean = false,
    val isAccessDenied: Boolean = false,
    val selectedFilter: UserFilter = UserFilter.ALL,
    val searchQuery: String = "",
    val users: List<UserItemUiState> = emptyList(),
    val totalCount: Int = 0,
    val visitorsCount: Int = 0,
    val pendingCount: Int = 0,
    val activeCount: Int = 0,
    val suspendedDisabledCount: Int = 0,
    val fleetDevices: List<DeviceRecord> = emptyList(),
    val selectedUserForManagement: UserItemUiState? = null,
    val userToConfirmStatusChange: Pair<UserItemUiState, AccountStatus>? = null,
    val userToConfirmDelete: UserItemUiState? = null,
    val isAssigningDevice: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

class UsersViewModel(
    private val sessionRepository: SessionRepository,
    private val supabaseFleetManager: SupabaseFleetManager
) : ViewModel() {

    companion object {
        val ALL_FEATURES = listOf("LOCATION", "CAMERA", "FILES", "MIC", "SCREEN", "COMMANDS")
    }

    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilter = MutableStateFlow(UserFilter.ALL)
    private val _profiles = MutableStateFlow<List<UserProfileRecord>>(emptyList())
    private val _assignments = MutableStateFlow<List<DeviceAssignmentRecord>>(emptyList())
    private val _permissions = MutableStateFlow<List<DeviceFeaturePermissionRecord>>(emptyList())
    private val _fleetDevices = MutableStateFlow<List<DeviceRecord>>(emptyList())
    private val _selectedUserIdForManagement = MutableStateFlow<String?>(null)
    private val _userToConfirmStatusChange = MutableStateFlow<Pair<UserItemUiState, AccountStatus>?>(null)
    private val _userToConfirmDelete = MutableStateFlow<UserItemUiState?>(null)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(false)
    private val _isAssigningDevice = MutableStateFlow(false)

    // Intermediate flow combining raw records into UI user models
    private val userItemsFlow = combine(
        _profiles,
        _assignments,
        _permissions,
        _fleetDevices
    ) { profiles, assignments, permissions, devices ->
        val deviceMap = devices.associateBy { it.deviceId }
        val permissionsByAssignment = permissions.groupBy { it.assignmentId }
        val assignmentsByUser = assignments.filter { it.status == "ACTIVE" }.groupBy { it.userId }

        profiles.map { profile ->
            val role = runCatching { Role.valueOf(profile.role.trim().uppercase()) }.getOrDefault(Role.USER)
            val status = runCatching { AccountStatus.valueOf(profile.status.trim().uppercase()) }.getOrDefault(AccountStatus.PENDING)

            val effectiveAssignments = if (role == Role.ADMIN || role == Role.OWNER) {
                // Admins & Owners inherently possess access to all fleet devices with full permissions
                devices.mapIndexed { idx, dev ->
                    UserDeviceAssignmentUiState(
                        assignmentId = -(idx.toLong() + 1L),
                        deviceId = dev.deviceId,
                        deviceName = dev.name.ifBlank { dev.deviceId },
                        features = ALL_FEATURES.associateWith { true }
                    )
                }
            } else {
                assignmentsByUser[profile.id].orEmpty().mapNotNull { asgn ->
                    val asgnId = asgn.id ?: (asgn.userId.hashCode().toLong() shl 16 xor (asgn.deviceId.hashCode().toLong() and 0xFFFFL))
                    val devRecord = deviceMap[asgn.deviceId]
                    val devName = devRecord?.name?.ifBlank { asgn.deviceId } ?: asgn.deviceId

                    val asgnPerms = permissionsByAssignment[asgnId].orEmpty()
                    val featureMap = ALL_FEATURES.associateWith { feat ->
                        asgnPerms.find { it.feature.equals(feat, ignoreCase = true) }?.isAllowed ?: (feat == "LOCATION")
                    }

                    UserDeviceAssignmentUiState(
                        assignmentId = asgnId,
                        deviceId = asgn.deviceId,
                        deviceName = devName,
                        features = featureMap
                    )
                }
            }

            val activePermCount = if (role == Role.ADMIN || role == Role.OWNER) {
                effectiveAssignments.size * ALL_FEATURES.size
            } else {
                effectiveAssignments.sumOf { asgn -> asgn.features.count { it.value } }
            }

            UserItemUiState(
                userId = profile.id,
                displayName = profile.displayName.ifBlank { profile.email ?: profile.id },
                email = profile.email ?: "",
                role = role,
                status = status,
                assignedDevices = effectiveAssignments,
                activePermissionsCount = activePermCount,
                avatarUrl = profile.avatarUrl
            )
        }
    }

    // Filtered users flow based on search query and category chips
    private val filteredUsersFlow = combine(
        userItemsFlow,
        _searchQuery,
        _selectedFilter
    ) { allUsers, query, filter ->
        val totalCount = allUsers.size
        val visitorsCount = allUsers.count { it.role == Role.VISITOR }
        val pendingCount = allUsers.count { it.status == AccountStatus.PENDING || it.role == Role.VISITOR }
        val activeCount = allUsers.count { it.status == AccountStatus.ACTIVE && it.role != Role.VISITOR }
        val suspendedDisabledCount = allUsers.count {
            it.status == AccountStatus.SUSPENDED || it.status == AccountStatus.DISABLED
        }

        val filteredByQuery = if (query.isBlank()) {
            allUsers
        } else {
            val q = query.trim().lowercase()
            allUsers.filter {
                it.displayName.lowercase().contains(q) ||
                it.email.lowercase().contains(q) ||
                it.userId.lowercase().contains(q)
            }
        }

        val filteredUsers = when (filter) {
            UserFilter.ALL -> filteredByQuery
            UserFilter.VISITORS -> filteredByQuery.filter { it.role == Role.VISITOR }
            UserFilter.ACTIVE -> filteredByQuery.filter { it.status == AccountStatus.ACTIVE && it.role != Role.VISITOR }
            UserFilter.SUSPENDED_DISABLED -> filteredByQuery.filter {
                it.status == AccountStatus.SUSPENDED || it.status == AccountStatus.DISABLED
            }
        }

        Triple(filteredUsers, listOf(totalCount, visitorsCount, pendingCount, activeCount, suspendedDisabledCount), Pair(query, filter))
    }

    private data class DialogStateTuple(
        val selectedUserId: String?,
        val userToConfirm: Pair<UserItemUiState, AccountStatus>?,
        val userToConfirmDelete: UserItemUiState?,
        val statusMsg: String?,
        val errMsg: String?,
        val loading: Boolean,
        val assigning: Boolean
    )

    private val dialogStateFlow = combine(
        combine(_selectedUserIdForManagement, _userToConfirmStatusChange, _userToConfirmDelete, ::Triple),
        combine(_statusMessage, _errorMessage, ::Pair),
        combine(_isLoading, _isAssigningDevice, ::Pair)
    ) { (selUser, confirmUser, confirmDelete), (statusMsg, errMsg), (loading, assigning) ->
        DialogStateTuple(selUser, confirmUser, confirmDelete, statusMsg, errMsg, loading, assigning)
    }

    val uiState: StateFlow<UsersUiState> = combine(
        filteredUsersFlow,
        sessionRepository.observeActiveSession(),
        _fleetDevices,
        dialogStateFlow
    ) { (filteredUsers, counts, filterPair), activeSession, fleetDevices, dialogTuple ->
        val userRole = activeSession?.user?.role ?: Role.VISITOR
        val currentUserId = activeSession?.user?.id
        val isVisitor = userRole == Role.VISITOR
        val isAccessDenied = isVisitor || userRole == Role.USER

        val (query, filter) = filterPair
        val totalCount = counts[0]
        val visitorsCount = counts[1]
        val pendingCount = counts[2]
        val activeCount = counts[3]
        val suspendedDisabledCount = counts[4]

        // Strict RBAC gate: Ordinary users and Visitors cannot observe other user accounts
        val visibleUsers = if (isAccessDenied) emptyList() else filteredUsers

        val selectedUserForManage = dialogTuple.selectedUserId?.let { id ->
            visibleUsers.find { it.userId == id }
        }

        UsersUiState(
            isLoading = dialogTuple.loading,
            currentUserRole = userRole,
            currentUserId = currentUserId,
            isVisitor = isVisitor,
            isAccessDenied = isAccessDenied,
            selectedFilter = filter,
            searchQuery = query,
            users = visibleUsers,
            totalCount = if (isAccessDenied) 0 else totalCount,
            visitorsCount = if (isAccessDenied) 0 else visitorsCount,
            pendingCount = if (isAccessDenied) 0 else pendingCount,
            activeCount = if (isAccessDenied) 0 else activeCount,
            suspendedDisabledCount = if (isAccessDenied) 0 else suspendedDisabledCount,
            fleetDevices = fleetDevices,
            selectedUserForManagement = selectedUserForManage,
            userToConfirmStatusChange = dialogTuple.userToConfirm,
            userToConfirmDelete = dialogTuple.userToConfirmDelete,
            isAssigningDevice = dialogTuple.assigning,
            statusMessage = dialogTuple.statusMsg,
            errorMessage = dialogTuple.errMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UsersUiState(isLoading = true)
    )

    init {
        refresh()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onFilterSelected(filter: UserFilter) {
        _selectedFilter.value = filter
    }

    fun onOpenManageUser(user: UserItemUiState) {
        _selectedUserIdForManagement.value = user.userId
        _statusMessage.value = null
        _errorMessage.value = null
    }

    fun onDismissManageUser() {
        _selectedUserIdForManagement.value = null
    }

    fun onDismissMessage() {
        _statusMessage.value = null
        _errorMessage.value = null
    }

    /**
     * One-click Quick Action: Promotes VISITOR account to USER role and opens Manage User modal.
     */
    fun onPromoteVisitorToUser(user: UserItemUiState) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                _errorMessage.value = "Only Admins and Owners can promote accounts"
                return@launch
            }

            _isLoading.value = true
            try {
                val result = supabaseFleetManager.approveUserAccount(user.userId)
                if (result.isSuccess) {
                    _statusMessage.value = "Promoted '${user.displayName}' to User"
                    loadData()
                    onOpenManageUser(user.copy(role = Role.USER))
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to promote user"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Promotion failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * One-click Quick Action: Promotes PENDING account to ACTIVE.
     */
    fun onApproveUser(userId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                _errorMessage.value = "Only Admins and Owners can approve accounts"
                return@launch
            }

            _isLoading.value = true
            try {
                val result = supabaseFleetManager.approveUserAccount(userId)
                if (result.isSuccess) {
                    _statusMessage.value = "User approved successfully"
                    loadData()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to approve user"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Approval failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Changes a user's role while enforcing strict hierarchical boundaries:
     * - Admin cannot promote anyone to Owner.
     * - Admin cannot demote or alter an Owner.
     */
    fun onChangeRole(targetUserId: String, newRole: Role) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                _errorMessage.value = "Unauthorized: Only Admins and Owners can change roles"
                return@launch
            }

            val targetProfile = _profiles.value.find { it.id == targetUserId }
            val currentTargetRole = runCatching { Role.valueOf(targetProfile?.role.orEmpty().uppercase()) }.getOrDefault(Role.USER)

            // Strict Role Boundary Checks
            if (session.user.id == targetUserId && newRole != session.user.role) {
                _errorMessage.value = "You cannot modify your own role"
                return@launch
            }

            if (session.user.role == Role.ADMIN) {
                if (currentTargetRole == Role.OWNER || currentTargetRole == Role.ADMIN) {
                    _errorMessage.value = "Administrator cannot modify Fleet Owner privileges"
                    return@launch
                }
                if (newRole == Role.OWNER) {
                    _errorMessage.value = "Administrator cannot promote users to Fleet Owner"
                    return@launch
                }
                if (currentTargetRole != Role.VISITOR || newRole != Role.USER) {
                    _errorMessage.value = "Administrators may not promote or alter roles (only Visitor to User promotion allowed)"
                    return@launch
                }
            }

            _isLoading.value = true
            try {
                val result = supabaseFleetManager.updateUserRole(targetUserId, newRole.name)
                if (result.isSuccess) {
                    _statusMessage.value = "Role updated to ${newRole.name}"
                    loadData()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to update role"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Role update failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Requests a status change. If SUSPENDED or DISABLED, triggers confirmation alert.
     */
    fun onRequestStatusChange(user: UserItemUiState, newStatus: AccountStatus) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull() ?: return@launch
            if (session.user.role != Role.OWNER) {
                _errorMessage.value = "Unauthorized: Only Fleet Owners can modify account status"
                return@launch
            }

            if (session.user.id == user.userId && (newStatus == AccountStatus.SUSPENDED || newStatus == AccountStatus.DISABLED)) {
                _errorMessage.value = "You cannot suspend or disable your own active account"
                return@launch
            }

            if (newStatus == AccountStatus.SUSPENDED || newStatus == AccountStatus.DISABLED) {
                _userToConfirmStatusChange.value = Pair(user, newStatus)
            } else {
                executeStatusChange(user.userId, newStatus)
            }
        }
    }

    fun onConfirmStatusChange() {
        val tuple = _userToConfirmStatusChange.value ?: return
        _userToConfirmStatusChange.value = null
        executeStatusChange(tuple.first.userId, tuple.second)
    }

    fun onCancelStatusChange() {
        _userToConfirmStatusChange.value = null
    }

    private fun executeStatusChange(userId: String, newStatus: AccountStatus) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = supabaseFleetManager.updateUserStatus(userId, newStatus.name)
                if (result.isSuccess) {
                    _statusMessage.value = "Status updated to ${newStatus.name}"
                    loadData()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to update status"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Status change failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Requests user account deletion. Strictly restricted to OWNER.
     * Prevents self-deletion and deletion of OWNER accounts.
     */
    fun onRequestDeleteUser(user: UserItemUiState) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull() ?: return@launch
            if (session.user.role != Role.OWNER) {
                _errorMessage.value = "Unauthorized: Only Fleet Owner can delete user accounts"
                return@launch
            }

            if (session.user.id == user.userId) {
                _errorMessage.value = "You cannot delete your own account"
                return@launch
            }

            if (user.role == Role.OWNER) {
                _errorMessage.value = "Owner accounts cannot be deleted"
                return@launch
            }

            _userToConfirmDelete.value = user
        }
    }

    fun onConfirmDeleteUser() {
        val user = _userToConfirmDelete.value ?: return
        _userToConfirmDelete.value = null
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = supabaseFleetManager.deleteUserAccount(user.userId)
                if (result.isSuccess) {
                    _statusMessage.value = "User ${user.displayName} deleted successfully"
                    _selectedUserIdForManagement.value = null
                    loadData()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to delete user account"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "User deletion failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onDismissDeleteUser() {
        _userToConfirmDelete.value = null
    }

    /**
     * Assigns a fleet device to a user.
     */
    fun onAssignDevice(userId: String, deviceId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                _errorMessage.value = "Only Admins and Owners can assign devices"
                return@launch
            }

            _isAssigningDevice.value = true
            try {
                val result = supabaseFleetManager.assignDeviceToUser(deviceId, userId)
                if (result.isSuccess) {
                    _statusMessage.value = "Device '$deviceId' assigned"
                    loadAssignmentsAndPermissions()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to assign device"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Assignment failed"
            } finally {
                _isAssigningDevice.value = false
            }
        }
    }

    /**
     * Unassigns a fleet device from a user.
     */
    fun onUnassignDevice(userId: String, deviceId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                _errorMessage.value = "Only Admins and Owners can unassign devices"
                return@launch
            }

            _isAssigningDevice.value = true
            try {
                val result = supabaseFleetManager.unassignDeviceFromUser(deviceId, userId)
                if (result.isSuccess) {
                    _statusMessage.value = "Device '$deviceId' unassigned"
                    loadAssignmentsAndPermissions()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to unassign device"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unassignment failed"
            } finally {
                _isAssigningDevice.value = false
            }
        }
    }

    /**
     * Toggles a granular feature permission (LOCATION, CAMERA, FILES, MIC, SCREEN, COMMANDS).
     */
    fun onToggleFeaturePermission(
        assignmentId: Long,
        feature: String,
        isAllowed: Boolean,
        userId: String? = null,
        deviceId: String? = null
    ) {
        viewModelScope.launch {
            val session = sessionRepository.getActiveSession().getOrNull()
            if (session == null || (session.user.role != Role.OWNER && session.user.role != Role.ADMIN)) {
                _errorMessage.value = "Only Admins and Owners can configure feature permissions"
                return@launch
            }

            try {
                val result = supabaseFleetManager.updateDeviceFeaturePermission(
                    assignmentId = assignmentId,
                    feature = feature,
                    isAllowed = isAllowed,
                    userId = userId,
                    deviceId = deviceId
                )
                if (result.isSuccess) {
                    loadAssignmentsAndPermissions()
                } else {
                    _errorMessage.value = result.exceptionOrNull()?.message ?: "Failed to update permission"
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Permission update failed"
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            loadData()
            _isLoading.value = false
        }
    }

    private suspend fun loadData() {
        val session = sessionRepository.getActiveSession().getOrNull() ?: return
        if (session.user.role == Role.VISITOR || session.user.role == Role.USER) return

        try {
            val profiles = supabaseFleetManager.fetchAllProfiles()
            _profiles.value = profiles

            val devices = supabaseFleetManager.fetchFleetDevices()
            _fleetDevices.value = devices

            loadAssignmentsAndPermissions()
        } catch (_: Exception) {}
    }

    private suspend fun loadAssignmentsAndPermissions() {
        try {
            val assignments = supabaseFleetManager.fetchDeviceAssignments()
            _assignments.value = assignments

            val permissions = supabaseFleetManager.fetchDeviceFeaturePermissions()
            _permissions.value = permissions
        } catch (_: Exception) {}
    }
}

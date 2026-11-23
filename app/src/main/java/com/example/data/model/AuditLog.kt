package com.example.data.model

data class AuditLog(
    val auditId: String = "",
    val adminUid: String = "",
    val adminName: String = "",
    val adminEmail: String = "",
    val targetUid: String = "",
    val targetName: String = "",
    val targetEmail: String = "",
    val action: String = "", // BLOCK_MEMBER, UNBLOCK_MEMBER, REMOVE_MEMBER, ROLE_CHANGE
    val reason: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val ACTION_BLOCK_MEMBER = "BLOCK_MEMBER"
        const val ACTION_UNBLOCK_MEMBER = "UNBLOCK_MEMBER"
        const val ACTION_REMOVE_MEMBER = "REMOVE_MEMBER"
        const val ACTION_PURGE_MEMBER = "PURGE_MEMBER"
        const val ACTION_ROLE_CHANGE = "ROLE_CHANGE"
        const val ACTION_APPROVE_USER = "APPROVE_USER"
        const val ACTION_RESET_RIDES_ONLY = "RESET_RIDES_ONLY"
        const val ACTION_RESET_ALL_EXCEPT_CURRENT_ADMIN = "RESET_ALL_EXCEPT_CURRENT_ADMIN"
        const val ACTION_RESET_ALL_EXCEPT_ADMINS = "RESET_ALL_EXCEPT_ADMINS"
        const val ACTION_DELETE_USER_DATA_ONLY = "DELETE_USER_DATA_ONLY"
        const val ACTION_DELETE_USER_AND_ACCOUNT = "DELETE_USER_AND_ACCOUNT"
    }
}

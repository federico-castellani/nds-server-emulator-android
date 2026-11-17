package io.github.federicocastellani.ndsstation.data

data class GameInfo(val id: String, val name: String, val url: String?)

data class AdminUser(val id: Int, val username: String, val isAdmin: Boolean, val mustChangePassword: Boolean)

data class SearchUser(val id: Int, val username: String)

/** A player's save as the admin sees it; [updatedAt] is ISO 8601. */
data class AdminSave(val gameId: String, val title: String, val size: Long, val updatedAt: String?)

data class LoginResult(val token: String, val mustChangePassword: Boolean, val isAdmin: Boolean)

class ApiException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : Exception(message, cause) {
    val isUnauthorized: Boolean get() = statusCode == 401 || statusCode == 403

    /** The server holds a newer version than the one this upload was based on. */
    val isConflict: Boolean get() = statusCode == 409
}

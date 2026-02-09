package cz.example.horsetracker.permissions

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object PermissionRepository {
    private val _hasLocation = MutableStateFlow(false)
    val hasLocation = _hasLocation.asStateFlow()

    private val _hasBackgroundLocation = MutableStateFlow(false)
    val hasBackgroundLocation = _hasBackgroundLocation.asStateFlow()

    fun refresh(context: Context) {
        _hasLocation.value = Permissions.hasFineOrCoarseLocation(context)
        _hasBackgroundLocation.value = Permissions.hasBackgroundLocation(context)
    }
}


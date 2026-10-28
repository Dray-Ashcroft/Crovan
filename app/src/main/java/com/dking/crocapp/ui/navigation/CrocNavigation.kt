package com.dking.crocapp.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.ui.graphics.vector.ImageVector
import com.dking.crocapp.R

sealed class CrocDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Send : CrocDestination(
        route = "send",
        labelRes = R.string.nav_send,
        selectedIcon = Icons.Filled.Upload,
        unselectedIcon = Icons.Outlined.Upload
    )

    data object Receive : CrocDestination(
        route = "receive",
        labelRes = R.string.nav_receive,
        selectedIcon = Icons.Filled.Download,
        unselectedIcon = Icons.Outlined.Download
    )

    data object History : CrocDestination(
        route = "history",
        labelRes = R.string.nav_history,
        selectedIcon = Icons.Filled.Download, // not used in bottom nav
        unselectedIcon = Icons.Outlined.Download
    )

    data object Settings : CrocDestination(
        route = "settings",
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Download, // not used in bottom nav
        unselectedIcon = Icons.Outlined.Download
    )

    data object Guide : CrocDestination(
        route = "guide",
        labelRes = R.string.guide_title,
        selectedIcon = Icons.Filled.Download, // not used in bottom nav
        unselectedIcon = Icons.Outlined.Download
    )

    companion object {
        /** Send and Receive appear in the bottom navigation */
        val bottomNavItems = listOf(Send, Receive)
    }
}

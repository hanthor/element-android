/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package im.vector.app.features.roomprofile.notifications

import im.vector.app.core.platform.VectorViewEvents

sealed class RoomNotificationSettingsViewEvents : VectorViewEvents {
    data class Failure(val throwable: Throwable) : RoomNotificationSettingsViewEvents()
}

/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package im.vector.app.features.home

import im.vector.app.core.platform.VectorViewEvents

sealed class HomeDetailViewEvents : VectorViewEvents {
    object Loading : HomeDetailViewEvents()
    object CallStarted : HomeDetailViewEvents()
    data class FailToCall(val failure: Throwable) : HomeDetailViewEvents()
}

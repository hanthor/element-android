/*
 * Copyright 2023, 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package im.vector.app.features.home.room.threads.list.viewmodel

import im.vector.app.core.platform.VectorViewModelAction

sealed interface ThreadListViewActions : VectorViewModelAction {
    object TryAgain : ThreadListViewActions
}

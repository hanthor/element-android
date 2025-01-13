/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package im.vector.app.features.terms

data class Term(
        val url: String,
        val name: String,
        val version: String? = null,
        val accepted: Boolean = false
)

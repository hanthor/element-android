/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE in the repository root for full details.
 */

package im.vector.app.features.voicebroadcast.usecase

import im.vector.app.features.voicebroadcast.VoiceBroadcastConstants
import im.vector.app.features.voicebroadcast.model.MessageVoiceBroadcastInfoContent
import im.vector.app.features.voicebroadcast.model.VoiceBroadcastState
import im.vector.app.features.voicebroadcast.recording.usecase.ResumeVoiceBroadcastUseCase
import im.vector.app.test.fakes.FakeRoom
import im.vector.app.test.fakes.FakeRoomService
import im.vector.app.test.fakes.FakeSession
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBe
import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent

private const val A_ROOM_ID = "room_id"
private const val AN_EVENT_ID = "event_id"
private const val A_STARTED_VOICE_BROADCAST_EVENT_ID = "a_started_voice_broadcast_event_id"

class ResumeVoiceBroadcastUseCaseTest {

    private val fakeRoom = FakeRoom()
    private val fakeSession = FakeSession(fakeRoomService = FakeRoomService(fakeRoom))
    private val resumeVoiceBroadcastUseCase = ResumeVoiceBroadcastUseCase(fakeSession)

    @Test
    fun `given a room id with a potential existing voice broadcast state when calling execute then the voice broadcast is resumed or not`() = runTest {
        val cases = listOf<VoiceBroadcastState?>(null).plus(VoiceBroadcastState.values()).map {
            when (it) {
                VoiceBroadcastState.PAUSED -> Case(it, true)
                VoiceBroadcastState.STARTED,
                VoiceBroadcastState.RESUMED,
                VoiceBroadcastState.STOPPED,
                null -> Case(it, false)
            }
        }

        cases.forEach { case ->
            if (case.canResumeVoiceBroadcast) {
                testVoiceBroadcastResumed(case.previousState)
            } else {
                testVoiceBroadcastNotResumed(case.previousState)
            }
        }
    }

    private suspend fun testVoiceBroadcastResumed(previousState: VoiceBroadcastState?) {
        // Given
        clearAllMocks()
        givenAVoiceBroadcastState(previousState)
        val voiceBroadcastInfoContentInterceptor = slot<Content>()
        coEvery { fakeRoom.stateService().sendStateEvent(any(), any(), capture(voiceBroadcastInfoContentInterceptor)) } coAnswers { AN_EVENT_ID }

        // When
        resumeVoiceBroadcastUseCase.execute(A_ROOM_ID)

        // Then
        coVerify {
            fakeRoom.stateService().sendStateEvent(
                    eventType = VoiceBroadcastConstants.STATE_ROOM_VOICE_BROADCAST_INFO,
                    stateKey = fakeSession.myUserId,
                    body = any(),
            )
        }
        val voiceBroadcastInfoContent = voiceBroadcastInfoContentInterceptor.captured.toModel<MessageVoiceBroadcastInfoContent>()
        voiceBroadcastInfoContent?.voiceBroadcastState shouldBe VoiceBroadcastState.RESUMED
        voiceBroadcastInfoContent?.relatesTo?.type shouldBe RelationType.REFERENCE
        voiceBroadcastInfoContent?.relatesTo?.eventId shouldBe A_STARTED_VOICE_BROADCAST_EVENT_ID
    }

    private suspend fun testVoiceBroadcastNotResumed(previousState: VoiceBroadcastState?) {
        // Given
        clearAllMocks()
        givenAVoiceBroadcastState(previousState)

        // When
        resumeVoiceBroadcastUseCase.execute(A_ROOM_ID)

        // Then
        coVerify(exactly = 0) { fakeRoom.stateService().sendStateEvent(any(), any(), any()) }
    }

    private fun givenAVoiceBroadcastState(state: VoiceBroadcastState?) {
        val relatesTo = when (state) {
            VoiceBroadcastState.STARTED,
            null -> null
            VoiceBroadcastState.PAUSED,
            VoiceBroadcastState.RESUMED,
            VoiceBroadcastState.STOPPED -> RelationDefaultContent(RelationType.REFERENCE, A_STARTED_VOICE_BROADCAST_EVENT_ID)
        }
        val event = state?.let {
            Event(
                    eventId = if (state == VoiceBroadcastState.STARTED) A_STARTED_VOICE_BROADCAST_EVENT_ID else AN_EVENT_ID,
                    type = VoiceBroadcastConstants.STATE_ROOM_VOICE_BROADCAST_INFO,
                    stateKey = fakeSession.myUserId,
                    content = MessageVoiceBroadcastInfoContent(
                            voiceBroadcastStateStr = state.value,
                            relatesTo = relatesTo
                    ).toContent()
            )
        }
        fakeRoom.stateService().givenGetStateEvent(event)
    }

    private data class Case(val previousState: VoiceBroadcastState?, val canResumeVoiceBroadcast: Boolean)
}

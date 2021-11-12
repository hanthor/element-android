/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.crypto.actions

import org.matrix.android.sdk.internal.crypto.MXOlmDevice
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.MXKey
import org.matrix.android.sdk.internal.crypto.model.MXOlmSessionResult
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.model.forEach
import org.matrix.android.sdk.internal.crypto.model.toDebugString
import org.matrix.android.sdk.internal.crypto.tasks.ClaimOneTimeKeysForUsersDeviceTask
import timber.log.Timber
import javax.inject.Inject

private const val ONE_TIME_KEYS_RETRY_COUNT = 3

internal class EnsureOlmSessionsForDevicesAction @Inject constructor(
        private val olmDevice: MXOlmDevice,
        private val oneTimeKeysForUsersDeviceTask: ClaimOneTimeKeysForUsersDeviceTask) {

    suspend fun handle(devicesByUser: Map<String, List<CryptoDeviceInfo>>, force: Boolean = false): MXUsersDevicesMap<MXOlmSessionResult> {
        val devicesWithoutSession = ArrayList<CryptoDeviceInfo>()
        val devicesWithSession = MXUsersDevicesMap<MXOlmSessionResult>()

//        val results = MXUsersDevicesMap<MXOlmSessionResult>()

        for ((userId, deviceInfos) in devicesByUser) {
            for (deviceInfo in deviceInfos) {
                val deviceId = deviceInfo.deviceId
                val key = deviceInfo.identityKey()
                if (key == null) {
                    Timber.w("## CRYPTO | Ignoring device (${deviceInfo.userId}|$deviceId) without identity key ")
                    continue
                }

                val sessionId = olmDevice.getSessionId(key)

                if (sessionId.isNullOrEmpty() || force) {
                    Timber.d("## CRYPTO | Found no existing olm session (${deviceInfo.userId}|$deviceId) (force=$force)")
                    devicesWithoutSession.add(deviceInfo)
                } else {
                    Timber.d("## CRYPTO | using olm session $sessionId for (${deviceInfo.userId}|$deviceId)")
                    val olmSessionResult = MXOlmSessionResult(deviceInfo, sessionId)
                    devicesWithSession.setObject(userId, deviceId, olmSessionResult)
                }
            }
        }

        Timber.i("## CRYPTO | Devices without olm session (count:${devicesWithoutSession.size}) :" +
                " ${devicesWithoutSession.joinToString { "${it.userId}|${it.deviceId}" }}")
        if (devicesWithoutSession.size == 0) {
            return devicesWithSession
        }

        // Let's try to setup olm sessions with the other devices
        // Prepare the request for claiming one-time keys
        val usersDevicesToClaim = MXUsersDevicesMap<String>()

        val oneTimeKeyAlgorithm = MXKey.KEY_SIGNED_CURVE_25519_TYPE

        for (device in devicesWithoutSession) {
            usersDevicesToClaim.setObject(device.userId, device.deviceId, oneTimeKeyAlgorithm)
        }

        // TODO: this has a race condition - if we try to send another message
        // while we are claiming a key, we will end up claiming two and setting up
        // two sessions.
        // That should eventually resolve itself, but it's poor form.

        Timber.i("## CRYPTO | claimOneTimeKeysForUsersDevices() : ${usersDevicesToClaim.toDebugString()}")

        val claimParams = ClaimOneTimeKeysForUsersDeviceTask.Params(usersDevicesToClaim)
        val oneTimeKeys = oneTimeKeysForUsersDeviceTask.executeRetry(claimParams, remainingRetry = ONE_TIME_KEYS_RETRY_COUNT)

        // We iterate through claimed info to log missing otks
        usersDevicesToClaim.forEach { userId, deviceId, alg ->
            val foundOtk = oneTimeKeys.getObject(userId, deviceId)
            if (foundOtk == null) {
                Timber.d("## CRYPTO: No one time key for $userId|$deviceId")
            } else if (foundOtk.type == oneTimeKeyAlgorithm) {
                devicesByUser[userId]?.firstOrNull { it.deviceId == deviceId }?.let { cryptoDeviceInfo ->
                    Timber.d("## CRYPTO | creating outbound session for $userId|$deviceId ...")
                    val createdSession = verifyKeyAndStartSession(foundOtk, userId, cryptoDeviceInfo)
                    if (createdSession != null) {
                        Timber.d("## CRYPTO | ... created outbound session $createdSession for $userId|$deviceId")
                        devicesWithSession.setObject(userId, deviceId, MXOlmSessionResult(cryptoDeviceInfo, createdSession))
                    } else {
                        Timber.d("## CRYPTO | ... Failed to create outbound session for $userId|$deviceId")
                    }
                }
            } else {
                // Skipping this key
                Timber.i("## CRYPTO | skipping otk for $userId|$deviceId because unsupported algorithm")
            }
        }
//        oneTimeKeys.forEach { userId, deviceId, oneTimeKey ->
//            if (oneTimeKey.type == oneTimeKeyAlgorithm) {
//                devicesByUser[userId]?.firstOrNull { it.deviceId == deviceId }?.let { cryptoDeviceInfo ->
//                    Timber.d("## CRYPTO | creating outbound session for $userId|$deviceId ...")
//                    val createdSession = verifyKeyAndStartSession(oneTimeKey, userId, cryptoDeviceInfo)
//                    if (createdSession != null) {
//                        Timber.d("## CRYPTO | ... created outbound session $createdSession for $userId|$deviceId")
//                        devicesWithSession.setObject(userId, deviceId, MXOlmSessionResult(cryptoDeviceInfo, createdSession))
//                    } else {
//                        Timber.d("## CRYPTO | ... Failed to create outbound session for $userId|$deviceId")
//                    }
//                }
//            } else {
//                // Skipping this key
//                Timber.i("## CRYPTO | skiping otk for $userId|$deviceId because unsupported algorithm")
//            }
//        }

        return devicesWithSession
//        for ((userId, deviceInfos) in devicesByUser) {
//            for (deviceInfo in deviceInfos) {
//                var oneTimeKey: MXKey? = null
//                val deviceIds = oneTimeKeys.getUserDeviceIds(userId)
//                if (null != deviceIds) {
//                    for (deviceId in deviceIds) {
//                        val olmSessionResult = results.getObject(userId, deviceId)
//                        if (olmSessionResult!!.sessionId != null && !force) {
//                            // We already have a result for this device
//                            continue
//                        }
//                        val key = oneTimeKeys.getObject(userId, deviceId)
//                        if (key?.type == oneTimeKeyAlgorithm) {
//                            oneTimeKey = key
//                        }
//                        if (oneTimeKey == null) {
//                            Timber.w("## CRYPTO | ensureOlmSessionsForDevices() : No one-time keys " + oneTimeKeyAlgorithm +
//                                    " for device " + userId + "|" + deviceId)
//                            continue
//                        }
//                        // Update the result for this device in results
//                        olmSessionResult.sessionId = verifyKeyAndStartSession(oneTimeKey, userId, deviceInfo)
//                    }
//                }
//            }
//        }
//        return results
    }

    private fun verifyKeyAndStartSession(oneTimeKey: MXKey, userId: String, deviceInfo: CryptoDeviceInfo): String? {
        var sessionId: String? = null

        val deviceId = deviceInfo.deviceId
        val signKeyId = "ed25519:$deviceId"
        val signature = oneTimeKey.signatureForUserId(userId, signKeyId)

        val fingerprint = deviceInfo.fingerprint()
        if (!signature.isNullOrEmpty() && !fingerprint.isNullOrEmpty()) {
            var isVerified = false
            var errorMessage: String? = null

            try {
                olmDevice.verifySignature(fingerprint, oneTimeKey.signalableJSONDictionary(), signature)
                isVerified = true
            } catch (e: Exception) {
                Timber.d(e, "## CRYPTO | verifyKeyAndStartSession() : Verify error for otk: ${oneTimeKey.signalableJSONDictionary()}," +
                        " signature:$signature fingerprint:$fingerprint")
                Timber.e("## CRYPTO | verifyKeyAndStartSession() : Verify error for ${deviceInfo.userId}|${deviceInfo.deviceId} " +
                        " - signable json ${oneTimeKey.signalableJSONDictionary()}")
                errorMessage = e.message
            }

            // Check one-time key signature
            if (isVerified) {
                sessionId = deviceInfo.identityKey()?.let { identityKey ->
                    olmDevice.createOutboundSession(identityKey, oneTimeKey.value)
                }

                if (sessionId.isNullOrEmpty()) {
                    // Possibly a bad key
                    Timber.e("## CRYPTO | verifyKeyAndStartSession() : Error starting session with device $userId:$deviceId")
                } else {
                    Timber.v("## CRYPTO | verifyKeyAndStartSession() : Started new sessionid " + sessionId +
                            " for device " + deviceInfo + "(theirOneTimeKey: " + oneTimeKey.value + ")")
                }
            } else {
                Timber.e("## CRYPTO | verifyKeyAndStartSession() : Unable to verify signature on one-time key for device " + userId +
                        ":" + deviceId + " Error " + errorMessage)
            }
        }

        return sessionId
    }
}

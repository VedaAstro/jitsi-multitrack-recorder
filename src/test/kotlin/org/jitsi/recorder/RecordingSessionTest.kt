/*
 * Jitsi Multi Track Recorder
 *
 * Copyright @ 2024-Present 8x8, Inc.
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
package org.jitsi.recorder

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.InfoEvent
import org.jitsi.mediajson.PongEvent
import java.io.File

class RecordingSessionTest : ShouldSpec() {
    init {
        should("parse the info event the bridge sends when the connection is established") {
            // jitsi-videobridge >= 2.3-307 (jicoco with InfoEvent) sends this on connect.
            val event = Event.parse(
                """{"event":"info","name":"jitsi-videobridge","version":"2.3-307","region":"mars"}"""
            )
            event.shouldBeInstanceOf<InfoEvent>()
        }

        should("quietly skip events the recording does not depend on") {
            val meetingId = "recording-session-test-${System.nanoTime()}"
            val sessionDirectory = File(Config.recordingDirectory, meetingId)
            try {
                val session = RecordingSession(meetingId)

                // The "info" event the updated bridge sends on connect.
                session.processText(
                    """{"event":"info","name":"jitsi-videobridge","version":"2.3-307"}"""
                ).shouldBeNull()

                // A "stop" bracketing a talk, sent by newer bridges.
                session.processText(
                    """{"event":"stop","sequenceNumber":"1","stop":{"tag":"endpoint1","timestamp":48000}}"""
                ).shouldBeNull()

                // The "session-end" the bridge sends on shutdown (its type id is not registered for
                // parsing even in current jicoco).
                session.processText("""{"event":"session-end"}""").shouldBeNull()

                // An event type a future bridge may add.
                session.processText(
                    """{"event":"speakers","speakers":["endpoint1"],"silence":false}"""
                ).shouldBeNull()

                // Ping/pong keeps working.
                val pong = session.processText("""{"event":"ping","id":42}""")
                Event.parse(pong!!).shouldBeInstanceOf<PongEvent>().id shouldBe 42
            } finally {
                sessionDirectory.deleteRecursively()
            }
        }
    }
}

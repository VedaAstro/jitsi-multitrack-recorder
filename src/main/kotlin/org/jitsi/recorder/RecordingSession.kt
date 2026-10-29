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

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import kotlinx.coroutines.CancellationException
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.InfoEvent
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.PingEvent
import org.jitsi.mediajson.PongEvent
import org.jitsi.mediajson.SessionEndEvent
import org.jitsi.mediajson.StartEvent
import org.jitsi.mediajson.TranscriptionResultEvent
import org.jitsi.utils.logging2.createLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.jitsi.recorder.RecorderMetrics.Companion.instance as metrics

class RecordingSession(private val meetingId: String) {
    private val logger = createLogger().apply { addContext("meetingId", meetingId) }
    private val directory = selectDirectory(meetingId)

    /** Event type ids already reported, so repeated unknown types don't spam the log. */
    private val reportedUnknownEventTypeIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        metrics.sessionsStarted.inc()
        metrics.currentSessions.inc()
    }

    private val mediaJsonRecorder = if (Config.recordingFormat == RecordingFormat.MKA) {
        MediaJsonMkaRecorder(directory, logger)
    } else {
        MediaJsonJsonRecorder(directory)
    }

    suspend fun processText(text: String): String? {
        return try {
            when (val event = Event.parse(text)) {
                is PingEvent -> PongEvent(event.id).toJson()
                is InfoEvent -> {
                    // The bridge sends an informational message (its version, region, ...) when the
                    // connection is established. It describes the sender, not the session's media.
                    logger.info("Received info from the bridge: ${event.toJson()}")
                    null
                }
                is StartEvent, is MediaEvent -> {
                    mediaJsonRecorder.addEvent(event)
                    null
                }
                is PongEvent, is SessionEndEvent, is TranscriptionResultEvent -> null
                else -> {
                    // Other known event types (e.g. "stop") are control-plane traffic the recording
                    // does not depend on.
                    logger.debug("Ignoring event: ${event.toJson()}")
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidTypeIdException) {
            // A newer bridge may send event types this version doesn't know about (e.g. the "info"
            // event jitsi-videobridge sends on connect since 2.3-307, before jicoco learned of it).
            // The recording only depends on "start" and "media", so skipping is safe. Log without
            // a stack trace, once per type id, instead of a SEVERE with a stack per event.
            val typeId = e.typeId ?: "null"
            if (reportedUnknownEventTypeIds.add(typeId)) {
                logger.warn("Ignoring event with unknown type id '$typeId'")
            }
            null
        } catch (e: Exception) {
            logger.error("Error", e)
            null
        }
    }

    suspend fun stop() {
        logger.info("Stopping")
        mediaJsonRecorder.stop()
        metrics.currentSessions.dec()
        finalize()
    }

    private fun finalize() {
        if (!Config.finalizeScript.isNullOrBlank()) {
            logger.info("Running finalize script")
            val logFile = File(directory, "finalize.log")
            val process = ProcessBuilder(
                Config.finalizeScript,
                meetingId,
                directory.absolutePath,
                Config.recordingFormat.toString()
            ).apply {
                if (!Config.logFinalizeOutput) {
                    redirectOutput(logFile)
                    redirectError(logFile)
                }
            }.start()

            if (Config.logFinalizeOutput) {
                logFile.outputStream().use { fileOut ->
                    val logLine: (String) -> Unit = { line ->
                        logger.info("[finalize] $line")
                        fileOut.write((line + "\n").toByteArray())
                        fileOut.flush()
                    }

                    val stdoutThread = Thread {
                        process.inputStream.bufferedReader().forEachLine(logLine)
                    }
                    val stderrThread = Thread {
                        process.errorStream.bufferedReader().forEachLine(logLine)
                    }
                    stdoutThread.start()
                    stderrThread.start()
                    stdoutThread.join()
                    stderrThread.join()
                }
            }

            process.waitFor().let {
                if (it != 0) {
                    metrics.finalizeErrors.inc()
                    logger.warn("Error from finalize: $it")
                } else {
                    logger.info("Finalize script completed successfully")
                }
            }
        }
    }

    private fun selectDirectory(meetingId: String): File {
        var suffix = ""
        var counter = 1
        var file: File

        do {
            val path = "${Config.recordingDirectory}/$meetingId$suffix"
            file = File(path)

            if (!file.exists()) {
                file.mkdirs()
                return file
            }

            suffix = "-$counter"
            counter++
        } while (counter < 100)

        throw RuntimeException("Failed to create directory for meetingId $meetingId after 100 attempts")
    }
}

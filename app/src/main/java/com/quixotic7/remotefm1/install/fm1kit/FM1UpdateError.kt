// SPDX-License-Identifier: MIT
// Ported from FM1Kit 0.4.5 (88b609c) Sources/FM1Kit/Updater/FM1UpdateError.swift and FM1Advice.swift.
// Copyright (c) 2026 Drew Robinson, MIT licence (LICENSES/FM1Kit-MIT.txt). Kotlin port for RemoteFM1 Android:
// the voice-restore, backup and bootloader-installer errors are left out (those features aren't ported); the words
// are FM1Kit's.
package com.quixotic7.remotefm1.install.fm1kit

import kotlin.coroutines.cancellation.CancellationException

/** Why an update stopped; [description] says what happened and what to do next, for the person updating. */
sealed class FM1UpdateError : Exception() {
    enum class Phase { staging, writing }

    data object DeviceNotFound : FM1UpdateError()
    data class MultipleDevices(val count: Int) : FM1UpdateError()
    data class SwitchNotConfirmed(val device: String, val pkg: String) : FM1UpdateError()
    data class AlreadyInstalled(val identity: String) : FM1UpdateError()
    data class SameIdentityNotConfirmed(val identity: String) : FM1UpdateError()
    data object CapabilityUnavailable : FM1UpdateError()
    data object DeviceBusy : FM1UpdateError()
    data class ArmRefused(val code: Int?) : FM1UpdateError()
    data class BuildMismatch(val expected: FM1BuildID, val reported: FM1BuildID) : FM1UpdateError()
    data class ModelMismatch(val device: String, val pkg: String) : FM1UpdateError()
    data class NotFirmware(val product: String) : FM1UpdateError()
    data class BootloaderMismatch(val requests: Int) : FM1UpdateError()
    data class PackageRefused(val requests: Int) : FM1UpdateError()
    data class Stalled(val phase: Phase, val requests: Int) : FM1UpdateError()
    data class Disconnected(val phase: Phase, val requests: Int) : FM1UpdateError()
    data object LoaderNotFound : FM1UpdateError()
    data object DeviceDidNotReturn : FM1UpdateError()
    data class IdentityMismatch(val expected: String, val reported: String) : FM1UpdateError()
    data class BadRequest(val address: Long, val length: Int) : FM1UpdateError()
    data class UnexpectedFinish(val phase: Phase) : FM1UpdateError()

    override val message: String get() = description

    val description: String
        get() = when (this) {
            DeviceNotFound -> "No FM-1 found. Check the cable, and close any other app using it."
            is MultipleDevices -> "$count FM-1s answered. Connect only the one to update, and try again."
            is SwitchNotConfirmed -> "Installing $pkg switches the FM-1 from $device to a different firmware, which can " +
                "erase its projects and samples. Back them up, then confirm the switch."
            is AlreadyInstalled -> "The FM-1 is already running $identity, so it isn't reinstalled. Choose another firmware."
            is SameIdentityNotConfirmed -> "The FM-1 already reports $identity, the same identity as this package. Confirm " +
                "you mean to reinstall it, or to install another release of the same firmware."
            CapabilityUnavailable -> "The FM-1's open firmware didn't say whether it's ready to update, so the update " +
                "didn't start. Switch it off and on again, and try again."
            DeviceBusy -> "The FM-1's open firmware is busy with another update, so this one didn't start. Close any " +
                "other updater, switch the FM-1 off and on again, and try again."
            is ArmRefused -> if (code != null) "The FM-1's open firmware refused this package (code $code), so the update didn't start."
                else "The FM-1's open firmware didn't answer when asked to prepare for the update, so the update " +
                    "didn't start. Switch it off and on again, and try again."
            is BuildMismatch -> "The update finished, but the FM-1 reports build ${reported.hex.take(16)} instead of " +
                "${expected.hex.take(16)}."
            is ModelMismatch -> "The device is $device but the package is for $pkg."
            is NotFirmware -> "$product installs a bootloader, not firmware, so it can't be installed as firmware."
            is BootloaderMismatch -> "The FM-1's firmware stopped after $requests requests, while comparing this " +
                "package's bootloader with the FM-1's, and should carry on as before. Firmwares with Felucca's original " +
                "update check, such as AMB-1, HYP-1, DUB-1 and Felucca itself, install M-VAVE's packages only when the " +
                "FM-1 already has the bootloader the package brings, so they refuse V15 once the FM-1's bootloader has " +
                "been changed, by m-bootloader or by M-VAVE's open firmware. Install it from a Mac in the chip's USB " +
                "download mode instead, which keeps the FM-1's bootloader, or first install a firmware with Felucca's " +
                "updated check, such as LOOM-8, which takes it."
            is PackageRefused -> "The FM-1's firmware read this package's header and stopped after $requests requests: " +
                "it refused the package, and should carry on as before. Its screen may show FAILED and a code. " +
                "Firmwares with Felucca's original update check refuse m-bootloader's installer and update loaders " +
                "they don't recognise this way, and those based on Felucca 1.6.1 refuse M-VAVE's open firmware too. " +
                "Install it from a Mac in the chip's USB download mode instead, or first install a firmware with " +
                "Felucca's updated check, such as LOOM-8, which takes it."
            is Stalled -> if (phase == Phase.staging) stagingWords(requests)
                else "The update loader stopped after $requests requests. Keep the FM-1 connected and resume the " +
                    "update; it stays in update mode until one finishes."
            is Disconnected -> if (phase == Phase.staging) stagingWords(requests)
                else "The update loader disconnected after $requests requests. Reconnect the FM-1 and resume the " +
                    "update; it stays in update mode until one finishes."
            LoaderNotFound -> "The update loader didn't appear. Reconnect the FM-1 and resume the update."
            DeviceDidNotReturn -> "The FM-1 didn't come back after writing. Switch it off and on again."
            is IdentityMismatch -> "The update finished, but the FM-1 reports $reported instead of $expected."
            is BadRequest -> "The FM-1 asked for data outside the package ($length bytes at 0x${address.toString(16)}), " +
                "so the update was stopped without answering. Check the package is complete."
            is UnexpectedFinish -> if (phase == Phase.staging) "The FM-1 asked to finish writing while it was still " +
                "checking the package, so the update was stopped. Switch it off and on again. If it starts in its " +
                "update loader, resume the update; otherwise install again."
                else "The update loader asked to finish checking the package while it was writing, so the update was " +
                    "stopped. Keep the FM-1 connected and resume the update; it stays in update mode until one finishes."
        }

    private fun stagingWords(requests: Int) = "The FM-1 stopped after $requests requests while checking the package, " +
        "before restarting into its update loader. It should still start normally. If its screen shows an update " +
        "error of its own, its firmware refused this package: some custom firmwares bring their own update code and " +
        "accept only certain packages. Replacing such a firmware needs the chip's USB download mode and a tool that " +
        "writes there, such as M-Flasher; the firmware's instructions say how to start download mode. If the FM-1 " +
        "doesn't start, and its firmware supports USB recovery mode (holding OCT− while switching it on), start it " +
        "that way and install again; otherwise recovering it needs a computer."

    /** may leave the FM-1 waiting part-way through an update, or otherwise changed */
    internal val mayLeaveDeviceChanged: Boolean
        get() = when (this) {
            is Stalled -> phase == Phase.writing
            is Disconnected -> phase == Phase.writing
            LoaderNotFound, is UnexpectedFinish, DeviceDidNotReturn, is BadRequest -> true
            else -> false
        }

    internal val summary: Pair<String, FM1Advice.Action>
        get() = when (this) {
            DeviceNotFound -> "No FM-1 found" to FM1Advice.Action.Reconnect
            is MultipleDevices -> "More than one FM-1 is connected" to FM1Advice.Action.Reconnect
            is SwitchNotConfirmed -> "This switches to a different firmware" to FM1Advice.Action.Confirm
            is AlreadyInstalled -> "This firmware is already installed" to FM1Advice.Action.ChooseAnotherFirmware
            is SameIdentityNotConfirmed -> "This firmware looks already installed" to FM1Advice.Action.Confirm
            CapabilityUnavailable, DeviceBusy -> "The FM-1 wasn't ready to update" to FM1Advice.Action.SwitchOffAndOn
            is ArmRefused -> if (code == null) "The FM-1 wasn't ready to update" to FM1Advice.Action.SwitchOffAndOn
                else "The FM-1 refused this firmware" to FM1Advice.Action.ChooseAnotherFirmware
            is BuildMismatch, is IdentityMismatch -> "The FM-1 is running something else" to FM1Advice.Action.TryAgain
            is ModelMismatch -> "This firmware isn't for the FM-1" to FM1Advice.Action.ChooseAnotherFirmware
            is NotFirmware -> "This file isn't a firmware" to FM1Advice.Action.ChooseAnotherFirmware
            is BootloaderMismatch -> "The firmware refused M-VAVE's package" to FM1Advice.Action.NeedsComputer
            is PackageRefused -> "The firmware refused this package" to FM1Advice.Action.NeedsComputer
            is Stalled -> if (phase == Phase.staging) "The install didn't start" to FM1Advice.Action.TryAgain
                else "The install was interrupted" to FM1Advice.Action.Resume
            is Disconnected -> if (phase == Phase.staging) "The install didn't start" to FM1Advice.Action.TryAgain
                else "The install was interrupted" to FM1Advice.Action.Resume
            LoaderNotFound -> "The install was interrupted" to FM1Advice.Action.Resume
            is UnexpectedFinish -> if (phase == Phase.writing) "The install was interrupted" to FM1Advice.Action.Resume
                else "The install stopped" to FM1Advice.Action.SwitchOffAndOn
            DeviceDidNotReturn -> "The FM-1 didn't come back" to FM1Advice.Action.SwitchOffAndOn
            is BadRequest -> "The firmware file looks damaged" to FM1Advice.Action.ChooseAnotherFirmware
        }

    val advice: FM1Advice
        get() = FM1Advice(summary.first, description, !mayLeaveDeviceChanged, summary.second, toString())
}

/** What an error means for the person holding the FM-1: a headline, whether it is still fine, the one thing to do. */
data class FM1Advice(
    val headline: String,
    val explanation: String,
    val isDeviceFine: Boolean,
    val action: Action,
    val technicalDetails: String,
) {
    sealed class Action {
        data object TryAgain : Action()
        data object Resume : Action()
        data object Reconnect : Action()
        data object SwitchOffAndOn : Action()
        data object Confirm : Action()
        data object ChooseAnotherFirmware : Action()
        data class HoldKeysAtPowerOn(val keys: List<String>) : Action()
        data object NeedsComputer : Action()
        data object Dismiss : Action()
    }

    companion object {
        /** advice for any error FM1Kit throws, or general advice for any other */
        fun of(error: Throwable): FM1Advice {
            val details = error.toString()
            return when (error) {
                is FM1UpdateError -> error.advice
                is FwscFailure -> FM1Advice("This file can't be installed", error.description, true,
                                            Action.ChooseAnotherFirmware, details)
                is MIDITransportError -> FM1Advice("The FM-1 couldn't be reached",
                    error.description + " Check the cable, and close any other app using the FM-1.", true,
                    Action.Reconnect, details)
                is CancellationException -> FM1Advice("Stopped", "It was stopped before it finished.", true,
                                                      Action.Dismiss, details)
                else -> FM1Advice("Something went wrong", error.localizedMessage ?: error.toString(), true,
                                  Action.TryAgain, details)
            }
        }
    }
}

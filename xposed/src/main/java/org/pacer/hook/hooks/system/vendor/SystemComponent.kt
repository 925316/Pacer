// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The system components Pacer resolves by name. Each is a class that is NOT
// loadable from the default class loader on every build, which is the whole
// reason a candidate list and a locator exist: since Android 12 several of these
// live in APEX modules, and Android 14 renamed ConnectivityService outright.
package org.pacer.hook.hooks.system.vendor

/**
 * @property process the process that serves this component, so a hooker only
 *   installs where the component actually lives.
 */
enum class SystemComponent(val process: SystemProcess) {
    LOCATION_MANAGER_SERVICE(SystemProcess.SYSTEM_SERVER),
    LOCATION_PROVIDER_MANAGER(SystemProcess.SYSTEM_SERVER),
    WIFI_SERVICE(SystemProcess.SYSTEM_SERVER),
    WIFI_SCANNER_SERVICE(SystemProcess.SYSTEM_SERVER),
    CONNECTIVITY_SERVICE(SystemProcess.SYSTEM_SERVER),
    APPOPS_SERVICE(SystemProcess.SYSTEM_SERVER),
    SETTINGS_PROVIDER(SystemProcess.SYSTEM_SERVER),
    TELEPHONY_REGISTRY(SystemProcess.SYSTEM_SERVER),
    TELEPHONY_PHONE_MANAGER(SystemProcess.PHONE),
    BLUETOOTH_SCAN_SERVICE(SystemProcess.BLUETOOTH),
}

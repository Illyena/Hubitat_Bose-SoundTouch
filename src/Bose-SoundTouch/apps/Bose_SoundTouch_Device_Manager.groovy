/*
 * Modifications Copyright 2026 Illyena
 * Copyright 2020 - tomw
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

//file:noinspection unused

import groovy.transform.Field
import hubitat.device.HubAction

//Input Names : //TODO comment out prior to running. This is to help keep track of variables
@Field Integer discoveryTimeout
@Field boolean retainExistingDevices
@Field List deviceList
@Field boolean logEnable

@Field static final String discoverySync = ""

definition(
        name: "Bose SoundTouch Device Manager",
        namespace: "Bose-SoundTouch",
        author: ["Illyena", "tomw"],
        description: "",
        category: "Convenience",
        importUrl: "https://raw.githubusercontent.com/Illyena/Hubitat_Bose-SoundTouch/main/src/Bose-SoundTouch/apps/Bose_SoundTouch_Device_Manager.groovy",
        iconUrl: "",
        iconX2Url: "",
        iconX3Url: ""
)

preferences {
    page name: "mainPage1"
    page name: "mainPage2"
    page name: "mainPage2b"
}

boolean logDebug(msg) {
    if (logEnable) log.debug "${msg}"
    return logEnable
}

void clearAllSettings() {
    settings.each { k, v -> app?.removeSettings(k) }
}

void installed() {
    log.trace "installed()"
    updated()
}

void uninstalled() {
    log.trace "uninstalled()"
    deleteChildren()
}

void updated() {
    log.trace "updated()"
    unsubscribe()
    createDevices()
}

def mainPage1() {
    dynamicPage(name: "mainPage1", title: "", install: false, uninstall: true) {
        if (null == atomicState.devices) atomicState.devices = [:]

        section {
            href(page: "mainPage2", title: "<b>Begin discovery process</b>",
                    description: "Discover Bose SoundTouch devices on your local network.", params: [runDiscovery: true])
            input name: "discoveryTimeout", type: "number", title: "Discovery timeout (default 5 sec)",
                    defaultValue: 5, range: "3-20", required: true, width: 2
        }
    }
}

def mainPage2(params) {
    dynamicPage(name: "mainPage2", title: "", install: true, uninstall: true) {
        if (params.runDiscovery) {
            atomicState.devices = [:] // clear devices list
            deviceDiscovery()
        }

        section {
            paragraph "<b>Discovered devices:</b> ${atomicState.devices}"
        }
        section {
            href(page: "mainPage1", title: "<b>Retry discovery process</b>", description: "")
        }
        section {
            input name: "retainExistingDevices", type: "bool", title: "Retain existing devices? (recommended)",
                    defaultValue: true, required: true
            input name: "deviceList", type: "enum", title: "Select devices to create", options: atomicState.devices,
                    required: false, multiple: true
            input name: "logEnable", type: "bool", title: "Enable debug logging?",
                    defaultValue: false, required: true
        }
    }
}

void deviceDiscovery() {
    subscribe(location, null, locationHandler, [filterEvents: false]) // subscribe to receive upnp events
    sendHubCommand(new HubAction("lan discovery urn:schemas-upnp-org:device:MediaRenderer:1", hubitat.device.Protocol.LAN)) // upnp request
    pauseExecution(1000 * ((discoveryTimeout < 3) ? 3 : discoveryTimeout.toInteger())) // limit wait to 3sec minimum
    unsubscribe(location) // stop listening
}

void createDevices() {
    if (!retainExistingDevices) deleteChildren()
    def child
    for (device in deviceList) {
        child = getChildDevice(device)
        if (child) {
            logDebug "skipping creation for existing device: ${device}"
            continue // if this child exists, either retainExistingDevices is set or something unexpected happened.  regardless, we're done.
        }
        child = addChildDevice("Bose-SoundTouch", "Bose SoundTouch Device", device,
                [isComponent: true, name: "Bose SoundTouch Device", label: atomicState.devices[device]])
        if (child) {
            logDebug "creating device: ${device}"
            child.updateSetting("IP_address", device)
            child.configure()
        }
    }
}

void deleteChildren() {
    for (child in getChildDevices()) {
        deleteChildDevice(child.getDeviceNetworkId())
    }
}

void locationHandler(evt) {
    //logDebug "evt = ${evt}"
    //logDebug "parseLanMessage(evt.description) = ${parseLanMessage(evt.description)}"
    //logDebug "parseLanMessage(evt.description).networkAddress = ${convertHexToIP(parseLanMessage(evt.description).networkAddress)}"
    //logDebug "XML = ${httpGetExec(convertHexToIP(parseLanMessage(evt.description).networkAddress), parseLanMessage(evt.description).ssdpPath)}"

    pauseExecution(new Random().nextInt(1000))
    def resp = httpGetExec(convertHexToIP(parseLanMessage(evt.description).networkAddress),
            convertHexToInt(parseLanMessage(evt.description).deviceAddress), parseLanMessage(evt.description).ssdpPath)
    logDebug "inspecting device: ${resp?.device?.modelName.toString()}"
    if (resp?.device?.modelName.toString().contains("SoundTouch")
            || resp?.device?.modelName.toString().contains("Lifestyle")) { // Filter on SoundTouch model names
        synchronized (discoverySync) {
            def tmpDevs = atomicState.devices
            tmpDevs.put(convertHexToIP(parseLanMessage(evt.description).networkAddress).toString(), resp.device.friendlyName.toString())
            atomicState.devices = tmpDevs
        }
    }
}

static private Integer convertHexToInt(hex) {
    return Integer.parseInt(hex, 16)
}

static private String convertHexToIP(hex) {
    return [
            convertHexToInt(hex[0..1]),
            convertHexToInt(hex[2..3]),
            convertHexToInt(hex[4..5]),
            convertHexToInt(hex[6..7])
    ].join(".")
}

String getBaseURI(devIP, devPort) {
    baseUri = "http://" + devIP
    if (null != devPort) baseUri += (":" + devPort)
    return baseUri
}

def httpGetExec(devIP, devPort, suffix) {
    logDebug "httpGetExec(${devIP}:${devPort}, ${suffix})"
    try {
        def getString = getBaseURI(devIP, devPort) + suffix
        httpGet([uri: getString.replaceAll(' ', '%20'), contentType: "text/xml", requestContentType: "text/xml"]) {
            resp ->
                if (resp.data) {
                    //logDebug "resp.data = ${resp.data}"
                    return resp.data
                }
        }
    } catch (Exception e) {
        log.warn "httpGetExec() failed: ${e.message}"
    }
}

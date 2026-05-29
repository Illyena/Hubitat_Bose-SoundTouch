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
 *
 * -------------------------------------------
 *
 * Change history:
 *
 * 1.1.0 - @tomw - Added captureContentItem feature for additional user presets.  Added AudioNotification capability support.
 * 1.0.0 - @tomw - Initial release
 */

//file:noinspection unused

import groovy.transform.Field
import groovy.xml.XmlSlurper
import groovy.xml.XmlUtil
import groovy.xml.slurpersupport.GPathResult
import hubitat.helper.HexUtils

//Input Names: // TODO comment out prior to running. This is to help keep track of input variables
@Field String IP_address
@Field String appKey
@Field boolean adjustSlaveVolumes
@Field boolean logEnable

metadata {
    definition(
            name: "Bose SoundTouch Device",
            namespace: "Bose-SoundTouch",
            author: ["Illyena", "tomw"],
            importUrl: "https://raw.githubusercontent.com/Illyena/Hubitat_Bose-SoundTouch/main/src/Bose-SoundTouch/drivers/Bose_SoundTouch_Device.groovy"
    ) {
        capability "AudioNotification"      // Review
        capability "AudioVolume"
        capability "PushableButton"
        capability "Configuration"          // Review
        capability "Initialize"             // Review
        capability "MusicPlayer"
        capability "Refresh"                // Review
        capability "SpeechSynthesis"        // Review
        capability "Switch"

        command "push", ["button"]

        command "createZone", ["slaveIP", "slaveMAC"]
        command "addZoneSlave", ["slaveIP", "slaveMAC"]
        command "removeZoneSlave", ["slaveIP", "slaveMAC"]
        command "captureZone", ["zoneNumber"]

        command "captureContentItem", ["itemNumber"]

        attribute "commStatus", "string"
        attribute "zoneRole", "string"
        attribute "trackArt", "string"
    }
}

preferences {
    section {
        input name: "IP_address", type: "text", title: "IP address of Bose SoundTouch Device", required: true
        input name: "appKey", type: "text", title: "Consumer Key, used for speaker notifications", required: false
        input name: "adjustSlaveVolumes", type: "bool", title: "Control slave speakers with master speakers (volume, mute, power)?", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
    }
}

boolean logDebug(msg) {
    if (logEnable) log.debug msg
    return logEnable
}

boolean logXml(msg) {
    return logDebug("${escapeXml(msg)}")
}

void clearAllSettings() {
    settings.each { k, v -> device?.removeSetting(k) }
}

void installed() {
    log.trace "installed()"
    configure()
    updated()
}

void uninstalled() {
    log.trace "uninstalled()"
    unschedule()
    interfaces.webSocket.close() // close any connections
}

void updated() {
    log.trace "updated()"
}

void initialize() {
    unschedule()
    interfaces.webSocket.close() // close any connections

    // connect to the Bose websocket interface
    interfaces.webSocket.connect("http://${IP_address}:8080/", headers: ["Sec-WebSocket-Protocol": "gabbo"])

    // Custom attributes
    sendEvent(name: "commStatus", value: "unknown")
    sendEvent(name: "zoneRole", value: "unknown")

    // AudioVolume
    sendEvent(name: "volume", value: "unknown")
    sendEvent(name: "mute", value: "unknown")

    // Switch
    sendEvent(name: "switch", value: "unknown")

    // MusicPlayer
    sendEvent(name: "level", value: "unknown")
    sendEvent(name: "status", value: "unknown")
    sendEvent(name: "trackData", value: "unknown")
    sendEvent(name: "trackDescription", value: "unknown")
    sendEvent(name: "trackArt", value: "unknown")

    inspectZone() // store off info from any pre-existing zones
    refresh() // update everything
}

void configure() {
    unschedule() // unschedule any pending actions

    def info = readInfo(false)
    if (!info) {
        logDebug "configure() failed"
        return
    }

    state.clear()
    state.macaddr = info.@deviceID.toString()

    // define the range of zone numbers for captureZone and captureContentItem.
    // note that 1 through 6 are reserved to emulate physical
    //  buttons on the device.  be sure to reserve more if necessary
    state.zoneNumMin = 10
    state.zoneNumMax = 20
    state.itemNumMin = 30
    state.itemNumMax = 40

    initialize()
}

void refresh() {
    refresh(false)
}

void refresh(useCachedValues) {
    if (null == IP_address) return
    unschedule() // unschedule any pending refreshes

    readInfo(useCachedValues)
    readNowPlaying(useCachedValues)
    readZone(useCachedValues)

    updateAttributes(true)

    /*
    refresh() every 5 minutes as a keep alive, 
       if there isn't activity in the meantime.
       5 minutes is meant as a one song approximation.
    
    refresh() happens routinely on any websocket event.
       action from parse() will preclude this scheduled
       refresh during normal operation.
    */
    runIn(300, refresh)
}

void updateAttributes(useCachedValues) {
    if (!useCachedValues) refresh()
    if (!checkCommStatus()) return

    sendEvent(name: "switch", value: isPoweredOn() ? "on" : "off")
    readVolume()
    readMute()
    updatePlayerAttributes(useCachedValues)

    def gz = readZone(useCachedValues)
    def role = (null == isZoneMaster(gz)) ? "unknown" : (isZoneMaster(gz) ? "master" : "slave")
    sendEvent(name: "zoneRole", value: role)
}

void updatePlayerAttributes(useCachedValues) {
    def np = readNowPlaying(useCachedValues)
    if (null == np) {
        logDebug "failed to get now_playing status"
        return
    }

    def tempStatus = "unknown"
    switch (np.playStatus.text()) {
        case "PLAY_STATE":
            tempStatus = "playing"
            break
        case "PAUSE_STATE":
            tempStatus = "paused"
            break
        case "STOP_STATE":
            tempStatus = "stopped"
            break
        case "BUFFERING_STATE":
            tempStatus = "loading"
            break
        case "":
            tempStatus = "powered off"
            break
    }
    if ("STANDBY" == np.@source.text()) tempStatus = "standby"
    sendEvent(name: "status", value: tempStatus)

    // From Bose API reference for now_playing.ContentItem:
    // This object describes the content container that is playing on the product. You should treat this object as an opaque blob, for use in /select.
    // Note: this is stored as unparsed XML so that it can by used in playTrack() and friends
    sendEvent(name: "trackData", value: (!np.ContentItem.isEmpty()) ? escapeXml(serializeXml(np.ContentItem)) : "unknown")
    if ((null == np) || ([np.track, np.artist, np.album].contains(null)))
        sendEvent(name: "trackDescription", value: "unknown")
    else
        sendEvent(name: "trackDescription", value: "${np.track} by ${np.artist} from ${np.album}")

    if ("IMAGE_PRESENT" == np.art.@artImageStatus.text())
        sendEvent(name: "trackArt", value: "<img src=\"${np.art.text()}\">")
    else
        sendEvent(name: "trackArt", value: "unknown")
}

void toggleMute() {
    operateKey("press", "MUTE")
    if (adjustSlaveVolumes) toggleSlaveMutes()
}

void mute() {
    if (readMute()) return // already muted
    toggleMute()
}

void unmute() {
    if (!readMute()) return // already unmuted
    toggleMute()
}

void setLevel(volumeLevel) {
    if (!checkCommStatus()) {
        refresh()
        return
    }

    def intVolumeLevel = (volumeLevel >= 0) ? ((volumeLevel <= 100) ? volumeLevel : 100) : 0 // bound to [0..100]
    httpPostExec("volume", "<volume>${intVolumeLevel.toString()}</volume>")
    if (adjustSlaveVolumes) doAdjustSlaveVolumes(intVolumeLevel) // adjust volumes of slaves in zone, if they exist

    if (intVolumeLevel <= 0) mute() // mute if new volumeLevel is 0
    else unmute()
}

void setVolume(volumeLevel) {
    setLevel(volumeLevel)
}

void volumeDown() {
    setLevel(readVolume() - 1)
}

void volumeUp() {
    setLevel(readVolume() + 1)
}

void nextTrack() {
    operateKey("press", "NEXT_TRACK")
}

void pause() {
    operateKey("press", "PLAY_PAUSE") // treat pause like a toggle
}

void play() {
    operateKey("press", "PLAY")
}

void playText(text) {
    playText(text, 70)
}

void playText(text, volumeLevel) {
    postPlayInfoObject(textToSpeech(text).uri, (volumeLevel.toInteger() < 10) ? "10" : (volumeLevel.toInteger() > 70)
            ? "70" : volumeLevel)
}

void playTrack(trackUri) {
    setTrack(trackUri)
    play()
}

void previousTrack() {
    operateKey("press", "PREV_TRACK")
}

void restoreTrack(trackUri) {
    playTrack(trackUri)
}

void resumeTrack(trackUri) {
    playTrack(trackUri)
}

void setTrack(trackUri) {
    httpPostExec("select", trackUri)
}

void stop() {
    operateKey("press", "PAUSE")
}

void playTextAndRestore(text, volumeLevel) {
    playText(text, volumeLevel)
}

void playTextAndResume(text, volumeLevel) {
    playText(text, volumeLevel)
}

void playTrack(trackUri, volumeLevel) {
    setTrack(trackUri)
    setVolume(volumeLevel)
    play()
}

void playTrackAndRestore(trackUri, volumeLevel) {
    playTrack(trackUri, volumeLevel)
}

void playTrackAndResume(trackUri, volumeLevel) {
    playTrack(trackUri, volumeLevel)
}

void on() {
    if (null == isPoweredOn()) return
    if (!isPoweredOn()) {
        operateKey("press", "POWER")
        operateKey("release", "POWER")
        if (adjustSlaveVolumes) toggleSlavePowers()
    }
}

void off() {
    if (null == isPoweredOn()) return
    if (isPoweredOn()) {
        operateKey("press", "POWER")
        operateKey("release", "POWER")
        if (adjustSlaveVolumes) toggleSlavePowers()
    }
}

void push() {} // no-op

void push(button) {
    switch (button.toString().isInteger()) {
        case true:
            logDebug "number button - ${button.toString().toInteger()}"

            def zoneNumMin = (state.zoneNumMin ? state.zoneNumMin.toInteger() : 10)
            def zoneNumMax = (state.zoneNumMax ? state.zoneNumMax.toInteger() : 20)
            def itemNumMin = (state.itemNumMin ? state.itemNumMin.toInteger() : 30)
            def itemNumMax = (state.itemNumMax ? state.itemNumMax.toInteger() : 40)

            switch (button.toString().toInteger()) {
                case 1..6:
                    // treat 1 to 6 as the physical preset buttons.  update this range if your device has more than 6.
                    // From Bose: ...set state to be "release" to start playing the preset, or set the state to "press" to set the preset.
                    operateKey("release", "PRESET_${button.toString().toInteger()}")
                    break
                case zoneNumMin..zoneNumMax:
                    // treat this range as saved zone configs and restore the zone, if it exists
                    restoreZoneMulti(button.toString().toInteger())
                    break
                case itemNumMin..itemNumMax:
                    // treat this range as saved content items and restore the item, if it exists
                    restoreContentItem(button.toString().toInteger())
                    break
                default:
                    logDebug "unknown button number"
                    break
            }
            break

        case false:
            logDebug "string button - ${button.toString()}"

            // treat strings as slave names and attempt to add to or create zone
            def slaveDetails = lookupSlaveDetailsFromName(button.toString())
            if (null == slaveDetails) {
                logDebug "push() slave not known: ${button}"
                return
            }
            if (slaveDetails.macAddr == state.macaddr) {
                logDebug "push(): requested slave is same as master"
                return // skip trying to add if the requested slave is the master
            }
            if (isSlaveInZone(slaveDetails.ipAddr, slaveDetails.macAddr)) {
                logDebug "push(): removing slave ${slaveDetails.name}"
                removeZoneSlave(slaveDetails.ipAddr, slaveDetails.macAddr) // slave found in zone, so remove it...
                tryToForceSlaveRefresh(slaveDetails.ipAddr)
                return
            } else {
                logDebug "push(): attempting to add slave ${slaveDetails.name}"
                createZone(slaveDetails.ipAddr, slaveDetails.macAddr) // ...otherwise, we need to add it
                tryToForceSlaveRefresh(slaveDetails.ipAddr)
                return
            }
            break
    }
}

void speak(text) {
    playText(text)
}

def createZone(slaveIP, slaveMAC) {
    // setZone and addZoneSlave are equivalent,
    //     according to this forum post: https://developer.bose.com/content/zone-management
    // addZoneSlave is supported in this driver for completeness,
    //    but for simplicity only setZone is used internally

    def body = \
          buildZoneObjectHeader(state.macaddr) + \
          buildMemberObject(slaveIP, slaveMAC) + \
          buildZoneObjectFooter()

    logDebug "createZone(), payload follows"
    logXml "${body}"

    return httpPostExec("setZone", body)
}

def createZoneMulti(slaves) {
    def slaveDetails
    def members = ""
    for (slave in slaves) {
        slaveDetails = lookupSlaveDetailsFromName(slave)
        if (slaveDetails) {
            if (slaveDetails.macAddr == state.macaddr) continue
            members += buildMemberObject(slaveDetails.ipAddr, slaveDetails.macAddr)
        }
    }
    def body = \
          buildZoneObjectHeader(state.macaddr) + \
          members + \
          buildZoneObjectFooter()

    logDebug "createZoneMulti(), payload follows"
    logXml "${body}"
    return httpPostExec("setZone", body)
}

void inspectZone() {
    def zone = readZone(false)
    if (null == zone) return

    clearZoneMapCur() // clear current zone map so that it stays current only with actual zone
    def name = ""
    for (member in zone.member) {
        name = getDeviceNameByIP(member.@ipaddress.toString())
        if (("" != name) && (null != name)) {
            addDevToSlaveMap(member.@ipaddress.toString(), member.text(), name)
            addDevToZoneMapCur(name)
        }
        name = ""
    }
}

def addZoneSlave(slaveIP, slaveMAC) {
    // setZone and addZoneSlave are equivalent,
    //     according to this forum post: https://developer.bose.com/content/zone-management
    // addZoneSlave is supported in this driver for completeness,
    //    but for simplicity only setZone is used internally

    def body = \
          buildZoneObjectHeader(state.macaddr) + \
          buildMemberObject(slaveIP, slaveMAC) + \
          buildZoneObjectFooter()

    logDebug "addZoneSlave(), payload follows"
    logXml "${body}"
    return httpPostExec("addZoneSlave", body)
}

def removeZoneSlave(slaveIP, slaveMAC) {
    def body = \
          buildZoneObjectHeader(state.macaddr) + \
          buildMemberObject(slaveIP, slaveMAC) + \
          buildZoneObjectFooter()

    logDebug "removeZoneSlave(), payload follows"
    logXml "${body}"
    return httpPostExec("removeZoneSlave", body)
}

void captureZone(zoneNumber) {
    inspectZone()
    def zMC = getZoneMapCur()
    logDebug "captureZone(${zoneNumber}): incoming zoneMapCur = ${zMC}"

    if (!zoneNumber.isInteger()) {
        log.debug "captureZone() failed: zoneNumber must be numeric"
        return
    }

    def zoneNumInt = zoneNumber.toInteger()
    def zoneNumMin = (state.zoneNumMin ? state.zoneNumMin.toInteger() : 10)
    def zoneNumMax = (state.zoneNumMax ? state.zoneNumMax.toInteger() : 20)

    if ((zoneNumInt < zoneNumMin) || (zoneNumInt > zoneNumMax)) {
        log.warn "captureZone() failed: zoneNumber must be in range of ${zoneNumMin}-${zoneNumMax}, inclusive."
        return
    }
    if (checkCommStatus()) {
        state.zoneMap = state.zoneMap ?: [:]
        state.zoneMap[zoneNumber] = zMC
    }
}

void captureContentItem(itemNumber) {
    def np = readNowPlaying(false)
    if (null == np) return

    logDebug "captureContentItem(${itemNumber}): incoming np = ${np}"
    if (!itemNumber.isInteger()) {
        log.warn "captureContentItem() failed: itemNumber must be numeric"
        return
    }

    def itemNumInt = itemNumber.toInteger()
    def itemNumMin = (state.itemNumMin ? state.itemNumMin.toInteger() : 30)
    def itemNumMax = (state.itemNumMax ? state.itemNumMax.toInteger() : 40)

    if ((itemNumInt < itemNumMin) || (itemNumInt > itemNumMax)) {
        log.warn "captureContentItem() failed: itemNumber must be in range of ${itemNumMin}-${itemNumMax}, inclusive."
        return
    }
    if (checkCommStatus()) {
        state.itemMap = state.itemMap ?: [:]
        state.itemMap[itemNumber] = serializeXml(np.ContentItem)
    }
}

void restoreContentItem(itemNumber) {
    if (state.itemMap?.containsKey(itemNumber.toString())) {
        playTrack(state.itemMap[itemNumber.toString()])
    }
}

void emptyZone() {
    inspectZone()
    logDebug "emptyZone(): incoming zoneMapCur = ${getZoneMapCur()}"

    def slaveDetails
    for (slave in getZoneMapCur()) { // attempt to remove all current slaves
        slaveDetails = lookupSlaveDetailsFromName(slave)
        if (null != slaveDetails) {
            if (slaveDetails.macAddr == state.macaddr) { // skip trying to remove if the requested slave is the master
                logDebug "requested slave is same as master"
                continue
            }
            logDebug "emptyZone() removing slave: ${slaveDetails}"
            removeZoneSlave(slaveDetails.ipAddr, slaveDetails.macAddr)
        } else {
            logDebug "emptyZone() slave not known: ${slave}"
        }
    }
}

def restoreZoneMulti(zoneNumber) {
    def zoneNumStr = zoneNumber.toString()

    if (!state.zoneMap?.containsKey(zoneNumStr)) {
        log.warn "restoreZoneMulti() Invalid zone number: ${zoneNumber}"
        return
    }
    emptyZone() // remove all slaves from existing zone
    return createZoneMulti(state.zoneMap[zoneNumStr]) // restore the saved zone
}

def executeCommand(suffix) {
    return httpGetExec(suffix)
}

def operateKey(state, value) {
    logDebug "operateKey(${state}, ${value})"
    return httpPostExec("key", "<key state=${state} sender=Gabbo>${value}</key>")
}

static String buildZoneObjectHeader(macAddr) {
    return "<zone master=\"${macAddr}\">\n"
}

static String buildZoneObjectFooter() {
    return "</zone>"
}

static String buildMemberObject(ipAddr, macAddr) {
    return "<member ipaddress=\"${ipAddr}\">${macAddr}</member>\n"
}

def postPlayInfoObject(url, volume) {
    if (null == appKey) {
        log.warn "Consumer Key unspecified. \
                    Apply for a key in the Bose developer portal and enter it into the device Preferences."
        return
    }

    def body = "<play_info>\n<app_key>${appKey}</app_key>\n<url>${url}</url>\n<service>Device Notification</service>\n\
                            <reason></reason>\n<message></message>\n<volume>${volume}</volume>\n</play_info>"
    logDebug "postPlayInfoObject(), payload follows"
    logXml "${body}"
    return httpPostExec("speaker", body)
}

def readInfo(useCachedValues) {
    def resp_xml
    if (useCachedValues) {
        resp_xml = getInfo()
    } else {
        resp_xml = httpGetExec("info")
        if (resp_xml) {
            sendEvent(name: "commStatus", value: "good")
            setInfo(resp_xml)
        } else {
            sendEvent(name: "commStatus", value: "error")
        }
    }
    return resp_xml
}

void setInfo(info) {
    logDebug "info = ${escapeXml(serializeXml(info))}"
    state.info = escapeXml(serializeXml(info))
}

GPathResult getInfo() {
    return parseXml(unescapeXml(state.info))
}

def readNowPlaying(useCachedValues) {
    def resp_xml
    if (useCachedValues) {
        resp_xml = getNowPlaying()
    } else {
        resp_xml = httpGetExec("now_playing")
        if (resp_xml) {
            sendEvent(name: "commStatus", value: "good")
            setNowPlaying(resp_xml)
        } else {
            sendEvent(name: "commStatus", value: "error")
        }
    }
    return resp_xml
}

void setNowPlaying(nowPlaying) {
    logDebug "now_playing = ${escapeXml(serializeXml(nowPlaying))}"
    state.nowPlaying = escapeXml(serializeXml(nowPlaying))
}

GPathResult getNowPlaying() {
    return parseXml(unescapeXml(state.nowPlaying))
}

def readZone(useCachedValues) {
    def resp_xml
    if (useCachedValues) {
        resp_xml = getZone()
    } else {
        resp_xml = httpGetExec("getZone")
        if (resp_xml) {
            sendEvent(name: "commStatus", value: "good")
            setZone(resp_xml)
        } else {
            sendEvent(name: "commStatus", value: "error")
        }
    }
    return resp_xml
}

def setZone(zone) {
    logDebug "getZone = ${escapeXml(serializeXml(zone))}"
    state.getZone = escapeXml(serializeXml(zone))
}

GPathResult getZone() {
    return parseXml(unescapeXml(state.getZone))
}

int readVolume() {
    def resp_xml = httpGetExec("volume")
    if (resp_xml) {
        sendEvent(name: "commStatus", value: "good")
        sendEvent(name: "volume", value: resp_xml.targetvolume.toInteger())
        sendEvent(name: "level", value: resp_xml.targetvolume.toInteger())
        return resp_xml.targetvolume.toInteger()
    } else {
        sendEvent(name: "commStatus", value: "error")
    }
}

def readMute() {
    def resp_xml = httpGetExec("volume")
    if (resp_xml) {
        sendEvent(name: "commStatus", value: "good")
        sendEvent(name: "mute", value: resp_xml.muteenabled.toBoolean() ? "muted" : "unmuted")
        return resp_xml.muteenabled.toBoolean()
    } else {
        sendEvent(name: "commStatus", value: "error")
    }
}

boolean isPoweredOn() {
    def onStatuses = ["PLAY_STATE", "PAUSE_STATE", "STOP_STATE", "BUFFERING_STATE"]
    def np = readNowPlaying(false)
    if (null == np) {
        logDebug "failed to get now_playing status"
        return null
    }
    return onStatuses.contains(np.playStatus)
}

boolean isZoneMaster(gz) {
    if (null == gz) return null

    def ourMac = state.macaddr
    def masterMac1 = gz.@master.text()
    if (isZoneValid(gz)) return ourMac == masterMac1 // we match and are the master
    return null
}

static boolean isZoneValid(gz) {
    return gz != null ? "" != gz.@master.text() : false // this zone passes the sniff test
}

void doAdjustSlaveVolumes(volumeLevel) {
    def gz = readZone(false)
    if (null == gz) return
    for (thisMember in gz.member) {
        httpAsyncPostExec(thisMember.@ipaddress.toString(), "volume", "<volume>${volumeLevel.toString()}</volume>")
    }
}

void operateSlaveKeys(state, value) {
    def gz = readZone(false)
    if (null == gz) return
    for (thisMember in gz.member) {
        httpAsyncPostExec(thisMember.@ipaddress.toString(), "key", "<key state=${state} sender=Gabbo>${value}</key>")
    }
}

void toggleSlaveMutes() {
    operateSlaveKeys("press", "MUTE")
}

void toggleSlavePowers() {
    operateSlaveKeys("press", "POWER")
    operateSlaveKeys("release", "POWER")
}

void tryToForceSlaveRefresh(ipAddr) {
    // read volume, then set to same.

    // this attempts to cause a driver refresh
    // for a slave that is added or removed from this
    // driver's zone.

    def resp_xml = httpGetExec(ipAddr, "volume")
    if (resp_xml && !resp_xml?.targetvolume?.isEmpty()) {
        httpAsyncPostExec(ipAddr, "volume", "<volume>${resp_xml.targetvolume.toInteger().toString()}</volume>")
    }
}

boolean isSlaveInZone(ipAddr, macAddr) {
    def gz = readZone(false)
    if (null == gz) return false

    for (thisMember in gz.member) {
        if (null == thisMember.@ipaddress) continue
        if (thisMember.text() == macAddr) {
            if (thisMember.@ipaddress.text() == ipAddr) return true
        }
    }
    return false
}

String getDeviceNameByIP(ipAddr) {
    return httpGetExec(ipAddr, "info")?.name?.text()
}

void addDevToSlaveMap(ipAddrSl, macAddrSl, nameSl) {
    if (null == state.slaveMap) state.slaveMap = [:]
    state.slaveMap[nameSl] = [ipAddr: ipAddrSl, macAddr: macAddrSl, name: nameSl] // map of all known slaves
}

def clearZoneMapCur() {
    state.zoneMapCur = []
}

def addDevToZoneMapCur(nameSl) {
    state.zoneMapCur.add(nameSl) // map of current slaves in zone
}

def getZoneMapCur() {
    return state.zoneMapCur
}

def lookupSlaveDetailsFromName(name) {
    return state.slaveMap != null ? state.slaveMap[name] : null // return slaveMap entry if name is found; or, return null
}

@SuppressWarnings('GroovyFallthrough')
boolean checkCommStatus() {
    switch (device.currentValue("commStatus")) {
        case "good":
            logDebug "checkCommStatus() success"
            return true
        case "error":
        case "unknown":
        default:
            logDebug "checkCommStatus() failed"
            return false
    }
}

void parse(message) {
    logXml "parse() message = ${message}"
    def parsedMsg = parseXml(message)
    if (!parsedMsg?.zoneUpdated?.isEmpty()) inspectZone() // if the zones change for any reason, we want to cache any info available

    if ((!parsedMsg?.volumeUpdated?.isEmpty()) || (!parsedMsg?.nowPlayingUpdated?.isEmpty()) || // update everything on any of these changes
            (!parsedMsg?.zoneUpdated?.isEmpty()) || (!parsedMsg?.infoUpdated?.isEmpty())) refresh()
}

void webSocketStatus(String message) {
    logXml "webSocketStatus() = ${message}"
}

String hexToAscii(hexStr) {
    return new String(HexUtils.hexStringToByteArray(hexStr))
}

GPathResult parseXml(resp) {
    def xmlSlurper = new XmlSlurper()
    def resp_xml = null
    try {
        resp_xml = xmlSlurper.parseText(resp.toString())
    } catch (Exception e) {
        log.warn "parse failed: ${e.message}"
    }
    return resp_xml
}

String serializeXml(resp) {
    def xmlUtil = new XmlUtil()
    def xmlString = null
    try {
        xmlString = xmlUtil.serialize(resp).replaceAll("\\<\\?xml(.+?)\\?\\>", "")
    } catch (Exception e) {
        log.warn "serialize failed: ${e.message}"
    }
    return xmlString
}

static String escapeXml(resp) {
    return XmlUtil.escapeXml(resp)
}

static String unescapeXml(resp) {
    return resp.replaceAll(/&lt;/, '<').replaceAll(/&gt;/, '>').replaceAll(/&quot;/, '"')
            .replaceAll(/&apos;/, "'").replaceAll(/&amp;/, '&')
}

String getBaseURI() {
    return getBaseURI(IP_address)
}

static String getBaseURI(ipAddr) {
    return "http://" + ipAddr + ":8090/"
}

def httpGetExec(suffix) {
    httpGetExec(IP_address, suffix)
}

def httpGetExec(ipAddr, suffix) {
    logDebug "httpGetExec(${ipAddr}, ${suffix})"
    def result = null
    try {
        getString = getBaseURI(ipAddr) + suffix
        httpGet(getString.replaceAll(' ', '%20')) { resp ->
            if (resp.data) {
                //logDebug "resp.data = ${resp.data}"
                result = resp.data
            }
        }
    } catch (Exception e) {
        logDebug "httpGetExec() failed: ${e.message}"
    }
    return result
}

def httpPostExec(suffix, body) {
    logXml "httpPostExec(${suffix}, ${body})"
    def result = null
    try {
        getString = getBaseURI() + suffix
        httpPost(getString.replaceAll(' ', '%20'), body) { resp ->
            if (resp.data) {
                //logDebug "resp.data = ${resp.data}"
                result = resp.data
            }
        }
    } catch (Exception e) {
        logDebug "httpPostExec() failed: ${e.message}"
    }
    return result
}

void httpAsyncPostExec(ipaddr, suffix, body) {
    logXml "httpAsyncPostExec(${ipaddr}, ${suffix}, ${body})"
    def uriInt = (getBaseURI(ipaddr) + suffix).replaceAll(' ', '%20')
    def bodyInt = body
    try {
        def postParams = [uri: uriInt,
                requestContentType: 'application/x-www-form-urlencoded',
                contentType: 'application/x-www-form-urlencoded',
                body: bodyInt]
        asynchttpPost("httpAsyncPostCallback", postParams, [body: "${body}"])
    } catch (Exception e) {
        logDebug "httpAsyncPostExec() failed: ${e.message}"
    }
}

void httpAsyncPostCallback(response, data) {
    logXml "httpAsyncPostCallback with status = ${response.getStatus()} from data = ${data}"
    if (!response.hasError()) logDebug "response.getData() = ${response.getData()}"
}

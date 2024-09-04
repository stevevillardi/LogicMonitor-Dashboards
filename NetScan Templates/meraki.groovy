/*******************************************************************************
 * © 2007-2024 - LogicMonitor, Inc. All rights reserved.
 ******************************************************************************/

import com.logicmonitor.common.sse.utils.GroovyScriptHelper as GSH
import com.logicmonitor.mod.Snippets
import com.santaba.agent.AgentVersion
import java.text.DecimalFormat
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// To run in debug mode, set to true
Boolean debug = false
// To enable logging, set to true
Boolean log = false

// Set props object based on whether or not we are running inside a netscan or debug console
def props
try {
    hostProps.get("system.hostname")
    props = hostProps
    debug = true  // set debug to true so that we can ensure we do not print sensitive properties
}
catch (MissingPropertyException) {
    props = netscanProps
}

String org = props.get("meraki.api.org")
String key = props.get("meraki.api.key")
if (!org) {
    throw new Exception("Must provide meraki.api.org to run this script.  Verify necessary credentials have been provided in Netscan properties.")
}
if (!key) {
    throw new Exception("Must provide meraki.api.key credentials to run this script.  Verify necessary credentials have been provided in Netscan properties.")
}

def logCacheContext = "${org}::cisco-meraki-cloud"
Boolean skipDeviceDedupe = props.get("skip.device.dedupe", "false").toBoolean()
String hostnameSource    = props.get("hostname.source", "")?.toLowerCase()?.trim()

Integer collectorVersion = AgentVersion.AGENT_VERSION.toInteger()
 
// Bail out early if we don't have the correct minimum collector version to ensure netscan runs properly
if (collectorVersion < 32400) {
    def formattedVer = new DecimalFormat("00.000").format(collectorVersion / 1000)
    throw new Exception("Upgrade collector running netscan to 32.400 or higher to run full featured enhanced netscan. Currently running version ${formattedVer}.")
}

def modLoader = GSH.getInstance()._getScript("Snippets", Snippets.getLoader()).withBinding(getBinding())
def emit        = modLoader.load("lm.emit", "1.1")
def lmDebugSnip = modLoader.load("lm.debug", "1")
def lmDebug     = lmDebugSnip.debugSnippetFactory(out, debug, log, logCacheContext)
def httpSnip    = modLoader.load("proto.http", "0")
def http        = httpSnip.httpSnippetFactory(props)
def cacheSnip   = modLoader.load("lm.cache", "0")
def cache       = cacheSnip.cacheSnippetFactory(lmDebug, logCacheContext)
// Only initialize lmApi snippet class if customer has not opted out
def lmApi
if (!skipDeviceDedupe) {
    def lmApiSnippet = modLoader.load("lm.api", "0")
    lmApi = lmApiSnippet.lmApiSnippetFactory(props, http, lmDebug)
}
def ciscoMerakiCloudSnip = modLoader.load("cisco.meraki.cloud", "0")
def ciscoMerakiCloud     = ciscoMerakiCloudSnip.ciscoMerakiCloudSnippetFactory(props, lmDebug, cache, http)

String orgDisplayname      = props.get("meraki.api.org.name") ?: "MerakiOrganization"
String rootFolder          = props.get("meraki.api.org.folder") ? props.get("meraki.api.org.folder") + "/" : ""
String serviceUrl          = props.get("meraki.service.url")
def disableSwitches        = props.get("meraki.disableswitches")?.toBoolean() ?: false
def addDeviceApiKey        = props.get("meraki.api.key.addToAllDevices") == null ? true : props.get("meraki.api.key.addToAllDevices").toBoolean()
def networksWhitelist      = props.get("meraki.api.org.networks")?.tokenize(",")?.collect{ it.trim() }
def collectorNetworksCSV   = props.get("meraki.api.org.collector.networks.csv")
def collectorNetworkInfo
if (collectorNetworksCSV) collectorNetworkInfo = processCollectorNetworkInfoCSV(collectorNetworksCSV)

String merakiSnmpCommunity = props.get("meraki.snmp.community.pass") 
String merakiSnmpSecurity  = props.get("meraki.snmp.security") 
String merakiSnmpAuth      = props.get("meraki.snmp.auth") 
String merakiSnmpAuthToken = props.get("meraki.snmp.authToken.pass") 
String merakiSnmpPriv      = props.get("meraki.snmp.priv") 
String merakiSnmpPrivToken = props.get("meraki.snmp.privToken.pass") 

// Get information about devices that already exist in LM portal
List fields = ["name", "currentCollectorId", "displayName"]
Map args = ["size": 1000, "fields": fields.join(",")]
def lmDevices
// But first determine if the portal size is within a range that allows us to get all devices at once
def pathFlag, portalInfo, timeLimitSec, timeLimitMs
if (!skipDeviceDedupe) {
    portalInfo = lmApi.apiCallInfo("Devices", args)
    timeLimitSec = props.get("lmapi.timelimit.sec", "60").toInteger()
    timeLimitMs = (timeLimitSec) ? Math.min(Math.max(timeLimitSec, 30), 120) * 1000 : 60000 // Allow range 30-120 sec if configured; default to 60 sec

    if (portalInfo.timeEstimateMs > timeLimitMs) {
        lmDebug.LMDebugPrint("Estimate indicates LM API calls would take longer than time limit configured.  Proceeding with individual queries by display name for each device to add.")
        lmDebug.LMDebugPrint("\t${portalInfo}\n\tNOTE:  Time limit is set to ${timeLimitSec} seconds.  Adjust this limit by setting the property lmapi.timelimit.sec.  Max 120 seconds, min 30 seconds.")
        pathFlag = "ind"
    }
    else {
        lmDebug.LMDebugPrint("Response time indicates LM API calls will complete in a reasonable time range.  Proceeding to collect info on all devices to cross reference and prevent duplicate device creation.\n\t${portalInfo}")
        pathFlag = "all"
        lmDevices = lmApi.getPortalDevices(args)
    }
}

List<Map> resources = []

def now = new Date()
def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.s z"
TimeZone tz = TimeZone.getDefault()
Map duplicateResources = [
    "date" : now.format(dateFormat, tz),
    "message" : "Duplicate device names and display names, keyed by display name that would be assigned by the netscan, found within LogicMonitor portal.  Refer to documentation for how to resolve name collisions using 'hostname.source' netscan property.",
    "total" : 0,
    "resources" : []
]

// Gather data from cache if running in debug otherwise make API requests
def orgDevicesStatuses
def orgDevices
if (debug) {
    orgDevicesStatuses = cache.cacheGet("${org}::organizationDeviceStatuses")
    if (orgDevicesStatuses) orgDevicesStatuses = ciscoMerakiCloud.slurper.parseText(orgDevicesStatuses).values()
} else {
    orgDevicesStatuses = ciscoMerakiCloud.httpGetWithPaging("/organizations/${org}/devices/statuses")
    if (orgDevicesStatuses) orgDevicesStatuses = ciscoMerakiCloud.slurper.parseText(orgDevicesStatuses)
}

// Organization device status data is required; we cannot proceed without it
if (!orgDevicesStatuses) {
    throw new Exception("Error occurred during organizations/${org}/devices/statuses HTTP GET: ${orgDevicesStatuses}.")
}

if (debug) {
    orgDevices = cache.cacheGet("${org}::organizationDevices")
    if (orgDevices) orgDevices = ciscoMerakiCloud.slurper.parseText(orgDevices).values()
} else {
    orgDevices = ciscoMerakiCloud.httpGetWithPaging("/organizations/${org}/devices")
    if (orgDevices) orgDevices = ciscoMerakiCloud.slurper.parseText(orgDevices)
}

def orgNetworkNames = [:]
def orgNetworks
if (debug) {
    orgNetworks = cache.cacheGet("${org}::organizationNetworks")
    if (orgNetworks) orgNetworks = ciscoMerakiCloud.slurper.parseText(orgNetworks).values()
} else {
    orgNetworks = ciscoMerakiCloud.httpGetWithPaging("/organizations/${org}/networks")
    if (orgNetworks) orgNetworks = ciscoMerakiCloud.slurper.parseText(orgNetworks)
}

// Network data is required; we cannot proceed without it
if (!orgNetworks) {
    throw new Exception("Error occurred during organizations/${org}/networks HTTP GET: ${orgNetworks}.")
}

orgNetworks.each { orgNetwork ->
    def networkId = orgNetwork.get("id")
    def networkName = orgNetwork.get("name")
    orgNetworkNames.put(networkId, networkName)
}

orgDevicesStatuses.each { orgDevice ->
    def networkId   = orgDevice.get("networkId")
    def networkName = orgNetworkNames.get(networkId)
    // Verify this network should be included based on customer whitelist configuration
    if (networksWhitelist != null && !networksWhitelist.contains(networkId)) return

    def ip = orgDevice.get("lanIp")
    if (ip == null) ip = orgDevice.get("publicIp")
    if (ip == null) ip = orgDevice.get("wan1Ip")
    if (ip == null) ip = orgDevice.get("wan2Ip")
    def name = orgDevice.get("name")

    // Skip devices that do not have an IP and name; we must have at least one to create a device
    if (!ip && !name) return

    def productType = orgDevice.get("productType")
    def serial = orgDevice.get("serial")
    def model = orgDevice.get("model")

    String displayName = name

    // Check for existing device in LM portal with this displayName; set to false initially and update to true when dupe found
    def deviceMatch = false
    // If customer has opted out of device deduplication checks, we skip the lookups where we determine if a match exists and proceed as false
    if (!skipDeviceDedupe) {
        if (pathFlag == "ind") {
            deviceMatch = lmApi.findPortalDevice(displayName, args)
            if (!deviceMatch) deviceMatch = lmApi.findPortalDeviceByName(ip, args)
        }
        else if (pathFlag == "all") {
            deviceMatch = lmApi.checkExistingDevices(displayName, lmDevices)
            if (!deviceMatch) deviceMatch = lmApi.checkExistingDevicesByName(ip, lmDevices)
        }
    }
    if (deviceMatch) {
        // Log duplicates that would cause additional devices to be created; unless these entries are resolved, they will not be added to resources for netscan output
        def collisionInfo = [
            (displayName) : [
                "Netscan" : [
                    "hostname"    : ip,
                    "displayName" : displayName
                ],
                "LM" : [
                    "hostname"    : deviceMatch.name,
                    "collectorId" : deviceMatch.currentCollectorId,
                    "displayName" : deviceMatch.displayName
                ],
                "Resolved" : false
            ]
        ]

        // If user specified to use LM hostname on display name match, update hostname variable accordingly
        // and flag it as no longer a match since we have resolved the collision with user's input
        if (hostnameSource == "lm" || hostnameSource == "logicmonitor") {
            ip = deviceMatch.name
            deviceMatch = false
            collisionInfo[displayName]["Resolved"] = true
        }
        // If user specified to use netscan data for hostname, update the display name to make it unique
        // and flag it as no longer a match since we have resolved the collision with user's input
        else if (hostnameSource == "netscan") {
            // Update the resolved status before we change the displayName
            collisionInfo[displayName]["Resolved"] = true
            displayName = "${displayName} - ${ip}"
            deviceMatch = false
        }

        duplicateResources["resources"].add(collisionInfo)
    }

    // Verify we have minimum requirements for device creation
    if ((ip || productType == "sensor") && networkId && productType && serial) {
        if (ip == "127.0.0.1") ip = name
        if (!name) name = ip
        def deviceProps = [
            "meraki.api.org"          : emit.sanitizePropertyValue(org),
            "meraki.api.network"      : emit.sanitizePropertyValue(networkId),
            "meraki.api.network.name" : emit.sanitizePropertyValue(networkName),
            "meraki.serial"           : emit.sanitizePropertyValue(serial)
        ]

        if (addDeviceApiKey) deviceProps.put("meraki.api.key", key)

        if (networksWhitelist != null) deviceProps.put("meraki.api.org.networks", emit.sanitizePropertyValue(networksWhitelist))
        if (disableSwitches) deviceProps.put("meraki.disableswitches", "true")

        if (merakiSnmpCommunity) deviceProps.put("meraki.snmp.community.pass", merakiSnmpCommunity)
        if (merakiSnmpSecurity) deviceProps.put("meraki.snmp.security", merakiSnmpSecurity)
        if (merakiSnmpAuth) deviceProps.put("meraki.snmp.auth", merakiSnmpAuth)
        if (merakiSnmpAuthToken) deviceProps.put("meraki.snmp.authToken.pass", merakiSnmpAuthToken)
        if (merakiSnmpPriv) deviceProps.put("meraki.snmp.priv", merakiSnmpPriv)
        if (merakiSnmpPrivToken) deviceProps.put("meraki.snmp.privToken.pass", merakiSnmpPrivToken)

        if (serviceUrl) deviceProps.put("meraki.service.url", serviceUrl)

        def tags = orgDevices?.find { it.serial == serial}?.tags
        if (tags && tags != "[]") deviceProps.put("meraki.tags", tags.join(","))
    
        def firmware = orgDevices.find { it.serial == serial}?.firmware
        if (firmware) deviceProps.put("meraki.firmware", firmware)

        def address = orgDevices.find { it.serial == serial}?.address
        if (address) deviceProps.put("location", address)

        if (productType == "camera") {
            deviceProps.put("system.categories", "CiscoMerakiCamera")
        } else if (productType == "switch") {
            deviceProps.put("system.categories", "CiscoMerakiSwitch")
        } else if (productType == "wireless") {
            deviceProps.put("system.categories", "CiscoMerakiWireless")
        } else if (productType == "appliance") {
            deviceProps.put("system.categories", "CiscoMerakiAppliance")
        } else if (productType == "cellularGateway") {
            deviceProps.put("system.categories", "CiscoMerakiCellularGateway")
        } else if (productType == "sensor") {
            ip = "${name}.invalid"
            if (model == "MT10") deviceProps.put("system.categories", "CiscoMerakiTemperatureSensor,NoPing")
            if (model == "MT12") deviceProps.put("system.categories", "CiscoMerakiWaterSensor,NoPing")
            if (model == "MT20") deviceProps.put("system.categories", "CiscoMerakiDoorSensor,NoPing")
            if (model == "MT40") deviceProps.put("system.categories", "CiscoMerakiPowerSensor,NoPing")        
        }

        deviceProps.put("meraki.productType", productType)

        // Set group and collector ID based on user CSV inputs if provided
        def collectorId = null
        Map resource = [:]
        if (collectorNetworkInfo) {
            collectorId = collectorNetworkInfo[networkId]["collectorId"]
            def folder      = collectorNetworkInfo[networkId]["folder"]
            resource = [
                "hostname"    : ip,
                "displayname" : name,
                "hostProps"   : deviceProps,
                "groupName"   : ["${rootFolder}${folder}/${networkName}"],
                "collectorId" : collectorId
            ]
        } else {
            resource = [
                "hostname"    : ip,
                "displayname" : name,
                "hostProps"   : deviceProps,
                "groupName"   : ["${rootFolder}${orgDisplayname}/${networkName}"]
            ]
        }

        // Only add the collectorId field to resource map if we found a collector ID above
        if (collectorId) {
            resource["collectorId"] = collectorId
            duplicateResources["resources"][displayName]["Netscan"][0]["collectorId"] = collectorId
        }

        if (!deviceMatch) {
            resources.add(resource)
        }
    }
}

lmDebug.LMDebugPrint("Duplicate Resources:")
duplicateResources.resources.each {
    lmDebug.LMDebugPrint("\t${it}")
}

emit.resource(resources, debug)

return 0

/**
 * Processes a CSV with headers collector id, network organization device name, folder, and network
 * @param filename String
 * @return collectorInfo Map with network id as key and Map of additional attributes as value
*/
Map processCollectorNetworkInfoCSV(String filename) {
    // Read file into memory and split into list of lists
    def csv = newFile(filename, "csv")
    def rows = csv.readLines()*.split(",")
    def collectorInfo = [:]

    // Verify whether headers are present and expected values
    // Sanitize for casing and extra whitespaces while gathering headers
    def maybeHeaders = rows[0]*.toLowerCase()*.trim()
    if (maybeHeaders.contains("collector id") && maybeHeaders.contains("folder") && maybeHeaders.contains("network")) {
        Map headerIndices = [:]
        maybeHeaders.eachWithIndex{ val, i ->
            headerIndices[val] = i
        }
        // Index values for headers to ensure we key the correct index regardless of order
        def ni = headerIndices["network"]
        def ci = headerIndices["collector id"]
        def fi = headerIndices["folder"]

        // Remove headers from dataset
        def data = rows[1..-1]
        // Build a map indexed by network for easy lookups later
        data.each{ entry ->
            collectorInfo[entry[ni]] = [
                    "collectorId" : entry[ci],
                    "folder"      : entry[fi]
                ]
        }
    }
    // Bail out early if we don't have the expected headers in the provided CSV
    else {
        throw new Exception(" Required headers not provided in CSV.  Please provide \"Collector ID\", \"Network Organization Device Name\", \"Folder Name, \"and Network (case insensitive).  Headers provided: \"${rows[0]}\"")
    }

    return collectorInfo
}

/**
 * Sanitizes filepath and instantiates File object
 * @param filename String
 * @param fileExtension String
 * @return File object using sanitized relative filepath
*/
File newFile(String filename, String fileExtension) {
    // Ensure relative filepath is complete with extension type
    def filepath
    if (!filename.startsWith("./")) {
        filepath = "./${filename}"
    }
    if (!filepath.endsWith(".${fileExtension}")) {
        filepath = "${filepath}.${fileExtension}"
    }

    return new File(filepath)
}
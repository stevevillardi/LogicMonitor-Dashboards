/*******************************************************************************
 * © 2007-2023 - LogicMonitor, Inc. All rights reserved.
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
String serviceUrl          = props.get("meraki.service.url") ?: "https://api.meraki.com/api/v1"
def disableSwitches        = props.get("meraki.disableswitches")?.toBoolean() ?: false
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
 
String cacheFilename = "Meraki_Org_${org}_devices"
Map sensitiveProps = [
    "meraki.api.key"   : key,
    "meraki.snmp.community.pass" : merakiSnmpCommunity,
    "meraki.snmp.auth"           : merakiSnmpAuth,
    "meraki.snmp.authToken.pass" : merakiSnmpAuthToken,
    "meraki.snmp.privToken.pass" : merakiSnmpPrivToken
]
 
// Determine whether there are devices cached on disk that still need to be added from previous netscan runs
if (processResourcesJson(emit, cacheFilename, sensitiveProps, lmDebug)) return 0
 
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
    "message" : "Duplicate display names found within LogicMonitor portal wherein hostname in LM does not match hostname in Netscan output.  Refer to documentation for how to resolve name collisions using 'hostname.source' netscan property.",
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
 
    def ip = orgDevice.get("lanIp") ?: orgDevice.get("publicIp")
    def name = orgDevice.get("name")
    def productType = orgDevice.get("productType")
    def serial = orgDevice.get("serial")
 
    String displayName = name
 
    // Check for existing device in LM portal with this displayName; set to false initially and update to true when dupe found
    def deviceMatch = false
    // If customer has opted out of device deduplication checks, we skip the lookups where we determine if a match exists and proceed as false
    if (!skipDeviceDedupe) {
        if (pathFlag == "ind") {
            deviceMatch = lmApi.findPortalDevice(displayName, args)
        }
        else if (pathFlag == "all") {
            deviceMatch = lmApi.checkExistingDevices(displayName, lmDevices)
        }
    }
    if (deviceMatch) {
        // Log duplicates that would cause additional devices to be created; unless these entries are resolved, they will not be added to resources for netscan output
        if (ip != deviceMatch.name) {
            def collisionInfo = [
                (displayName) : [
                    "Netscan" : [
                        "hostname"    : ip
                    ],
                    "LM" : [
                        "hostname"    : deviceMatch.name,
                        "collectorId" : deviceMatch.currentCollectorId
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
        // Don't worry about matches where the hostname values are the same
        // These will update via normal netscan processing and should be ignored
        else {
            deviceMatch = false
        }
    }
 
    // Verify we have minimum requirements for device creation
    if (ip && networkId && productType && serial) {
        if (ip == "127.0.0.1") ip = name
        if (!name) name = ip
        def deviceProps = [
            "meraki.api.key"          : key,
            "meraki.api.org"          : emit.sanitizePropertyValue(org),
            "meraki.api.network"      : emit.sanitizePropertyValue(networkId),
            "meraki.api.network.name" : emit.sanitizePropertyValue(networkName),
            "meraki.serial"           : emit.sanitizePropertyValue(serial)
        ]
 
        if (networksWhitelist != null) deviceProps.put("meraki.api.org.networks", emit.sanitizePropertyValue(networksWhitelist))
        if (disableSwitches) deviceProps.put("meraki.disableswitches", "true")
 
        if (merakiSnmpCommunity) deviceProps.put("meraki.snmp.community.pass", merakiSnmpCommunity)
        if (merakiSnmpSecurity) deviceProps.put("meraki.snmp.security", merakiSnmpSecurity)
        if (merakiSnmpAuth) deviceProps.put("meraki.snmp.auth", merakiSnmpAuth)
        if (merakiSnmpAuthToken) deviceProps.put("meraki.snmp.authToken.pass", merakiSnmpAuthToken)
        if (merakiSnmpPriv) deviceProps.put("meraki.snmp.priv", merakiSnmpPriv)
        if (merakiSnmpPrivToken) deviceProps.put("meraki.snmp.privToken.pass", merakiSnmpPrivToken)
 
        def tags = orgDevices?.find { it.serial == serial}?.tags
        if (tags && tags != "[]") deviceProps.put("meraki.tags", tags.join(","))
     
        def firmware = orgDevices.find { it.serial == serial}?.firmware
        if (firmware) deviceProps.put("meraki.firmware", firmware)
 
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
 
emitWriteJsonResources(emit, cacheFilename, resources, lmDebug)
 
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
 
////////////////////////////////////////////////////////////// DEVICE BATCHING METHODS //////////////////////////////////////////////////////////////
/**
 * Replaces cached props stored with bogus values with their correct values
 * @param cachedProps Map of hostProps values stored in file cache
 * @param sensitiveProps Map of sensitive properties configured in the netscan to use for updating cachedProps values
 * @return completeHostProps Map updated hostProps with no bogus values
*/
Map processCachedHostProps(Map cachedProps, Map sensitiveProps) {
    Map completeHostProps = cachedProps.collectEntries{ k, v ->
                                if (sensitiveProps.containsKey(k)) {
                                    return [k as String, sensitiveProps[k]]
                                }
                                else {
                                    return [k as String, v as String]
                                }
                            }
    // Verify that we do not have any remaining properties with fake values; stop the show if we do
    def missingKeys = completeHostProps.findAll{ k,v -> v == "***" }
    if (missingKeys) {
        throw new Exception(" Unable to update all cached sensitive properties with appropriate values.  Check Netscan properties and ensure the following keys have been added with values other than ***:\n\t${missingKeys.keySet().join(",")}")
    }
    return completeHostProps
}
 
 
/**
 * Processes a JSON file representing resources cached to disk on the collector
 * @param emit Snippet object for lm.emit (requires version 1.0)
 * @param filename String
 * @param sensitiveProps Map of sensitive properties configured in the netscan to use for updating cachedProps values
 * @param lmDebug Snippet object class instantiation of lm.debug (requires version 1.0)
 * @return Boolean indicator of whether processing was successful
*/
Boolean processResourcesJson(emit, String filename, Map sensitiveProps, lmDebug) {
    File cacheFile = newFile(filename, "json")
    def cachedDevices
    try {
        cachedDevices = new JsonSlurper().parse(cacheFile)
    }
    catch (JsonException) {
        lmDebug.LMDebugPrint("No file found under ${cacheFile.name}; proceeding with API calls to retrieve devices.\n")
        return false
    }
 
    if (!cachedDevices) {
        lmDebug.LMDebugPrint("No cached devices found in ${cacheFile.name}; proceeding with API calls to retrieve devices.\n")
        return false
    }
    lmDebug.LMDebugPrint("${cachedDevices.size()} devices retrieved from cache file ${cacheFile.name}")
 
    // Updated cached devices to include proper values for sensitive properties stored in cache
    cachedDevices.each{ device ->
        if (device["hostProps"]) device["hostProps"] = processCachedHostProps(device["hostProps"], sensitiveProps)
    }
 
    emitWriteJsonResources(emit, filename, cachedDevices, lmDebug)
    return true
}
 
 
/**
 * Output resources to stdout and cache any remainders to JSON file on collector disk
 * @param emit Snippet object for lm.emit (requires version 1.0)
 * @param filename String
 * @param resources List<Map> resources to be added from netscan
 * @param lmDebug Snippet object class instantiation of lm.debug (requires version 1.0)
*/
def emitWriteJsonResources(emit, String filename, List<Map> resources, lmDebug) {
    def chunkSize = 600
    def chunk = Math.min(resources.size(), chunkSize)
    lmDebug.LMDebugPrint("Adding ${chunk} devices.")
    // Output resources in chunk size deemed safe by platform team
    emit.resource(resources[0..chunk-1], lmDebug.debug)
  
    File cacheFile = newFile(filename, "json")
    // If the number of resources is less than or equal to our chunk size, our batching is complete and we can delete the file and exit
    if (resources.size() <= chunk) {
        cacheFile.delete()
        lmDebug.LMDebugPrint("All known devices have been reported.")
        return
    }
    // Remove sensitive properties prior to storing data in cache file; hardcode to true to ensure props are masked regardless of debug mode
    def remainingResources = emit.sanitizeResourceSensitiveProperties(resources, true)
    remainingResources = remainingResources[chunk..-1]
    def jsonRR = JsonOutput.toJson(remainingResources)
    // println JsonOutput.prettyPrint(jsonRR) // Uncomment for debugging purposes if needed
 
    lmDebug.LMDebug("Caching ${remainingResources.size()} devices to disk to add to portal in upcoming netscan executions.")
    cacheFile.write(jsonRR)
    return
}
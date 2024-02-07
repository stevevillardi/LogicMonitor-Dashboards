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
debug = false
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
 
String user = props.get("cisco.catalyst.center.user", props.get("cisco.dna.center.user"))
String pass = props.get("cisco.catalyst.center.pass", props.get("cisco.dna.center.pass"))
String catalystCenterHost = props.get("cisco.catalyst.center.host", props.get("cisco.dna.center.host"))
if (!user) {
    throw new Exception("Must provide cisco.catalyst.center.user to run this script.  Verify necessary credentials have been provided in Netscan properties.")
}
if (!pass) {
    throw new Exception("Must provide cisco.catalyst.center.pass credentials to run this script.  Verify necessary credentials have been provided in Netscan properties.")
}
if (!catalystCenterHost) {
    throw new Exception("Must provide cisco.catalyst.center.host to run this script.  Verify necessary properties have been provided in Netscan properties.")
}
 
def logCacheContext = "${catalystCenterHost}::cisco-catalyst-center"
Boolean skipDeviceDedupe = props.get("skip.device.dedupe", "false").toBoolean()
String hostnameSource    = props.get("hostname.source", "")?.toLowerCase()?.trim()
 
Integer collectorVersion = AgentVersion.AGENT_VERSION.toInteger()
  
// Bail out early if we don't have the correct minimum collector version to ensure netscan runs properly
if (collectorVersion < 32400) {
    def formattedVer = new DecimalFormat("00.000").format(collectorVersion / 1000)
    throw new Exception("Upgrade collector running netscan to 32.400 or higher to run full featured enhanced netscan. Currently running version ${formattedVer}.")
}
  
// Bail out early if we don't have the correct minimum collector version to ensure netscan runs properly
if (collectorVersion < 32400) {
    def formattedVer = new DecimalFormat("00.000").format(collectorVersion / 1000)
    throw new Exception("Upgrade collector running netscan to 32.400 or higher to run full featured enhanced netscan. Currently running version ${formattedVer}.")
}
 
def modLoader = GSH.getInstance()._getScript("Snippets", Snippets.getLoader()).withBinding(getBinding())
def emit        = modLoader.load("lm.emit", "1")
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
def ciscoCatalystCenterSnip = modLoader.load("cisco.catalyst.center", "0")
def ciscoCatalystCenter     = ciscoCatalystCenterSnip.ciscoCatalystCenterSnippetFactory(props, lmDebug, cache, http)
 
String orgDisplayname    = props.get("cisco.catalyst.center.name") ?: "Cisco Catalyst Center"
String orgFolder         = props.get("cisco.catalyst.center.folder") ? props.get("cisco.catalyst.center.folder") + "/" : ""
String serviceUrl        = props.get("cisco.catalyst.service.url") ?: "https://${ciscoCatalystCenter.host}/dna/intent/api/v1"
def sitesWhitelist       = props.get("cisco.catalyst.center.sites")?.tokenize(",")?.collect{ it.trim() }
def deviceFamilies       = props.get("cisco.catalyst.center.devicefamilies")?.tokenize(",")?.collect{ it.trim() }
def collectorSitesCSV    = props.get("cisco.catalyst.center.collector.sites.csv")
def collectorSiteInfo
if (collectorSitesCSV) collectorSiteInfo = processCollectorSiteInfoCSV(collectorSitesCSV)
 
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
    "message" : "Duplicate device names and display names, keyed by display name that would be assigned by the netscan, found within LogicMonitor portal.  Refer to documentation for how to resolve name collisions using 'hostname.source' netscan property.",    "total" : 0,
    "total" : 0,
    "resources" : []
]
 
// Gather data from cache if running in debug otherwise make API requests
def deviceHealth
if (debug) {
    deviceHealth = cache.cacheGet("${ciscoCatalystCenter.host}:deviceHealth")
    if (deviceHealth) {
        deviceHealth = ciscoCatalystCenter.slurper.parseText(deviceHealth).values()
    } else {
        deviceHealth = ciscoCatalystCenter.httpGet("device-health")?.response
        if (deviceHealth) deviceHealth = ciscoCatalystCenter.slurper.parseText(JsonOutput.toJson(deviceHealth))       
    }
} else {
    deviceHealth = ciscoCatalystCenter.httpGet("device-health")?.response
    if (deviceHealth) deviceHealth = ciscoCatalystCenter.slurper.parseText(JsonOutput.toJson(deviceHealth))
}
 
// Device health data is required; we cannot proceed without it
if (!deviceHealth) {
    throw new Exception("Error occurred during /device-health HTTP GET: ${deviceHealth}.")
}
 
// Gather data from cache if running in debug otherwise make API requests
def networkDevice
if (debug) {
    networkDevice = cache.cacheGet("${ciscoCatalystCenter.host}:networkDevice")
    if (networkDevice) {
        networkDevice = ciscoCatalystCenter.slurper.parseText(networkDevice).values()
    } else {
        networkDevice = ciscoCatalystCenter.httpGet("network-device")?.response
        if (networkDevice) networkDevice = ciscoCatalystCenter.slurper.parseText(JsonOutput.toJson(networkDevice))       
    }
} else {
    networkDevice = ciscoCatalystCenter.httpGet("network-device")?.response
    if (networkDevice) networkDevice = ciscoCatalystCenter.slurper.parseText(JsonOutput.toJson(networkDevice))
}
 
// Network device data is required; we cannot proceed without it
if (!networkDevice) {
    throw new Exception("Error occurred during /network-device HTTP GET: ${networkDevice}.")
}
 
// Gather data from cache if running in debug otherwise make API requests
def sites
if (debug) {
    sites = cache.cacheGet("${ciscoCatalystCenter.host}:site")
    if (sites) {
        sites = ciscoCatalystCenter.slurper.parseText(sites).values()
    } else {
        sites = ciscoCatalystCenter.httpGet("site")?.response
        if (sites) sites = ciscoCatalystCenter.slurper.parseText(JsonOutput.toJson(sites))       
    }
} else {
    sites = ciscoCatalystCenter.httpGet("site")?.response
    if (sites) sites = ciscoCatalystCenter.slurper.parseText(JsonOutput.toJson(sites))
}
 
// Sites data is required; we cannot proceed without it
if (!sites) {
    throw new Exception("Error occurred during /site HTTP GET: ${sites}.")
}
 
// Physical topology data is required; we cannot proceed without it
def physicalTopology = ciscoCatalystCenter.httpGet("topology/physical-topology")
if (!physicalTopology) {
    throw new Exception("Error occurred during /topology/physical-topology HTTP GET: ${physicalTopology}.")
}
 
physicalTopology = ciscoCatalystCenter.slurper.parseText(JsonOutput.toJson(physicalTopology))
def ipToSiteId = [:]
physicalTopology.response.nodes.each { node ->
    def ip = node.ip
    def siteId = node.additionalInfo.siteid
    if (ip && siteId) ipToSiteId.put(ip, siteId)
}
 
 
if (deviceFamilies) {
    deviceHealth = deviceHealth.findAll { deviceFamilies.contains(it.deviceFamily) }
} else {
    deviceHealth = deviceHealth.findAll { it.deviceFamily == "UNIFIED_AP" || it.deviceFamily == "WIRELESS_CONTROLLER" }
}
 
deviceHealth.each { device ->
    def ip = device.ipAddress
    def displayName = device.name
    def siteInfo = sites.find { it.id == ipToSiteId[device.ipAddress]}
    def siteId = siteInfo?.id
    def siteName = siteInfo?.name
     
    def associatedWlcIp = networkDevice.find { it.managementIpAddress == ip }?.associatedWlcIp
 
    // Verify this site should be included based on customer whitelist configuration
    if (sitesWhitelist != null && !sitesWhitelist.contains(siteId)) return
 
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
    if (ip && siteId && siteName) {
        def deviceProps = [
            "cisco.catalyst.center.host"            : catalystCenterHost,
            "cisco.catalyst.center.user"            : user,
            "cisco.catalyst.center.pass"            : pass,
            "cisco.catalyst.center.site"            : emit.sanitizePropertyValue(siteName),
            "cisco.catalyst.center.site.id"         : emit.sanitizePropertyValue(siteId)        ]
 
        if (device.deviceFamily == "UNIFIED_AP") {
            deviceProps.put("system.categories", "CiscoCatalystAccessPoint")
            deviceProps.put("cisco.catalyst.center.associatedWlcIp", emit.sanitizePropertyValue(associatedWlcIp))
        } else if (device.deviceFamily == "WIRELESS_CONTROLLER") {
            deviceProps.put("system.categories", "CiscoCatalystWLC")
        }
 
        if (device.location) {
            try {
                def address = device.location.tokenize("/")[-2..-1]?.join(" ")?.tokenize("-")[1..-1]?.join(" ")
                if (address) deviceProps.put("location", address)   
            } catch (Exception e) {
                lmDebug.LMDebugPrint("Exception parsing address: ${e}")
            }
        }
 
        if (sitesWhitelist != null) deviceProps.put("cisco.catalyst.center.sites", emit.sanitizePropertyValue(sitesWhitelist))
 
        // Set group and collector ID based on user CSV inputs if provided
        def collectorId
        Map resource
        if (collectorSiteInfo) {
            collectorId = collectorSiteInfo[siteId]["collectorId"]
            def folder      = collectorSiteInfo[siteId]["folder"]
            resource = [
                "hostname"    : ip,
                "displayname" : displayName,
                "hostProps"   : deviceProps,
                "groupName"   : ["${orgFolder}${folder}/${siteName}"],
                "collectorId" : collectorId
            ]
            resources.add(resource)
        } else {
            resource = [
                "hostname"    : ip,
                "displayname" : displayName,
                "hostProps"   : deviceProps,
                "groupName"   : ["${orgFolder}${orgDisplayname}/${siteName}"]
            ]
            resources.add(resource)
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
 
emit.resource(resources, lmDebug)
 
return 0
 
/**
 * Processes a CSV with headers collector id, folder, and site
 * @param filename String
 * @return collectorInfo Map with site id as key and Map of additional attributes as value
*/
Map processCollectorSiteInfoCSV(String filename) {
    // Read file into memory and split into list of lists
    def csv = newFile(filename, "csv")
    def rows = csv.readLines()*.split(",")
    def collectorInfo = [:]
 
    // Verify whether headers are present and expected values
    // Sanitize for casing and extra whitespaces while gathering headers
    def maybeHeaders = rows[0]*.toLowerCase()*.trim()
    if (maybeHeaders.contains("collector id") && maybeHeaders.contains("folder") && maybeHeaders.contains("site")) {
        Map headerIndices = [:]
        maybeHeaders.eachWithIndex{ val, i ->
            headerIndices[val] = i
        }
        // Index values for headers to ensure we key the correct index regardless of order
        def ni = headerIndices["site"]
        def ci = headerIndices["collector id"]
        def fi = headerIndices["folder"]
 
        // Remove headers from dataset
        def data = rows[1..-1]
        // Build a map indexed by site for easy lookups later
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
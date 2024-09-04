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
def debug = false

Integer collectorVersion = AgentVersion.AGENT_VERSION.toInteger()
String vManageHost, vManageName, rootFolder, sites, host, user, pass, csvFile
Integer port


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

vManageHost = props.get("cisco.sdwan.vmanagehost") // required
def cleanedvManageHost = vManageHost?.replaceAll(":","_") // replace : if found since it's a delimiter

user = props.get("cisco.sdwan.user") // required
pass = props.get("cisco.sdwan.pass") // required

// Check that required properties are present
if (!user || !pass || !vManageHost) {
    throw new Exception("Not all required properties have been configured.  Please update netscan properties to include cisco.sdwan.user, cisco.sdwan.pass, and cisco.sdwan.vmanagehost.")
}

vManageName = props.get("cisco.sdwan.manager.name", "Cisco vManage")
rootFolder = props.get("cisco.sdwan.folder", "Cisco Catalyst SDWAN")
port = (props.get("cisco.sdwan.port", props.get("vmanage.port", "8443"))).toInteger()
Boolean skipDeviceDedupe = props.get("skip.device.dedupe", "false").toBoolean()
String hostnameSource    = props.get("hostname.source", "")?.toLowerCase()?.trim()
statisticsLookBack = (props.get("cisco.sdwan.statisticslookback", "60")).toInteger()


// CSV file containing headers "Collector ID", "Region Folder", "Site ID" and "Site Name" to customize collector assignment and group names created
// Save in /usr/local/logicmonitor/agent/bin directory (Linux)
// or C:\Program Files\LogicMonitor\Agent\bin directory (Windows)
csvFile = props.get("cisco.sdwan.sites.csv", null)

// Bail out early if we don't have the correct minimum collector version to ensure netscan runs properly
if (collectorVersion < 32400) {
    def formattedVer = new DecimalFormat("00.000").format(collectorVersion / 1000)
    throw new Exception(" Upgrade collector running netscan to 32.400 or higher to run full featured enhanced netscan. Currently running version ${formattedVer}.")
}

// Convert to map with site ID as key if CSV file was provided
Map siteInfo = (csvFile) ? processCollectorSiteInfoCSV(csvFile) : null

// Configure all required snippets
def modLoader = GSH.getInstance()._getScript("Snippets", Snippets.getLoader()).withBinding(getBinding())
def emit = modLoader.load("lm.emit", "1.1")
def lmDebugSnip = modLoader.load("lm.debug", "1")
def lmDebug = lmDebugSnip.debugSnippetFactory(out, debug)
def cacheSnip = modLoader.load("lm.cache", "0")
def cache = cacheSnip.cacheSnippetFactory(lmDebug)
def httpSnippet = modLoader.load("proto.http", "0")
def http = httpSnippet.httpSnippetFactory(props)
def ciscoSDWANSnippet = modLoader.load("cisco.sdwan", "0")
def ciscoSDWAN = ciscoSDWANSnippet.ciscoSDWANSnippetFactory(props, lmDebug, cache, http)

// Only initialize lmApi snippet class if customer has not opted out
def lmApi
if (!skipDeviceDedupe) {
    def lmApiSnippet = modLoader.load("lm.api", "0")
    lmApi = lmApiSnippet.lmApiSnippetFactory(props, http, lmDebug)
}

def sessionIdKey = "${vManageHost}:sessionId"
def csrfTokenKey = "${vManageHost}:csrfToken"
def response
def testSessionId = ciscoSDWAN.cache.cacheGet(sessionIdKey)
def siteAllowList = props.get("cisco.sdwan.allowedsites", null)?.tokenize(",")?.collect { it.trim() }

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

if (testSessionId == null) {
    // Since login and logouts can lock a portal we will only do so if we cannot find data for sessionId in cache.
    ciscoSDWAN.sessionId = ciscoSDWAN.login()
    ciscoSDWAN.csrfToken = ciscoSDWAN.getCSRFtoken()
    response = ciscoSDWAN.getDevice()
}
else {
    // Try to do a getDevice with cached sessionId so we don't lock existing resources
    ciscoSDWAN.sessionId = ciscoSDWAN.cache.cacheGet(sessionIdKey)
    ciscoSDWAN.csrfToken = ciscoSDWAN.cache.cacheGet(csrfTokenKey)
    response = ciscoSDWAN.getDevice()
}

if (response) {
    def devices = ciscoSDWAN.slurper.parseText(response)
    // Set variable to store all resources that will be added by netscan
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

    // Keep track of region devices that have been added including their associated sites
    List seenSites = []

    // Add each device into monitoring 
    devices?.'data'?.each{ it ->
        if ((siteAllowList?.contains(it.'site-id')) || siteAllowList == null) {
            String ip = it.'system-ip'.toString()
            String displayName = it.'host-name'.toString()

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

            String siteId = it.'site-id'.toString()
            String setCategory = null
            String deviceType = it.'device-type'

            if (deviceType.contains("edge")) {
                setCategory = "CiscoSDWANEdge"
            }
            if (deviceType.contains("smart")) {
                setCategory = "CiscoSDWANSmart"
            }
            if (deviceType.contains("bond")) {
                setCategory = "CiscoSDWANBond"
            }
            if (deviceType.contains("manage")) {
                setCategory = "CiscoSDWANManage"
            }

            Map deviceProps = [
                    "cisco.sdwan.user"               : emit.sanitizePropertyValue(ciscoSDWAN.user),
                    "cisco.sdwan.pass"               : pass,
                    "cisco.sdwan.vmanagehost"        : emit.sanitizePropertyValue(ciscoSDWAN.vManageHost),
                    "cisco.sdwan.port"               : emit.sanitizePropertyValue(ciscoSDWAN.port),
                    "cisco.sdwan.device.type"        : emit.sanitizePropertyValue(it.'device-type'),
                    "cisco.sdwan.device.id"          : emit.sanitizePropertyValue(it.'system-ip'),
                    "cisco.sdwan.site.id"            : emit.sanitizePropertyValue(siteId),
                    "cisco.sdwan.statisticslookback" : emit.sanitizePropertyValue(statisticsLookBack),
                    "cisco.sdwan.serial.number"      : emit.sanitizePropertyValue(it.'board-serial'),
                    "system.categories"              : setCategory,
            ]

            // Initialize groupName and collectorId, then assign values based on whether info has been provided via CSV
            String groupName
            Integer collectorId
            if (seenSites.contains(siteId)) {
                // If we have site info from the CSV, we can use that to build the user defined group name
                if (siteInfo) {
                    // When data is provided, we build a nested group with the site folder under the region folder
                    groupName = "${siteInfo[siteId].regionFolder}/${siteInfo[siteId].siteName}"
                    collectorId =  siteInfo[siteId].collectorId.toInteger()
                }
                // If not, we'll simply name it with the site ID and not assign collector ID
                else {
                    groupName = "Site ID: ${siteId}"
                }
            }
            // If we haven't seen any info about this site, we'll also simply name it with the site ID and not assign collector ID
            else {
                groupName = "Site ID: ${siteId}"
            }
            Map resource = [
                    "hostname"   : ip,
                    "displayname": displayName,
                    "hostProps"  : deviceProps,
                    "groupName"  : ["${rootFolder}/${groupName}"],
            ]
            // Only add the collectorId field to resource map if we found a collector ID above
            if (collectorId) {
                resource["collectorId"] = collectorId
                duplicateResources["resources"][displayName]["Netscan"]["collectorId"] = collectorId
            }
            // Only add devices that have not been flagged as a display name device match
            // Name collisions that have been resolved are updated to remove the device match flag
            if (!deviceMatch) {
                resources.add(resource)
            } else {
                lmDebug.LMDebug("${displayName} skipped due to unresolved name collison.")
            }
        }
    }

    // After building full map of resources, emit complete JSON of discovered devices
    // Enhanced netscan format (requires collector version 32.400+)
    if (resources) {
        emit.resource(resources, debug)
    }

    // Report devices that already exist in LM via log file named after root folder
    if (duplicateResources["resources"].size() > 0) {
        def netscanDupLog = new File("../logs/NetscanDuplicates/${rootFolder.replaceAll(" ", "_")}.json")
        new File(netscanDupLog.getParent()).mkdirs()
        duplicateResources["total"] = duplicateResources["resources"].size()
        def json = JsonOutput.prettyPrint(JsonOutput.toJson(duplicateResources))
        netscanDupLog.write(json)
        if (hostnameSource) {
            lmDebug.LMDebug("${duplicateResources["resources"].size()} devices found that were resolved with hostname.source=${hostnameSource} in netscan output.  See LogicMonitor/Agent/logs/NetscanDuplicates/${rootFolder.replaceAll(" ", "_")}.json for details.")
        } else {
            lmDebug.LMDebug("${duplicateResources["resources"].size()} devices found that were not reported in netscan output.  See LogicMonitor/Agent/logs/NetscanDuplicates/${rootFolder.replaceAll(" ", "_")}.json for details.")
        }
    }
} else {
    lmDebug.LMDebugLog("NETSCAN: No response from device API call. Credential check likely failed or some other communication error occurred.")
    throw new Exception(" No response data from device. Verify credentials and that we get data back from the device endpoint using them.")
}

return 0


/**
 * Helper function to process CSV with collector id, site device name, folder, site id and site name info
 * @param String filename Filename of the CSV containing user-defined site info
 * @return Map siteInfo with site ID as the key with a Map containing the rest of the info as key value pairs
 */
def processCollectorSiteInfoCSV(String filename) {
    // Read file into memory and split into list of lists
    def csv = newFile(filename, "csv")
    def rows = csv.readLines()*.split(",")
    def siteInfo = [:]

    // Verify whether headers are present and contain expected values
    // Sanitize for casing and extra whitespaces while gathering headers
    def maybeHeaders = rows[0]*.toLowerCase()*.trim()
    if (maybeHeaders.contains("collector id") &&
            maybeHeaders.contains("region folder") &&
            maybeHeaders.contains("site id") &&
            maybeHeaders.contains("site name")) {

        Map headerIndices = [:]
        maybeHeaders.eachWithIndex { val, i ->
            headerIndices[val] = i
        }
        // Index values for headers to ensure we key the correct index regardless of order
        def ci = headerIndices["collector id"]
        def rf = headerIndices["region folder"]
        def si = headerIndices["site id"]
        def sn = headerIndices["site name"]

        // Remove headers from dataset
        def data = rows[1..-1]

        // Build a map of common site names with site ID as key
        data.each { entry ->
            siteId = entry[si].trim()
            siteInfo[siteId] = [
                    "collectorId" : entry[ci].trim(),
                    "regionFolder": entry[rf].trim(),
                    "siteName"    : entry[sn].trim(),
            ]
        }
    }
    // Bail out early if we don't have the expected headers in the provided CSV
    else {
        throw new Exception(" Required headers not provided in CSV.  Please provide \"Collector ID\", \"Region Folder\", \"Site ID\", and \"Site Name\" (case insensitive).  Headers provided: \"${rows[0]}\"")
    }

    return siteInfo
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
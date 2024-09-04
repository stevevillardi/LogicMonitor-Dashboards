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

// Required properties
def creds = props.get("aruba.orchestrator.api.key")
def host = props.get("aruba.orchestrator.host")

// Optional properties
def rootFolder = props.get("aruba.sdwan.org.folder") ?: "Aruba EdgeConnect SDWAN"
Boolean skipDeviceDedupe = props.get("skip.device.dedupe", "false").toBoolean()
String hostnameSource = props.get("hostname.source")?.toLowerCase()?.trim() ?: ""

// Retrieve the collector version
Integer collectorVersion = AgentVersion.AGENT_VERSION.toInteger()

// Bail out early if we don't have the correct minimum collector version to ensure netscan runs properly
if (collectorVersion < 32400) {
    def formattedVer = new DecimalFormat("00.000").format(collectorVersion / 1000)
    throw new Exception(" Upgrade collector running netscan to 32.400 or higher to run full featured enhanced script netscan. Currently running version ${formattedVer}.")
}

if (!creds || !host) {
    throw new Exception("Must provide aruba.orchestrator.api.key and aruba.orchestrator.host to run this script.  Verify necessary fields have been provided in NetScan properties.")
}

// Load the snippets
def modLoader = GSH.getInstance()._getScript("Snippets", Snippets.getLoader()).withBinding(getBinding())
def lmEmit = modLoader.load("lm.emit", "1")
def lmDebug = modLoader.load("lm.debug", "1").debugSnippetFactory((PrintStream) out, debug, log, "aruba-sdwan")
def http = modLoader.load("proto.http", "0").httpSnippetFactory(props)
def cache = modLoader.load("lm.cache", "0").cacheSnippetFactory(lmDebug, "aruba-sdwan")
def arubaSnippet = modLoader.load("aruba.sdwan", "0").arubaSdwanFactory(props, lmDebug, cache, http)

// Only initialize lmApi snippet class if customer has not opted out
def lmApi
if (!skipDeviceDedupe) {
    def lmApiSnippet = modLoader.load("lm.api", "0")
    lmApi = lmApiSnippet.lmApiSnippetFactory(props, http, lmDebug)
}

// CSV file containing headers "Site ID" and "Site Name" to customize group names created
// Save in /usr/local/logicmonitor/agent/bin directory (Linux)
//  or C:\Program Files\LogicMonitor\Agent\bin directory (Windows)
String csvFile = props.get("aruba.sdwan.sites.csv")

// Convert to map with site ID as key
Map siteInfo = null
if (csvFile) {
    siteInfo = processCSV(csvFile)
}

// Get information about devices that already exist in LM portal
List fields = ["name", "currentCollectorId", "displayName"]
Map args = ["size": 1000, "fields": fields.join(",")]
def lmDevices
// But first determine if the portal size is within a range that allows us to get all devices at once
def pathFlag, portalInfo, timeLimitSec, timeLimitMs
if (!skipDeviceDedupe) {
    portalInfo = lmApi.apiCallInfo("Devices", args)
    timeLimitSec = props.get("lmapi.timelimit.sec", "60").toInteger()
    timeLimitMs = (timeLimitSec) ? Math.min(Math.max(timeLimitSec, 30), 120) * 1000 : 60000
    // Allow range 30-120 sec if configured; default to 60 sec

    if (portalInfo.timeEstimateMs > timeLimitMs) {
        lmDebug.LMDebugPrint("Estimate indicates LM API calls would take longer than time limit configured.  Proceeding with individual queries by display name for each device to add.")
        lmDebug.LMDebugPrint("\t${portalInfo}\n\tNOTE:  Time limit is set to ${timeLimitSec} seconds.  Adjust this limit by setting the property lmapi.timelimit.sec.  Max 120 seconds, min 30 seconds.")
        pathFlag = "ind"
    } else {
        lmDebug.LMDebugPrint("Response time indicates LM API calls will complete in a reasonable time range.  Proceeding to collect info on all devices to cross reference and prevent duplicate device creation.\n\t${portalInfo}")
        pathFlag = "all"
        lmDevices = lmApi.getPortalDevices(args)
    }
}
// Get your data and build your list of resources
List<Map> resources = []

def now = new Date()
def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.s z"
TimeZone tz = TimeZone.getDefault()
Map duplicateResources = [
        "date"     : now.format(dateFormat, tz),
        "message"  : "Duplicate display names found within LogicMonitor portal wherein hostname in LM does not match hostname in Netscan output.  Refer to documentation for how to resolve name collisions using 'hostname.source' netscan property.",
        "total"    : 0,
        "resources": []
]


def appliancesData = arubaSnippet.httpGet("appliance")
def orchestratorInfo = arubaSnippet.httpGet("gmsserver/info")

appliancesData?.each { device ->
    String displayName = device.hostName
    def collectorId
    def ip = device.ip
    // Check for existing device in LM portal with this displayName; set to false initially and update to true when dupe found
    def deviceMatch = false
    // If customer has opted out of device deduplication checks, we skip the lookups where we determine if a match exists and proceed as false
    if (!skipDeviceDedupe) {
        if (pathFlag == "ind") {
            deviceMatch = lmApi.findPortalDevice(displayName, args)
        } else if (pathFlag == "all") {
            deviceMatch = lmApi.checkExistingDevices(displayName, lmDevices)
        }
    }

    if (deviceMatch) {
        if (ip != deviceMatch.name) {
            def collisionInfo = [
                    (displayName): [
                            "Netscan" : [
                                    "hostname": ip
                            ],
                            "LM"      : [
                                    "hostname"   : deviceMatch.name,
                                    "collectorId": deviceMatch.currentCollectorId
                            ],
                            "Resolved": false
                    ]
            ]
            if (hostnameSource == "lm" || hostnameSource == "logicmonitor") {
                ip = deviceMatch.name
                collectorId = deviceMatch.currentCollectorId
                deviceMatch = false
                collisionInfo[displayName]["Resolved"] = true
            } else if (hostnameSource == "netscan") {
                collisionInfo[displayName]["Resolved"] = true
                displayName = "${displayName} - ${ip}"
                deviceMatch = false
            }

            duplicateResources["resources"].add(collisionInfo)
        } else {
            deviceMatch = false
        }
    }

    def groupName
    def site = device.site
    if (siteInfo) {
        if (siteInfo[site]) {
            groupName = ["${rootFolder}/${siteInfo[site]["SiteFolder"]}"]
            if (siteInfo[site]["CollectorId"]) {
                collectorId = "${siteInfo[site]["CollectorId"]}"
            }
        }
    } else {
        groupName = device.site ? ["${rootFolder}/${device.site}"] : ["${rootFolder}"]
    }

    Map resource = [
            "hostname"   : ip,
            "displayname": displayName,
            "groupName"  : groupName,
            "hostProps"  : [
                    "aruba.appliance.nepk"               : device?.nePk,
                    "aruba.orchestrator.host"            : host,
                    "aruba.orchestrator.api.key"         : creds,
                    "aruba.orchestrator.serial.number"   : orchestratorInfo?.serialNumber,
                    "aruba.orchestrator.model"           : orchestratorInfo?.model,
                    "aruba.orchestrator.software.version": orchestratorInfo?.release,
                    "aruba.orchestrator.host.name"       : orchestratorInfo?.hostName,
                    "aruba.orchestrator.platform"        : orchestratorInfo?.platform,
                    "aruba.orchestrator.os"              : orchestratorInfo?.osRev ?: orchestratorInfo?.release
            ]
    ]

    def filterProps = [
            "aruba.appliance.serial.number": device?.serial,
            "aruba.appliance.site"         : device?.site,
            "aruba.appliance.device.name"  : device?.hostName,
            "aruba.appliance.tags"         : device?.tagsList
    ]

    filterProps.each { k, v ->
        if (v) {
            Map hostProps = resource.get("hostProps")
            hostProps.put(k, v)
        }
    }


    if (collectorId) {
        resource["collectorId"] = collectorId
        if (duplicateResources["resources"][displayName]["Netscan"][0]) {
            duplicateResources["resources"][displayName]["Netscan"][0]["collectorId"] = collectorId
        }
    }

    if (!deviceMatch) {
        resources.add(resource)
    }
}

// Output validated data in JSON format
lmEmit.resource(resources, debug)

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

return 0

////////////////////////////////////////////////////////////// CSV PROCESSING METHODS ///////////////////////////////////////////////////////////
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

/**
 * Helper function to process CSV with site id and name info
 * @param String filename Filename of the CSV containing user-defined site info
 * @return Map siteInfo with site ID as the key and site name as the value
 */
Map processCSV(String filename) {
    File cacheFile = newFile(filename, "csv")
    def rows = cacheFile.readLines()*.split(",")

    def siteInfo = [:]

    // Verify whether headers are present and expected values
    // Sanitize for casing and extra whitespaces while gathering headers
    def maybeHeaders = rows[0]*.toLowerCase()*.trim()
    if (maybeHeaders.contains("site folder name") && maybeHeaders.contains("site name")) {
        Map headerIndices = [:]
        maybeHeaders.eachWithIndex { val, i ->
            headerIndices[val] = i
        }
        // Index values for headers to ensure we key the correct index regardless of order
        def sn = headerIndices["site name"]
        def colId = headerIndices["collector id"]
        def sf = headerIndices["site folder name"]

        // Remove headers from dataset
        data = rows[1..-1]
        // Build a map of common site names with site name as key
        data.each { entry ->
            siteInfo[entry[sn]] = [
                    "SiteFolder" : entry[sf],
                    "CollectorId": entry[colId]
            ]
        }
    }
    // Bail out early if we don't have the expected headers in the provided CSV, we can't properly associate Site IDs with common names without clear headers
    else {
        throw new Exception(" Required headers not provided in CSV.  Please provide \"Site Folder Name\" and \"Site Name\" (case insensitive).  Headers provided: \"${rows[0]}\"")
    }
    return siteInfo
}
///////////////////////////////////////////////////////////////////// END //////////////////////////////////////////////////////////////////////
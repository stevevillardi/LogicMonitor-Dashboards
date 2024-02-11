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
def clientId = props.get("paloalto.sase.client.id")
def clientSecret = props.get("paloalto.sase.client.key")
def tsgId = props.get("paloalto.sase.tsg.id")
 
// Optional properties
def rootFolder = props.get("paloalto.prisma.sdwan.root.folder") ?: "Prisma SD-WAN"
def collectorId = props.get("paloalto.collector")
 
Boolean skipDeviceDedupe = props.get("skip.device.dedupe", "false").toBoolean()
String hostnameSource = props.get("hostname.source", "")?.toLowerCase()?.trim()
 
// Retrieve the collector version
Integer collectorVersion = AgentVersion.AGENT_VERSION.toInteger()
 
// Bail out early if we don't have the correct minimum collector version to ensure netscan runs properly
if (collectorVersion < 32400) {
    def formattedVer = new DecimalFormat("00.000").format(collectorVersion / 1000)
    throw new Exception(" Upgrade collector running netscan to 32.400 or higher to run full featured enhanced script netscan. Currently running version ${formattedVer}.")
}
 
// Bail out early if we don't have the necessary credentials
if (!clientSecret || !clientId || !tsgId) {
    throw new Exception("Must provide credentials to run this script (paloalto.sase.client.id, paloalto.sase.client.key, and paloalto.sase.tsg.id). Verify necessary credentials have been provided in NetScan properties.")
}
 
def modLoader = GSH.getInstance()._getScript("Snippets", Snippets.getLoader()).withBinding(getBinding())
def lmEmit = modLoader.load("lm.emit", "1.1")
def debugSnip = modLoader.load("lm.debug", "1.0")
def lmDebug = debugSnip.debugSnippetFactory(out, debug)
def httpSnippet = modLoader.load("proto.http", "0")
def http = httpSnippet.httpSnippetFactory(props)
def cache = modLoader.load("lm.cache", "0").cacheSnippetFactory(lmDebug, "PaloAlto_PrismaSDWAN")
def prisma = modLoader.load("paloalto.prismasdwan", "0.0").prismaSnippetFactory(cache, http, lmDebug, props)
 
// Only initialize lmApi snippet class if customer has not opted out
def lmApi
if (!skipDeviceDedupe) {
    def lmApiSnippet = modLoader.load("lm.api", "0")
    lmApi = lmApiSnippet.lmApiSnippetFactory(props, http, lmDebug)
}
 
String cacheFilename = "Technology_Primsa_devices"
 
Map sensitiveProps = [
        "paloalto.sase.client.id" : clientId,
        "paloalto.sase.client.key": clientSecret,
]
 
if (processResourcesJson(lmEmit, cacheFilename, sensitiveProps, lmDebug)) return 0
 
// CSV file containing headers "Site ID" and "Site Name" to customize group names created
// Save in /usr/local/logicmonitor/agent/bin directory (Linux)
//  or C:\Program Files\LogicMonitor\Agent\bin directory (Windows)
String csvFile = props.get("prisma.sdwan.sites.csv")
// Convert to map with site ID as key
Map siteInfo = csvFile ? processCSV(csvFile) : null
 
// Get information about devices that already exist in LM portal
List fields = ["name", "currentCollectorId", "displayName", "systemProperties"]
Map args = ["size": 1000, "fields": fields.join(","), "filter": "systemProperties.name:\"system.sysname\""]
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
 
def lmDevicesBySysname
if (pathFlag == "all" && !skipDeviceDedupe) {
    // Prisma does not provide individual IP addresses, check dedupe by device name
    lmDevicesBySysname = lmDevices?.collectEntries { device ->
        def sysName = device.systemProperties.find { it -> it.name == "system.sysname" }.value
        [
                ("${sysName}".toString().toLowerCase()): [
                        "name"              : "${device.name}",
                        "displayName"       : (device.displayName),
                        "currentCollectorId": (device.currentCollectorId),
                        "sysName"           : (sysName)
                ]
        ]
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
 
 
if (!prisma.token) {
    throw new Exception("Credential check failed. Verify credentials and try running in debug mode in collector debug console.")
}
 
def permissions = prisma.getPermissions()
def siteVersion = prisma.sanitizeVersion(permissions.resource_version_map.sites)
        ?.split(",")?.max()
def elementVersion = prisma.sanitizeVersion(permissions.resource_version_map.elements)
        ?.split(",")?.max()
 
Map sites = prisma.getSites(siteVersion)?.collectEntries { site -> [(site.id): (site.name)] }
def elements = prisma.getElements(elementVersion)
elements?.each { details ->
    def displayName = details.name
    def displayNameToCheck = "${displayName}".toString().toLowerCase()
    // We do not get IP for prisma
    def hostName = details.name
    // Check for existing device in LM portal with this displayName; set to false initially and update to true when dupe found
    def deviceMatch = false
    def elementId = details.id
 
    // If customer has opted out of device deduplication checks, we skip the lookups where we determine if a match exists and proceed as false
    if (!skipDeviceDedupe) {
        if (pathFlag == "ind") {
            deviceMatch = lmApi.findPortalDeviceByProperty(displayName, args)
        } else if (pathFlag == "all") {
            deviceMatch = lmDevicesBySysname?.get(displayName)
        }
    }
 
    if (deviceMatch) {
        // Log duplicates that would cause additional devices to be created; unless these entries are resolved, they will not be added to resources for netscan output
        // We have a collision if the element name is the same as the hostname from the existing lm resource
        if (displayNameToCheck != deviceMatch.name.toString().toLowerCase()) {
            def collisionInfo = [
                    (displayNameToCheck): [
                            "Netscan" : [
                                    "hostname": hostName
                            ],
                            "LM"      : [
                                    "hostname"   : deviceMatch.name,
                                    "collectorId": deviceMatch.currentCollectorId
                            ],
                            "Resolved": false
                    ]
            ]
 
            // If user specified to use LM hostname on display name match, update hostname variable accordingly
            // need to figure out the logic here if a customer chooses lm do we pick the hostname as display name ?
            if (hostnameSource == "lm" || hostnameSource == "logicmonitor") {
                collisionInfo[displayNameToCheck]["Resolved"] = true
                hostName = deviceMatch.name
                displayName = deviceMatch.displayName
                collectorId = deviceMatch.currentCollectorId
                deviceMatch = false
 
            }
            // If user specified to use netscan data for hostname, update the display name to make it unique
            // and flag it as no longer a match since we have resolved the collision with user's input
            else if (hostnameSource == "netscan") {
                collisionInfo[displayNameToCheck]["Resolved"] = true
                displayName = "${displayName} - ${deviceMatch.name}"
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
 
    String siteId = "${details.site_id}"
    List groupName = null
    if (siteInfo) {
        if (siteInfo[sites?.get(siteId)]) {
            groupName = ["${rootFolder}/${siteInfo[sites?.get(siteId)]}"]
        }
    } else {
        def site = sites?.get(siteId)
        groupName = site ? ["${rootFolder}/${site}"] : ["${rootFolder}"]
    }
 
    Map hostProps = ["paloalto.sase.element.id": elementId,
                     "paloalto.sase.tsg.id"    : tsgId
    ]
 
    sensitiveProps.each { k, v ->
        hostProps.put(k, v)
    }
 
    Map resource = [
            "hostname"   : hostName.replaceAll(/[!@#$%^&*()+={}\[\]|\\\\;'"<>,\/?`~ ]/, '-'),  // String
            "displayname": displayName, // String
            "hostProps"  : hostProps, // Map<String, String>
            "groupName"  : groupName,   // List<String>
    ]
 
    if (collectorId) {
        resource["collectorId"] = collectorId
        if(duplicateResources["resources"][displayNameToCheck]["Netscan"][0]){
            duplicateResources["resources"][displayNameToCheck]["Netscan"][0]["collectorId"] = collectorId
        }
    }
 
    if (!deviceMatch) {
        resources.add(resource)
    }
}
 
// Output validated data in JSON format
// If errors have been made in acceptable/required keys, lm.emit will throw and inform the user
emitWriteJsonResources(lmEmit, cacheFilename, resources, lmDebug) // limit output to 600
 
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
 
/**
 * Sanitizes filepath and instantiates File object
 * @param filename String
 * @param fileExtension String
 * @return File object using sanitized relative filepath
 */
File newFile(String filename, String fileExtension) {
    // Ensure relative filepath is complete with extension type
    def filepath
    if (!filename?.startsWith("./")) {
        filepath = "./${filename}"
    }
    if (!filepath?.endsWith(".${fileExtension}")) {
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
    Map completeHostProps = cachedProps.collectEntries { k, v ->
        if (sensitiveProps.containsKey(k)) {
            return [k as String, sensitiveProps[k]]
        } else {
            return [k as String, v as String]
        }
    }
    // Verify that we do not have any remaining properties with fake values; stop the show if we do
    def missingKeys = completeHostProps.findAll { k, v -> v == "***" }
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
    cachedDevices.each { device ->
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
    emit.resource(resources[0..chunk - 1], lmDebug.debug)
 
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
///////////////////////////////////////////////////////////////////// END //////////////////////////////////////////////////////////////////////
 
////////////////////////////////////////////////////////////// CSV PROCESSING METHODS ///////////////////////////////////////////////////////////
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

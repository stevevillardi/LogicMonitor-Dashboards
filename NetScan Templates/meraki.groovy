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
def ciscoMerakiCloudSnip = modLoader.load("cisco.meraki.cloud", "0")
def ciscoMerakiCloud     = ciscoMerakiCloudSnip.ciscoMerakiCloudSnippetFactory(props, lmDebug, cache, http)
 
String orgDisplayname      = props.get("meraki.api.org.name") ?: "MerakiOrganization"
String orgFolder           = props.get("meraki.api.org.folder") ? props.get("meraki.api.org.folder") + "/" : ""
String serviceUrl          = props.get("meraki.service.url") ?: "https://api.meraki.com/api/v1"
def networksWhitelist    = props.get("meraki.api.org.networks")?.tokenize(",")?.collect{ it.trim() }
def collectorNetworksCSV = props.get("meraki.api.org.collector.networks.csv")
def collectorNetworkInfo
if (collectorNetworksCSV) collectorNetworkInfo = processCollectorNetworkInfoCSV(collectorNetworksCSV)
 
String merakiSnmpCommunity = props.get("meraki.snmp.community.pass")
String merakiSnmpSecurity  = props.get("meraki.snmp.security")
String merakiSnmpAuth      = props.get("meraki.snmp.auth")
String merakiSnmpAuthToken = props.get("meraki.snmp.authToken.pass")
String merakiSnmpPriv      = props.get("meraki.snmp.priv")
String merakiSnmpPrivToken = props.get("meraki.snmp.privToken.pass")
 
Map sensitiveProps = [
    "meraki.api.key"   : key,
    "meraki.snmp.community.pass" : merakiSnmpCommunity,
    "meraki.snmp.auth"           : merakiSnmpAuth,
    "meraki.snmp.authToken.pass" : merakiSnmpAuthToken,
    "meraki.snmp.privToken.pass" : merakiSnmpPrivToken
]
 
// Determine whether there are devices cached on disk that still need to be added from previous netscan runs
if (processResourcesJson(emit, "devices", sensitiveProps, lmDebug)) return 0
 
List<Map> resources = []
 
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
        }
 
        deviceProps.put("meraki.productType", productType)
 
        // Set group and collector ID based on user CSV inputs if provided
        if (collectorNetworkInfo) {
            def collectorId = collectorNetworkInfo[networkId]["collectorId"]
            def folder      = collectorNetworkInfo[networkId]["folder"]
            Map resource = [
                "hostname"    : ip,
                "displayname" : name,
                "hostProps"   : deviceProps,
                "groupName"   : ["${orgFolder}${folder}/${networkName}"],
                "collectorId" : collectorId
            ]
            resources.add(resource)
        } else {
            Map resource = [
                "hostname"    : ip,
                "displayname" : name,
                "hostProps"   : deviceProps,
                "groupName"   : ["${orgFolder}${orgDisplayname}/${networkName}"]
            ]
            resources.add(resource)
        }
    }
}
 
emitWriteJsonResources(emit, "devices", resources, lmDebug)
 
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
    if (!filename.startsWith("./")) {
        filepath = "./${filename}"
    }
    if (!filepath.endsWith(".${fileExtension}")) {
        filepath = "${filepath}.${fileExtension}"
    }
 
    return new File(filepath)
}
 
 
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
 * Replaces cached props stored with bogus values with their correct values
 * @param cachedProps Map of hostProps values stored in file cache
 * @param sensitiveProps Map of sensitive properties configured in the netscan to use for updating cachedProps values
 * @return completeHostProps Map updated hostProps with no bogus values
*/
Map processCachedHostProps(Map cachedProps, Map sensitiveProps) {
    Map completeHostProps = cachedProps.collectEntries{ k,v ->
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
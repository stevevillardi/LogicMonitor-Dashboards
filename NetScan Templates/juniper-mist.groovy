/*******************************************************************************
 * © 2007-2024 - LogicMonitor, Inc. All rights reserved.
 ******************************************************************************/
 
import com.logicmonitor.common.sse.utils.GroovyScriptHelper as GSH
import com.logicmonitor.mod.Snippets
import com.santaba.agent.util.Settings
import com.santaba.agent.AgentVersion
import groovy.json.JsonSlurper
import java.text.DecimalFormat

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

String organization = props.get("mist.api.org")
String token = props.get("mist.api.key")
if (!organization) {
    throw new Exception("Must provide mist.api.org to run this script.  Verify necessary credentials have been provided in Netscan properties.")
}
if (!token) {
    throw new Exception("Must provide mist.api.key credentials to run this script.  Verify necessary credentials have been provided in Netscan properties.")
}

def logCacheContext = "${organization}::juniper-mist"
Boolean skipDeviceDedupe = props.get("skip.device.dedupe", "false").toBoolean()
String hostnameSource    = props.get("hostname.source", "")?.toLowerCase()?.trim()

Integer collectorVersion = AgentVersion.AGENT_VERSION.toInteger()

// Bail out early if we don't have the correct minimum collector version to ensure netscan runs properly
if (collectorVersion < 32400) {
    def formattedVer = new DecimalFormat("00.000").format(collectorVersion / 1000)
    throw new Exception("Upgrade collector running netscan to 32.400 or higher to run full featured enhanced netscan. Currently running version ${formattedVer}.")
}

def modLoader = GSH.getInstance()._getScript("Snippets", Snippets.getLoader()).withBinding(getBinding())
def emit = modLoader.load("lm.emit", "1.1")
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
def juniperMistSnip = modLoader.load("juniper.mist", "0")
def juniperMist     = juniperMistSnip.juniperMistSnippetFactory(props, lmDebug, cache, http)

String url = props.get("mist.api.url")?.trim() ?: "https://api.mist.com/api/v1/"
if (!url.endsWith("/")) url += "/"

String organizationDisplayname = props.get("mist.api.org.name")?.trim() ?: "MistOrganization"
String organizationFolder = props.get("mist.api.org.folder")?.trim() ? props.get("mist.api.org.folder") + "/" : ""
def sitesWhitelist = props.get("mist.api.org.sites")?.tokenize(",")?.collect{ it.trim() }
def collectorSitesCSV = props.get("mist.api.org.collector.sites.csv")
def collectorSiteInfo
if (collectorSitesCSV) {
    collectorSiteInfo = processCollectorSiteInfoCSV(collectorSitesCSV)
    lmDebug.LMDebugPrint("CSV Sites:")
    collectorSiteInfo.each { lmDebug.LMDebugPrint("${it[2]}") }
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

def now = new Date()
def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.s z"
TimeZone tz = TimeZone.getDefault()
Map duplicateResources = [
        "date" : now.format(dateFormat, tz),
        "message" : "Duplicate device names and display names, keyed by display name that would be assigned by the netscan, found within LogicMonitor portal.  Refer to documentation for how to resolve name collisions using 'hostname.source' netscan property.",
        "total" : 0,
        "resources" : []
]

Map headers = ["Authorization":"token ${token}", "Accept":"application/json"]

List<Map> resources = []

def organizationSites = juniperMist.http.rawGet("${url}orgs/${organization}/sites", headers)
if (organizationSites) {
    organizationSites = juniperMist.slurper.parseText(organizationSites.content.text)
    organizationSites.each { organizationSite ->
        def siteName = organizationSite.get("name")
        if (sitesWhitelist != null && !sitesWhitelist.contains(siteName)) return    
        def site = organizationSite.get("id") 
        def siteDeviceStats = juniperMist.http.rawGet("${url}sites/${site}/stats/devices?type=all", headers)
        if (siteDeviceStats) {
            siteDeviceStats = juniperMist.slurper.parseText(siteDeviceStats.content.text)
            siteDeviceStats.each { siteDeviceStat ->
                def ip = siteDeviceStat.get("ip")
                def name = siteDeviceStat.get("name")
                def type = siteDeviceStat.get("type")
                def deviceProps = ["mist.api.org": organization, "mist.api.site": site, "mist.device.type": type, "mist.api.org.site.name": siteName, "mist.api.key": token, "mist.api.url": url]
                if (sitesWhitelist != null) deviceProps.put("mist.api.org.sites", props.get("mist.api.org.sites"))
                if (type == "ap") {
                    deviceProps.put("system.categories", "JuniperMistAP,NoPing")
                } else if (type == "switch") {
                    deviceProps.put("system.categories", "JuniperMistSwitch")
                } else if (type == "gateway") {
                    deviceProps.put("system.categories", "JuniperMistGateway")
                    if (siteDeviceStat.module_stat && siteDeviceStat.module2_stat) {
                        def primaryNodeIndex = siteDeviceStat.module_stat.node_name.get(0)
                        def secondaryNodeIndex = siteDeviceStat.module2_stat.node_name.get(0)
                        if (collectorSiteInfo) {
                            def collectorIdEntry = collectorSiteInfo.find{ it[3]?.contains(siteName) }
                            if (collectorIdEntry == null) {
                                lmDebug.LMDebugPrint("Site not found in provided collector site info CSV file: ${siteName}")
                                return
                            }                            
                            def collectorId = collectorIdEntry[0]
                            def folder = collectorIdEntry[2]                      
                            Map resource = [
                                "hostname"    : "${ip}-${secondaryNodeIndex}",
                                "displayname" : "${name}-${secondaryNodeIndex}",
                                "hostProps"   : deviceProps,
                                "groupName"   : ["${organizationFolder}${folder}/${siteName}"],
                                "collectorId" : collectorId
                            ]
                            resources.add(resource)
                        } else {
                            Map resource = [
                                "hostname"    : "${ip}-${secondaryNodeIndex}",
                                "displayname" : "${name}-${secondaryNodeIndex}",
                                "hostProps"   : deviceProps,
                                "groupName"   : ["${organizationFolder}${organizationDisplayname}/${siteName}"]
                            ]
                            resources.add(resource)
                        }
                        name = "${name}-${primaryNodeIndex}"
                    }
                }  

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

                def collectorId
                Map resource
                if (ip && name && type && siteName) {
                    if (ip.contains("127.0.0.1")) ip = name
                    if (collectorSiteInfo) {
                        def collectorIdEntry = collectorSiteInfo.find{ it[3]?.contains(siteName) }
                        if (collectorIdEntry == null) {
                            lmDebug.LMDebugPrint("Site not found in provided collector site info CSV file: ${siteName}")
                            return
                        }
                        collectorId = collectorIdEntry[0]
                        def folder = collectorIdEntry[2]                      
                        resource = [
                            "hostname"    : ip,
                            "displayname" : name,
                            "hostProps"   : deviceProps,
                            "groupName"   : ["${organizationFolder}${folder}/${siteName}"],
                            "collectorId" : collectorId
                        ]
                    } else {
                        resource = [
                            "hostname"    : ip,
                            "displayname" : name,
                            "hostProps"   : deviceProps,
                            "groupName"   : ["${organizationFolder}${organizationDisplayname}/${siteName}"]
                        ]
                    }

                    // Only add the collectorId field to resource map if we found a collector ID above
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
            }
        } else {
            throw new Exception("Error occurred during sites/${site}/stats/devices?type=all HTTP GET: ${siteDeviceStats}.")
        }
    }

    lmDebug.LMDebugPrint("Duplicate Resources:")
    duplicateResources.resources.each {
        lmDebug.LMDebugPrint("\t${it}")
    }

    emit.resource(resources, debug)
} else {
    throw new Exception("Error occurred during orgs/${organization}/sites HTTP GET: ${organizationSites}.")
}

return 0


// Helper function to process a collector id, site organization device name, folder, and site CSV
def processCollectorSiteInfoCSV(String filename) {
    // Ensure relative filepath is complete with extension type
    def filepath
    if (!filename.contains("./")) {
        filepath = "./${filename}"
    }
    if (!filename.contains(".csv")) {
        filepath = "${filename}.csv"
    }

    // Read file into memory and split into list of lists
    def csv = new File(filepath)
    def rows = csv.readLines()*.split(",")
    def data

    // Verify whether headers are present and expected values
    // Sanitize for casing and extra whitespaces while gathering headers
    def maybeHeaders = rows[0]*.toLowerCase()*.trim()
    if (maybeHeaders.contains("collector id") && maybeHeaders.contains("site organization device name") && maybeHeaders.contains("folder") && maybeHeaders.contains("site")) {
        // Remove headers from dataset
        data = rows[1..-1]
    }
    // Bail out early if we don't have the expected headers in the provided CSV
    else {
        throw new Exception(" Required headers not provided in CSV.  Please provide \"Collector ID\", \"Site Organization Device Name\", \"Folder\", and \"Site\" (case insensitive).  Headers provided: \"${rows[0]}\"")
    }

    return data
}
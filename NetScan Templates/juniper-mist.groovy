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
def emit = modLoader.load("lm.emit", "0")
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
}

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
                def deviceProps = ["mist.api.org": organization, "mist.api.site": site, "mist.device.type": type, "mist.api.org.site.name": siteName, "mist.api.key": token]
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
                            def collectorIdEntry = collectorSiteInfo.find{ it.contains(siteName) }
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
                if (ip && name && type && siteName) {
                    if (ip.contains("127.0.0.1")) ip = name
                    if (collectorSiteInfo) {
                        def collectorIdEntry = collectorSiteInfo.find{ it.contains(siteName) }
                        def collectorId = collectorIdEntry[0]
                        def folder = collectorIdEntry[2]                      
                        Map resource = [
                            "hostname"    : ip,
                            "displayname" : name,
                            "hostProps"   : deviceProps,
                            "groupName"   : ["${organizationFolder}${folder}/${siteName}"],
                            "collectorId" : collectorId
                        ]
                        resources.add(resource)
                    } else {
                        Map resource = [
                            "hostname"    : ip,
                            "displayname" : name,
                            "hostProps"   : deviceProps,
                            "groupName"   : ["${organizationFolder}${organizationDisplayname}/${siteName}"]
                        ]
                        resources.add(resource)
                    }
                }
            }
        } else {
            throw new Exception("Error occurred during sites/${site}/stats/devices?type=all HTTP GET: ${siteDeviceStats}.")
        }
    }

    emit.resource(resources)
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
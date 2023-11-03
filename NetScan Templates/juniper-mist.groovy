/*******************************************************************************
 * © 2007-2023 - LogicMonitor, Inc. All rights reserved.
 ******************************************************************************/
 
import com.logicmonitor.common.sse.utils.GroovyScriptHelper as GSH
import com.logicmonitor.mod.Snippets
import com.santaba.agent.util.Settings
import com.santaba.agent.AgentVersion
import groovy.json.JsonSlurper
import java.text.DecimalFormat
import javax.net.ssl.HttpsURLConnection

Integer collectorVersion = AgentVersion.AGENT_VERSION.toInteger()
 
// Bail out early if we don't have the correct minimum collector version to ensure netscan runs properly
if (collectorVersion < 32400) {
    def formattedVer = new DecimalFormat("00.000").format(collectorVersion / 1000)
    throw new Exception("Upgrade collector running netscan to 32.400 or higher to run full featured enhanced netscan. Currently running version ${formattedVer}.")
}

modLoader = GSH.getInstance()._getScript("Snippets", Snippets.getLoader()).withBinding(getBinding())
def emit = modLoader.load("lm.emit", "0")

String organization = netscanProps.get("mist.api.org")
String token = netscanProps.get("mist.api.key")
if (!organization) {
    throw new Exception("Must provide mist.api.org to run this script.  Verify necessary credentials have been provided in Netscan properties.")
}
if (!token) {
    throw new Exception("Must provide mist.api.key credentials to run this script.  Verify necessary credentials have been provided in Netscan properties.")
}

String url = netscanProps.get("mist.api.url")?.trim() ?: "https://api.mist.com/api/v1/"
if (!url.endsWith("/")) url += "/"

String organizationDisplayname = netscanProps.get("mist.api.org.name")?.trim() ?: "MistOrganization"
String organizationFolder = netscanProps.get("mist.api.org.folder")?.trim() ? netscanProps.get("mist.api.org.folder") + "/" : ""
def sitesWhitelist = netscanProps.get("mist.api.org.sites")?.tokenize(",")?.collect{ it.trim() }
def collectorSitesCSV = netscanProps.get("mist.api.org.collector.sites.csv")
def collectorSiteInfo
if (collectorSitesCSV) {
    collectorSiteInfo = processCollectorSiteInfoCSV(collectorSitesCSV)
}

Map headers = ["Authorization":"token ${token}", "Accept":"application/json"]

List<Map> resources = []

def facility = "siteMetrics"
def organizationSites = httpGet("${url}orgs/${organization}/sites", headers)
if (organizationSites.statusCode == 200 && organizationSites.data) {    
    if (collectorSiteInfo) {
        def seenCollectors = []
        collectorSiteInfo.each { collectorSite ->
            if (collectorSite.size() == 4) {
                def collectorId = collectorSite[0]
                if (seenCollectors.contains(collectorId)) return // One org device and folder per collector ID
                seenCollectors << collectorId
                def name = collectorSite[1]
                def folder = collectorSite[2]
                def props = ["mist.api.org": organization, "mist.api.key": token, "mist.api.url": url, "system.categories": "JuniperMistOrg", 
                             "mist.api.org.collector.sites": collectorSiteInfo.findAll{ it[0] == collectorId }?.collect{ it[3] }?.toString()?.replace("[", "")?.replace("]", "")]
                Map resource = [
                    "hostname"    : "${name}.invalid",
                    "displayname" : "${name}",
                    "hostProps"   : props,
                    "groupName"   : ["${organizationFolder}${folder}"],
                    "collectorId" : collectorId
                ]
                resources.add(resource)
            }
        }
    } else {
        def props = ["mist.api.org": organization, "mist.api.key": token, "mist.api.url": url, "system.categories": "JuniperMistOrg,NoPing"]
        Map resource = [
            "hostname"    : "${organizationDisplayname}.invalid",
            "displayname" : organizationDisplayname,
            "hostProps"   : props,
            "groupName"   : ["${organizationFolder}${organizationDisplayname}"]
        ]
        resources.add(resource)
    }

    organizationSites = organizationSites.data
    organizationSites.each { organizationSite ->
        def siteName = organizationSite.get("name")   
        if (sitesWhitelist != null && !sitesWhitelist.contains(siteName)) return    
        def site = organizationSite.get("id") 
        def siteDeviceStats = httpGet("${url}sites/${site}/stats/devices?type=all", headers)
        if (siteDeviceStats.statusCode == 200) {
            siteDeviceStats = siteDeviceStats.data
            siteDeviceStats.each { siteDeviceStat ->
                def ip = siteDeviceStat.get("ip")
                def name = siteDeviceStat.get("name")
                def type = siteDeviceStat.get("type")
                def props = ["mist.api.org": organization, "mist.api.site": site, "mist.device.type": type, "mist.api.org.site.name": siteName]
                if (type == "ap") {
                    props.put("system.categories", "JuniperMistAP,NoPing")
                } else if (type == "switch") {
                    props.put("system.categories", "JuniperMistSwitch")
                } else if (type == "gateway") {
                    props.put("system.categories", "JuniperMistGateway")
                }  
                if (ip && name && type && siteName) {
                    if (ip == "127.0.0.1") ip = name
                    if (collectorSiteInfo) {
                        def collectorIdEntry = collectorSiteInfo.find{ it.contains(siteName) }
                        def collectorId = collectorIdEntry[0]
                        def folder = collectorIdEntry[2]                      
                        Map resource = [
                            "hostname"    : ip,
                            "displayname" : name,
                            "hostProps"   : props,
                            "groupName"   : ["${organizationFolder}${folder}/${siteName}"],
                            "collectorId" : collectorId
                        ]
                        resources.add(resource)
                    } else {
                        Map resource = [
                            "hostname"    : ip,
                            "displayname" : name,
                            "hostProps"   : props,
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

def httpGet(String endpoint, Map headers, Integer retryLen = 5) {
    Random random = new Random()
    Integer waitPeriod = 5000 + Math.round((3000 * random.nextDouble())) // adding randomness to wait time
    Double waitTime = 0
    Map returnItem = [:]
    Integer retryCount = 0
    ArrayList validRetries = [429, 500]
    HttpsURLConnection response = null
    while (retryCount <= retryLen) {
        retryCount++
        response = rawHttpGet(endpoint, headers)
        returnItem["statusCode"] = response.getResponseCode()
        if (!validRetries.contains(returnItem["statusCode"])) {
            if (returnItem["statusCode"] == 200) {
                returnItem["rawData"] = response.inputStream.text
                returnItem["data"] = new JsonSlurper().parseText(returnItem["rawData"])
                sleep(200)
            } else {
                returnItem["data"] = null
            }
            returnItem["waitTime"] = waitTime
            return returnItem
        }
        sleep(waitPeriod)
        waitTime = waitTime + waitPeriod
    }
    returnItem["statusCode"] = -1  // unknown status code
    returnItem["data"] = null
    returnItem["waitTime"] = waitTime
    returnItem["rawData"] = null
    returnItem["errMsg"] = response.getErrorStream()
    return returnItem
}

def rawHttpGet(String url, Map headers) {
    HttpsURLConnection request = null
    Map proxyInfo = getProxyInfo()
    if (proxyInfo){
        request = url.toURL().openConnection(proxyInfo.proxy)
    } else {
        request = url.toURL().openConnection()
    }
    headers.each { request.addRequestProperty(it.key, it.value) }
    return request
}

Map getProxyInfo() {
    Boolean deviceProxy    =  this.netscanProps.get("proxy.enable")?.toBoolean() ?: true  // default to true in absence of property to use collectorProxy as determinant
    Boolean collectorProxy = Settings.getSetting("proxy.enable")?.toBoolean() ?: false // if settings are not present, value should be false
    Map proxyInfo = [:]

    if (deviceProxy && collectorProxy) {
        proxyInfo = [
            enabled : true,
            host : this.netscanProps.get("proxy.host") ?: Settings.getSetting("proxy.host"),
            port : this.netscanProps.get("proxy.port") ?: Settings.getSetting("proxy.port") ?: 3128,
            user : Settings.getSetting("proxy.user"),
            pass : Settings.getSetting("proxy.pass")
        ]

        proxyInfo["proxy"] = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyInfo.host, proxyInfo.port.toInteger()))
    }

    return proxyInfo
}

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
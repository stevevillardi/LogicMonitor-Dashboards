/*******************************************************************************
 * Alert Collection Tester - Debug/Troubleshooting Script
 * Tests the V4 alert collection logic with detailed debugging output
 ******************************************************************************/

import groovy.json.JsonSlurper
import com.santaba.agent.util.Settings
import org.apache.commons.codec.binary.Hex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import groovy.json.*

// Configuration - same as main script
String apiId   = hostProps.get("lmaccess.id")  ?: hostProps.get("logicmonitor.access.id")
String apiKey  = hostProps.get("lmaccess.key") ?: hostProps.get("logicmonitor.access.key")
def lookbackPeriod = hostProps.get("alert.analysis.period") ?: hostProps.get("alert.duration.period") ?: "6" //Default for testing

def excludeUnACKedAlerts = hostProps.get("alert.analysis.excludeUnACKedAlerts") ?: hostProps.get("alert.duration.excludeUnACKedAlerts") ?: "false"
def excludeSDTedAlerts = hostProps.get("alert.analysis.excludeSDTedAlerts") ?: hostProps.get("alert.duration.excludeSDTedAlerts") ?: "false"
def datasourceFilter = hostProps.get("alert.analysis.datasourceList") ?: hostProps.get("alert.duration.datasourceList") ?: "*"

def portalName = hostProps.get("lmaccount") ?: Settings.getSetting(Settings.AGENT_COMPANY)
Map proxyInfo = getProxyInfo()

// Test configuration
def MAX_TEST_ITERATIONS = 5  // Limit for testing
def TEST_BATCH_SIZE = 1000   // Smaller batches for testing

// Calculate time window
int timePeriod = 24 * lookbackPeriod.toInteger()
Date now = new Date()
long endEpoch = now.getTime() / 1000
long startEpoch = endEpoch - (timePeriod * 3600)

def SDTFilter = excludeSDTedAlerts == "true" ? "false" : "*"
def ACKFilter = "*"
def propfilter = ",acked:\"${ACKFilter}\",sdted:\"${SDTFilter}\",instanceName~\"${datasourceFilter.replace(",", "|")}\""

// V4: Use startEpoch filtering
def filter = "cleared:\"True\",startEpoch>:${startEpoch}" + propfilter
def fields = "severity,monitorObjectId,monitorObjectGroups,id,startEpoch,endEpoch,acked,sdted"

println "=== ALERT COLLECTION TESTER ==="
println "Timestamp: ${new Date()}"
println "Portal: ${portalName}"
println "Lookback Period: ${lookbackPeriod} days"
println "Start Epoch: ${startEpoch} (${new Date(startEpoch * 1000L)})"
println "End Epoch: ${endEpoch} (${new Date(endEpoch * 1000L)})"
println "Filter: ${filter}"
println "Fields: ${fields}"
println ""

try {
    def allAlerts = []
    def totalCollected = 0
    def apiIterations = 0
    def startTime = System.currentTimeMillis()

    println "=== STARTING ALERT COLLECTION TEST ==="

    while (apiIterations < MAX_TEST_ITERATIONS) {
        apiIterations++
        println ""
        println "--- Iteration ${apiIterations} ---"
        println "Current offset: ${totalCollected}"
        println "Requesting ${TEST_BATCH_SIZE} alerts..."

        def iterationStart = System.currentTimeMillis()

        try {
            def apiResponse = apiGetAlertsSorted(portalName, apiId, apiKey, proxyInfo, filter, fields, totalCollected, TEST_BATCH_SIZE)

            def iterationTime = System.currentTimeMillis() - iterationStart
            def iterationAlerts = apiResponse.items
            def totalOnServer = apiResponse.total
            def hasMoreResults = apiResponse.hasMore

            println "  API Response Time: ${iterationTime}ms"
            println "  Alerts Returned: ${iterationAlerts.size()}"
            println "  Server Total: ${totalOnServer}"
            println "  Has More Results: ${hasMoreResults}"

            if (!iterationAlerts || iterationAlerts.size() == 0) {
                println "  No more alerts found - stopping"
                break
            }

            // Analyze the alerts in this batch
            analyzeAlertBatch(iterationAlerts, apiIterations)

            // Format group IDs (like main script)
            iterationAlerts = formatGroupIds(iterationAlerts)

            allAlerts.addAll(iterationAlerts)
            totalCollected += iterationAlerts.size()

            println "  Total Collected So Far: ${totalCollected}"

            // Check stopping conditions
            if (!hasMoreResults && iterationAlerts.size() < TEST_BATCH_SIZE) {
                println "  Reached end of results (no more + partial batch)"
                break
            }

            if (iterationAlerts.size() < TEST_BATCH_SIZE && !hasMoreResults) {
                println "  Collection complete (partial batch + no more)"
                break
            }

        } catch (Exception e) {
            println "  ERROR in iteration ${apiIterations}: ${e.getMessage()}"
            break
        }
    }

    def totalTime = System.currentTimeMillis() - startTime

    println ""
    println "=== COLLECTION SUMMARY ==="
    println "Total Iterations: ${apiIterations}"
    println "Total Alerts Collected: ${totalCollected}"
    println "Total Time: ${totalTime}ms"
    println "Average per Iteration: ${apiIterations > 0 ? Math.round(totalTime / apiIterations) : 0}ms"
    println ""

    if (allAlerts.size() > 0) {
        analyzeOverallCollection(allAlerts)
    } else {
        println "No alerts collected!"
    }

} catch (Exception ex) {
    println "SCRIPT ERROR: ${ex.getMessage()}"
    ex.printStackTrace()
}

def analyzeAlertBatch(alerts, iteration) {
    if (!alerts || alerts.size() == 0) {
        println "  Batch Analysis: No alerts to analyze"
        return
    }

    def startEpochs = alerts.collect { it.startEpoch }.findAll { it != null }
    def endEpochs = alerts.collect { it.endEpoch }.findAll { it != null }

    if (startEpochs.size() > 0) {
        def minStart = startEpochs.min()
        def maxStart = startEpochs.max()
        def sortedStarts = startEpochs.sort()
        def middleStart = sortedStarts[Math.floor(sortedStarts.size() / 2) as int]

        println "  Start Epochs:"
        println "    Min: ${minStart} (${new Date(minStart * 1000L)})"
        println "    Mid: ${middleStart} (${new Date(middleStart * 1000L)})"
        println "    Max: ${maxStart} (${new Date(maxStart * 1000L)})"

        // Check if properly sorted
        def isSorted = startEpochs == startEpochs.sort()
        println "    Properly Sorted: ${isSorted ? 'YES' : 'NO'}"
        if (!isSorted) {
            println "    WARNING: Alerts not sorted by startEpoch!"
        }
    }

    if (endEpochs.size() > 0) {
        def minEnd = endEpochs.min()
        def maxEnd = endEpochs.max()
        def sortedEnds = endEpochs.sort()
        def middleEnd = sortedEnds[Math.floor(sortedEnds.size() / 2) as int]

        println "  End Epochs:"
        println "    Min: ${minEnd} (${new Date(minEnd * 1000L)})"
        println "    Mid: ${middleEnd} (${new Date(middleEnd * 1000L)})"
        println "    Max: ${maxEnd} (${new Date(maxEnd * 1000L)})"
    }

    // Sample alerts
    println "  Sample Alerts:"
    alerts.take(3).eachWithIndex { alert, i ->
        println "    ${i+1}. ID:${alert.id}, Start:${alert.startEpoch}, End:${alert.endEpoch}, Sev:${alert.severity}"
    }
}

def analyzeOverallCollection(allAlerts) {
    println "=== OVERALL ANALYSIS ==="

    def allStartEpochs = allAlerts.collect { it.startEpoch }.findAll { it != null }
    def allEndEpochs = allAlerts.collect { it.endEpoch }.findAll { it != null }

    if (allStartEpochs.size() > 0) {
        def sortedStarts = allStartEpochs.sort()
        def minStart = sortedStarts.first()
        def maxStart = sortedStarts.last()
        def middleStart = sortedStarts[Math.floor(sortedStarts.size() / 2) as int]

        println "All Start Epochs:"
        println "  Min: ${minStart} (${new Date(minStart * 1000L)})"
        println "  Mid: ${middleStart} (${new Date(middleStart * 1000L)})"
        println "  Max: ${maxStart} (${new Date(maxStart * 1000L)})"

        // Check overall sorting
        def isOverallSorted = allStartEpochs == allStartEpochs.sort()
        println "  Overall Sorted: ${isOverallSorted ? 'YES' : 'NO'}"

        // Check for duplicates
        def uniqueIds = allAlerts.collect { it.id }.unique()
        def hasDuplicates = uniqueIds.size() != allAlerts.size()
        println "  Duplicates Found: ${hasDuplicates ? 'YES' : 'NO'}"
        if (hasDuplicates) {
            println "  Unique Alerts: ${uniqueIds.size()}, Total: ${allAlerts.size()}"
        }

        // Time range validation
        def timeSpan = maxStart - minStart
        def daysSpan = timeSpan / 86400
        println "  Time Span: ${timeSpan} seconds (${String.format('%.1f', daysSpan)} days)"
    }

    if (allEndEpochs.size() > 0) {
        def sortedEnds = allEndEpochs.sort()
        def minEnd = sortedEnds.first()
        def maxEnd = sortedEnds.last()
        def middleEnd = sortedEnds[Math.floor(sortedEnds.size() / 2) as int]

        println "All End Epochs:"
        println "  Min: ${minEnd} (${new Date(minEnd * 1000L)})"
        println "  Mid: ${middleEnd} (${new Date(middleEnd * 1000L)})"
        println "  Max: ${maxEnd} (${new Date(maxEnd * 1000L)})"
    }

    // Severity distribution
    def severityCount = [:]
    allAlerts.each { alert ->
        def sev = alert.severity ?: 'Unknown'
        severityCount[sev] = (severityCount[sev] ?: 0) + 1
    }

    println "Severity Distribution:"
    severityCount.each { sev, count ->
        println "  Severity ${sev}: ${count} alerts"
    }
}

def apiGetAlertsSorted(portalName, apiId, apiKey, proxyInfo, filter, fields, offset, size) {
    def args = [
        'filter': filter,
        'fields': fields,
        'size': size,
        'offset': offset,
        'sort': '+startEpoch'
    ]

    println "  API Call: offset=${offset}, size=${size}, sort=+startEpoch"

    def response = apiGetV2(portalName, apiId, apiKey, "/alert/alerts", proxyInfo, args)
    if (response.get("errmsg", "OK") != "OK") {
        throw new Exception("Santaba returned errormsg: ${response?.errmsg}")
    }

    return [
        items: response.items ?: [],
        total: response.total ?: 0,
        hasMore: response.total < 0
    ]
}

def formatGroupIds(alerts) {
    alerts.each { alert ->
        alert.monitorObjectGroups.each { group ->
            if (alert.groupIds) {
                alert.groupIds = "${alert.groupIds},${(group.findAll { it.key == 'id' }).id}"
            } else {
                alert.groupIds = "${(group.findAll { it.key == 'id' }).id}"
            }
        }
        alert.remove('monitorObjectGroups')
    }
    return alerts
}

// API helper functions
static String generateAuth(id, key, path) {
    Long epoch_time = System.currentTimeMillis()
    Mac hmac = Mac.getInstance("HmacSHA256")
    hmac.init(new SecretKeySpec(key.getBytes(), "HmacSHA256"))
    def signature = Hex.encodeHexString(hmac.doFinal("GET${epoch_time}${path}".getBytes())).bytes.encodeBase64()
    return "LMv1 ${id}:${signature}:${epoch_time}"
}

def apiGetV2(portalName, apiId, apiKey, endPoint, proxyInfo, Map args = [:]) {
    def request = rawGetV2(portalName, apiId, apiKey, endPoint, proxyInfo, args)
    if (request.getResponseCode() == 200) {
        def payload = new JsonSlurper().parseText(request.content.text)
        return payload
    } else {
        throw new Exception("Server return HTTP code ${request.getResponseCode()} for endpoint ${endPoint}")
    }
}

def rawGetV2(portalName, apiId, apiKey, endPoint, proxyInfo, Map args = [:]) {
    def auth = generateAuth(apiId, apiKey, endPoint)
    def headers = ["Authorization": auth, "Content-Type": "application/json", "X-Version": "3"]
    def url = "https://${portalName}.logicmonitor.com/santaba/rest${endPoint}"
    if (args) {
        def encodedArgs = []
        args.each { k, v ->
            if (k == "filter" || k == "fields" || k == "sort") {
                encodedArgs << "${k}=${v.toString()}"
            } else {
                encodedArgs << "${k}=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}"
            }
        }
        url += "?${encodedArgs.join('&')}"
    }

    def request
    if (proxyInfo.enabled) {
        request = url.toURL().openConnection(proxyInfo.proxy)
    } else {
        request = url.toURL().openConnection()
    }
    request.setRequestMethod("GET")
    request.setDoOutput(true)
    headers.each { k, v ->
        request.addRequestProperty(k, v)
    }

    return request
}

Map getProxyInfo() {
    Boolean deviceProxy = hostProps.get("proxy.enable")?.toBoolean()
    deviceProxy = (deviceProxy != null) ? deviceProxy : true
    Boolean collectorProxy = Settings.getSetting("proxy.enable")?.toBoolean()
    collectorProxy = (collectorProxy != null) ? collectorProxy : false

    Map proxyInfo = [:]

    if (deviceProxy && collectorProxy) {
        proxyInfo = [
            enabled: true,
            host: hostProps.get("proxy.host") ?: Settings.getSetting("proxy.host"),
            port: hostProps.get("proxy.port") ?: Settings.getSetting("proxy.port") ?: 3128,
            user: Settings.getSetting("proxy.user"),
            pass: Settings.getSetting("proxy.pass")
        ]

        proxyInfo["proxy"] = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyInfo.host, proxyInfo.port.toInteger()))
    }

    return proxyInfo
}

println ""
println "=== ALERT COLLECTION TEST COMPLETE ==="
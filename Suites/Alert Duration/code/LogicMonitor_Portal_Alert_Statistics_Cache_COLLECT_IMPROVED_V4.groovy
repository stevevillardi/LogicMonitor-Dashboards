/*******************************************************************************
 * © 2007-2023 - LogicMonitor, Inc. All rights reserved.
 * V4: OPTIMIZED with proper API sorting and startEpoch-based filtering
 ******************************************************************************/

import groovy.json.JsonSlurper
import com.santaba.agent.util.Settings
import com.santaba.agent.live.LiveHostSet
import org.apache.commons.codec.binary.Hex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import groovy.json.*
import java.util.concurrent.*
import java.util.Arrays

String apiId   = hostProps.get("lmaccess.id")  ?: hostProps.get("logicmonitor.access.id")
String apiKey  = hostProps.get("lmaccess.key") ?: hostProps.get("logicmonitor.access.key")
def lookbackPeriod = hostProps.get("alert.analysis.period") ?: hostProps.get("alert.duration.period") ?: "30" //Default to 30 day(s)

def excludeUnACKedAlerts = hostProps.get("alert.analysis.excludeUnACKedAlerts") ?: hostProps.get("alert.duration.excludeUnACKedAlerts") ?: "false" //Default to false
def excludeSDTedAlerts = hostProps.get("alert.analysis.excludeSDTedAlerts") ?: hostProps.get("alert.duration.excludeSDTedAlerts") ?: "false" //Default to false
def datasourceFilter = hostProps.get("alert.analysis.datasourceList") ?: hostProps.get("alert.duration.datasourceList") ?: "*" //Default to include all datasources

def additionalGroupIds = hostProps.get("alert.analysis.includeGroupIds") ?: hostProps.get("alert.duration.includeGroupIds") ?: null //Comma seperated list of group ids to include in discovery

// IMPROVED: Configurable cache size for large environments
def maxAlertCacheSize = hostProps.get("alert.analysis.maxAlertCachePartitions")?.toInteger() ?: 10

// Debug mode for additional output
debugMode = hostProps.get("alert.analysis.debug")?.toBoolean() ?: false
def portalName = hostProps.get("lmaccount")    ?: Settings.getSetting(Settings.AGENT_COMPANY)
Map proxyInfo  = getProxyInfo()

// Performance and timeout management (globally accessible)
SCRIPT_START_TIME = System.currentTimeMillis()
MAX_EXECUTION_TIME = 110000 // 110 seconds (10s buffer)
def executionMetrics = [:]

// IMPROVED: Track success/failure of each component
def componentStatus = [
    deviceGroups: [success: false, cached: false, count: 0, error: null],
    devices: [success: false, cached: false, count: 0, error: null],
    alerts: [success: false, cached: false, count: 0, error: null]
]

/*Calculate start and end date for alert query*/
int timePeriod = 24 * lookbackPeriod.toInteger() //hours for rolling average
Date now = new Date()
long endEpoch = now.getTime() / 1000 //in seconds
long startEpoch = endEpoch - (timePeriod*3600)

def SDTFilter = "*"
def ACKFilter = "*"

if(excludeUnACKedAlerts == "false"){ACKFilter = "*"}
if(excludeSDTedAlerts == "true"){SDTFilter = "false"}

def propfilter = ",acked:\"${ACKFilter}\",sdted:\"${SDTFilter}\",instanceName~\"${datasourceFilter.replace(",", "|")}\""

// V4: CHANGED - Use startEpoch instead of endEpoch for better alert filtering
def filter = "cleared:\"True\",startEpoch>:${startEpoch}" + propfilter
def fields = "severity,monitorObjectId,monitorObjectGroups,id,startEpoch,endEpoch,acked,sdted"

def alerts
def alertSize
def maxQuery = 0
def scriptCache
// Optimized cache timeouts for different data types (max 24 hours)
def alertCacheTimeout = 86400 // 24 hours - historical alerts don't change
def resourceCacheTimeout = 3600 * 4 // 4 hours - devices change infrequently
def groupCacheTimeout = 3600 * 8 // 8 hours - device groups change rarely
def cacheTimeout = alertCacheTimeout // Default for backward compatibility

def cacheHit = 0
def previousCacheSize = 0
def currentCacheSize = 0
def addedToCache = 0
def removedFromCache = 0
def resourcesInCache = 0
def resourcesCacheHit = 0

def deviceGroupsInCache = 0
def deviceGroupsCacheHit = 0

def elapsedTime
def previousRun

def devices
def deviceGroups

// Utility functions for performance and timeout management
def trackPerformance = { String operation, Closure code ->
    def startTime = System.currentTimeMillis()
    try {
        def result = code()
        def duration = System.currentTimeMillis() - startTime
        executionMetrics[operation] = duration
        return result
    } catch (Exception e) {
        println "${operation}Error=1"
        throw e
    }
}

def checkTimeout = { String operation ->
    if ((System.currentTimeMillis() - SCRIPT_START_TIME) >= MAX_EXECUTION_TIME) {
        throw new Exception("Script timeout during: ${operation}")
    }
}

// IMPROVED: Try-catch wrapper for each component
def safeExecute = { String component, Closure code ->
    try {
        def result = code()
        componentStatus[component].success = true
        return result
    } catch (Exception e) {
        componentStatus[component].success = false
        componentStatus[component].error = e.getMessage()
        println "${component}Error=1"
        println "${component}ErrorMessage=${e.getMessage()}"
        return null
    }
}

try {
    scriptCache = this.class.classLoader.loadClass("com.santaba.agent.util.script.ScriptCache").getCache();

    // IMPROVED: Process device groups independently
    safeExecute("deviceGroups") {
        String deviceGroupCache = scriptCache.get("DeviceAlertStats-deviceGroupCache");
        String deviceGroupCacheExp = scriptCache.get("DeviceAlertStats-deviceGroupCache-Expiration");

        if(deviceGroupCacheExp){
            previousRun = Long.valueOf(deviceGroupCacheExp)
            elapsedTime = endEpoch - previousRun
        }

        if(deviceGroupCache == null || elapsedTime >= 3000){
            /*Grab device by type*/
            devicesByTypeId = (apiGetManyV2(portalName, apiId, apiKey, "/device/groups", proxyInfo, ['fields':"id",'filter':"name%3A%22Devices%20by%20Type%22",'size':1000]))[0].id
            deviceGroups = apiGetManyV2(portalName, apiId, apiKey, "/device/groups/${devicesByTypeId}/groups", proxyInfo, ['fields':"id,name,fullPath",'size':1000,'sort':"+displayName"])

            if(additionalGroupIds){
                additionalGroupIds.split(',').each { group ->
                    additionalGroup = apiGetV2(portalName, apiId, apiKey, "/device/groups/${group}", proxyInfo, ['fields':"id,name,fullPath"])
                    deviceGroups.add(additionalGroup)
                }
            }

            if(deviceGroups){
                deviceGroupsJson = new JsonBuilder( deviceGroups ).toPrettyString()
                deviceGroupsExp = Long.toString(endEpoch)
                scriptCache.set("DeviceAlertStats-deviceGroupCache",deviceGroupsJson,groupCacheTimeout * 1000);
                scriptCache.set("DeviceAlertStats-deviceGroupCache-Expiration",deviceGroupsExp,groupCacheTimeout * 1000);
                deviceGroupsInCache = deviceGroups.size()
                componentStatus.deviceGroups.count = deviceGroups.size()
            }
        }
        else{
            deviceGroups = new JsonSlurper().parseText(deviceGroupCache)
            deviceGroupsInCache = deviceGroups.size()
            deviceGroupsCacheHit = 1
            componentStatus.deviceGroups.cached = true
            componentStatus.deviceGroups.count = deviceGroups.size()
        }
    }

    // IMPROVED: Process devices independently
    safeExecute("devices") {
        String resourceCache = scriptCache.get("DeviceAlertStats-ResourceCache");
        String resourceCacheExp = scriptCache.get("DeviceAlertStats-ResourceCache-Expiration");

        if(resourceCacheExp){
            previousRun = Long.valueOf(resourceCacheExp)
            elapsedTime = endEpoch - previousRun
        }

        if(resourceCache == null || elapsedTime >= 3000){
            /*Grab resource*/
            devices = apiGetManyV2(portalName, apiId, apiKey, "/device/devices", proxyInfo, ['fields':"id,displayName,deviceType",'size':1000,'sort':"+displayName"])
            if(devices){
                devicesJson = new JsonBuilder( devices ).toPrettyString()
                devicesExp = Long.toString(endEpoch)
                scriptCache.set("DeviceAlertStats-ResourceCache",devicesJson,resourceCacheTimeout * 1000);
                scriptCache.set("DeviceAlertStats-ResourceCache-Expiration",devicesExp,resourceCacheTimeout * 1000);
                resourcesInCache = devices.size()
                componentStatus.devices.count = devices.size()
            }
        }
        else{
            devices = new JsonSlurper().parseText(resourceCache)
            resourcesInCache = devices.size()
            resourcesCacheHit = 1
            componentStatus.devices.cached = true
            componentStatus.devices.count = devices.size()
        }
    }

    // V4: IMPROVED - Process alerts with proper API sorting and offset pagination
    safeExecute("alerts") {
        /*Check if options changed, if so purge cache and rebuilt*/
        String alertsCacheOptions = scriptCache.get("DeviceAlertStats-AlertsCache-Options");

        if(alertsCacheOptions){
            def alertCacheObject = new JsonSlurper().parseText(alertsCacheOptions)
            if((alertCacheObject.excludeUnACKedAlerts.toString() != excludeUnACKedAlerts) ||
               (alertCacheObject.excludeSDTedAlerts.toString() != excludeSDTedAlerts) ||
               (alertCacheObject.datasourceFilter.toString() != datasourceFilter) ||
               (alertCacheObject.lookbackPeriod.toString() != lookbackPeriod)){
                removeAlertCache(maxAlertCacheSize,"DeviceAlertStats-AlertsCache",scriptCache)

                def alertOptions = new JsonSlurper()
                def alertObject = "{\"excludeUnACKedAlerts\": ${excludeUnACKedAlerts},\"excludeSDTedAlerts\": ${excludeSDTedAlerts},\"datasourceFilter\": \"${datasourceFilter}\",\"lookbackPeriod\": ${lookbackPeriod}}"
                scriptCache.set("DeviceAlertStats-AlertsCache-Options",alertObject,alertCacheTimeout * 1000);
            }
        }
        else{
            def alertOptions = new JsonSlurper()
            def alertObject = "{\"excludeUnACKedAlerts\": ${excludeUnACKedAlerts},\"excludeSDTedAlerts\": ${excludeSDTedAlerts},\"datasourceFilter\": \"${datasourceFilter}\",\"lookbackPeriod\": ${lookbackPeriod}}"
            scriptCache.set("DeviceAlertStats-AlertsCache-Options",alertObject,cacheTimeout * 1000);
        }

        checkTimeout("Alert cache state check")

        // V4: Check if we have existing cache and use incremental collection
        def cacheExists = isCachePopulated(maxAlertCacheSize, "DeviceAlertStats-AlertsCache", scriptCache)

        if (!cacheExists) {
            // First run - collect alerts using time-limited iterations
            if (debugMode) println "InitialCacheCreation=1"

            def allAlerts = []
            def totalCollected = 0
            def apiIterations = 0

            // V4: Collect alerts in 9k chunks with epoch-based pagination
            def currentFilter = filter  // Start with base filter

            while (!checkTimeoutHard() && apiIterations < 20) { // Max 20 iterations
                checkTimeout("Alert collection iteration ${apiIterations + 1}")

                def chunkResult = trackPerformance("alertIteration${apiIterations + 1}") {
                    // Collect up to 9k alerts using multiple 1k API calls
                    collectAlertChunk(portalName, apiId, apiKey, proxyInfo, currentFilter, fields)
                }

                def iterationAlerts = chunkResult.alerts
                def lastTotal = chunkResult.lastTotal
                def hasMoreResults = chunkResult.hasMore

                if (!iterationAlerts || iterationAlerts.size() == 0) {
                    if (debugMode) println "NoMoreAlerts=1"
                    break
                }

                iterationAlerts = formatGroupIds(iterationAlerts)
                allAlerts.addAll(iterationAlerts)
                totalCollected += iterationAlerts.size()
                apiIterations++

                if (debugMode) {
                    println "Iteration${apiIterations}Collected=${iterationAlerts.size()}"
                    println "TotalCollected=${totalCollected}"
                    println "ServerTotal=${lastTotal}"
                    println "HasMoreResults=${hasMoreResults ? 1 : 0}"
                }

                // Stop if no more results available
                if (!hasMoreResults) {
                    if (debugMode) println "ReachedEndOfResults=1"
                    break
                }

                // Stop if we got less than expected (approaching end)
                if (iterationAlerts.size() < 9000) {
                    if (debugMode) println "PartialChunkReceived=${iterationAlerts.size()}"
                    break
                }

                // Prepare next iteration with new epoch filter
                def lastStartEpoch = iterationAlerts.collect { it.startEpoch }.max()
                currentFilter = "cleared:\"True\",startEpoch>${lastStartEpoch}" + propfilter
                if (debugMode) println "NextIterationFilter=startEpoch>${lastStartEpoch}"
            }

            if(allAlerts.size() > 0){
                currentCacheSize = allAlerts.size()
                addedToCache = allAlerts.size()

                try {
                    trackPerformance("alertCacheStorage") {
                        setAlertCacheFixed(maxAlertCacheSize,"DeviceAlertStats-AlertsCache",scriptCache,allAlerts,cacheTimeout)
                    }
                    componentStatus.alerts.count = allAlerts.size()
                    println "InitialCacheComplete=1"
                    println "ApiIterations=${apiIterations}"
                } catch (Exception e) {
                    println "PartialAlertCache=1"
                    println "AlertsCollected=${allAlerts.size()}"
                    println "CacheError=${e.getMessage()}"
                    componentStatus.alerts.error = "Partial: ${e.getMessage()}"
                    componentStatus.alerts.count = allAlerts.size()
                }
            }
        } else {
            // Incremental update - only get new alerts since last cached
            println "IncrementalCacheUpdate=1"
            cacheHit = 1

            // V4: Get the latest startEpoch from cache (not endEpoch)
            def lastCachedStartEpoch = getLastCachedStartEpoch(maxAlertCacheSize, "DeviceAlertStats-AlertsCache", scriptCache)

            // Clean old alerts from cache first
            previousCacheSize = trackPerformance("cacheCleanup") {
                cleanOldAlertsFromCache(maxAlertCacheSize, "DeviceAlertStats-AlertsCache", scriptCache, startEpoch, cacheTimeout)
            }

            currentCacheSize = previousCacheSize

            // V4: Get new alerts since last cached startEpoch
            def newAlertFilter = "cleared:\"True\",startEpoch>${lastCachedStartEpoch}" + propfilter
            def newAlerts = []
            def totalNewCollected = 0
            def newApiIterations = 0

            // Collect new alerts in iterations until timeout
            def currentNewFilter = newAlertFilter

            while (!checkTimeoutHard() && newApiIterations < 9) { // Max 9 iterations for incremental
                checkTimeout("New alert collection iteration ${newApiIterations + 1}")

                def chunkResult = trackPerformance("newAlertIteration${newApiIterations + 1}") {
                    collectAlertChunk(portalName, apiId, apiKey, proxyInfo, currentNewFilter, fields)
                }

                def iterationAlerts = chunkResult.alerts
                def totalOnServer = chunkResult.lastTotal
                def hasMoreResults = chunkResult.hasMore

                if (!iterationAlerts || iterationAlerts.size() == 0) {
                    if (debugMode) println "NoNewAlertsFound=1"
                    break
                }

                iterationAlerts = formatGroupIds(iterationAlerts)
                newAlerts.addAll(iterationAlerts)
                totalNewCollected += iterationAlerts.size()
                newApiIterations++

                if (debugMode) {
                    println "NewIteration${newApiIterations}Collected=${iterationAlerts.size()}"
                    println "TotalNewCollected=${totalNewCollected}"
                    println "NewServerTotal=${totalOnServer}"
                    println "NewHasMoreResults=${hasMoreResults ? 1 : 0}"
                }

                // Stop if no more results available
                if (!hasMoreResults) {
                    if (debugMode) println "ReachedEndOfNewResults=1"
                    break
                }

                // Stop if we got less than expected
                if (iterationAlerts.size() < 9000) {
                    if (debugMode) println "PartialNewChunkReceived=${iterationAlerts.size()}"
                    break
                }

                // Prepare next iteration with new epoch filter
                def lastStartEpoch = iterationAlerts.collect { it.startEpoch }.max()
                currentNewFilter = "cleared:\"True\",startEpoch>${lastStartEpoch}" + propfilter
            }

            if (newAlerts.size() > 0) {
                addedToCache = newAlerts.size()
                try {
                    trackPerformance("incrementalCacheUpdate") {
                        addAlertsToCache(maxAlertCacheSize, "DeviceAlertStats-AlertsCache", scriptCache, newAlerts, cacheTimeout)
                    }
                    currentCacheSize = getCurrentCacheSize(maxAlertCacheSize, "DeviceAlertStats-AlertsCache", scriptCache)
                    componentStatus.alerts.count = currentCacheSize
                    componentStatus.alerts.cached = true
                    if (debugMode) {
                        println "IncrementalComplete=1"
                        println "NewApiIterations=${newApiIterations}"
                    }
                } catch (Exception e) {
                    if (debugMode) {
                        println "IncrementalUpdateFailed=1"
                        println "NewAlertsCollected=${newAlerts.size()}"
                        println "UpdateError=${e.getMessage()}"
                    }
                    componentStatus.alerts.error = "Incremental update failed"
                    componentStatus.alerts.count = previousCacheSize
                }
            } else {
                addedToCache = 0
                componentStatus.alerts.count = currentCacheSize
                componentStatus.alerts.cached = true
                if (debugMode) println "NoNewAlerts=1"
            }

            removedFromCache = Math.max(0, previousCacheSize - currentCacheSize + addedToCache)
        }
    }

    // IMPROVED: Always output metrics, even with partial failures
    def totalExecutionTime = System.currentTimeMillis() - SCRIPT_START_TIME

    // Output alert cache metrics
    println "alertCacheHit=${cacheHit}"
    println "currentCacheSize=${currentCacheSize}"
    println "previousCacheSize=${previousCacheSize}"
    println "removedFromCache=${removedFromCache}"
    println "addedToCache=${addedToCache}"
    println "lookbackPeriod=${lookbackPeriod}"
    println "maxAlertCachePartitions=${maxAlertCacheSize}"

    // Output device/group cache metrics
    println "resourcesInCache=${resourcesInCache}"
    println "resourcesCacheHit=${resourcesCacheHit}"
    println "deviceGroupsInCache=${deviceGroupsInCache}"
    println "deviceGroupsCacheHit=${deviceGroupsCacheHit}"

    // Output performance metrics
    println "totalExecutionTime=${totalExecutionTime}"
    println "maxAlertQueryCount=${maxQuery}"

    // Output component status
    println "deviceGroupsSuccess=${componentStatus.deviceGroups.success ? 1 : 0}"
    println "devicesSuccess=${componentStatus.devices.success ? 1 : 0}"
    println "alertsSuccess=${componentStatus.alerts.success ? 1 : 0}"

    // Output individual operation timings
    executionMetrics.each { operation, duration ->
        println "${operation}=${duration}"
    }

    // Memory cleanup
    alerts?.clear()
    deviceGroups?.clear()
    devices?.clear()
    executionMetrics?.clear()
}
catch (Exception ex) {
    def executionTime = System.currentTimeMillis() - SCRIPT_START_TIME
    println "ScriptError=1"
    println "ErrorMessage=${ex.getMessage()}"
    println "ExecutionTimeAtError=${executionTime}"
    println "TimeoutExceeded=${executionTime >= MAX_EXECUTION_TIME ? 1 : 0}"

    // IMPROVED: Still output what we collected before error
    println "deviceGroupsInCache=${deviceGroupsInCache}"
    println "deviceGroupsCacheHit=${deviceGroupsCacheHit}"
    println "resourcesInCache=${resourcesInCache}"
    println "resourcesCacheHit=${resourcesCacheHit}"
    println "alertCacheHit=${cacheHit}"
    println "currentCacheSize=${currentCacheSize}"

    // Output any performance metrics we collected before the error
    executionMetrics.each { operation, duration ->
        println "${operation}=${duration}"
    }

    println ex
    return -1
}

// V4: NEW - Hard timeout check (10 seconds remaining)
def checkTimeoutHard() {
    def remainingTime = MAX_EXECUTION_TIME - (System.currentTimeMillis() - SCRIPT_START_TIME)
    return remainingTime < 10000 // Stop with 10 seconds remaining
}

// V4: NEW - Collect up to 9k alerts using multiple 1k API calls
def collectAlertChunk(portalName, apiId, apiKey, proxyInfo, filter, fields) {
    def allAlerts = []
    def lastTotal = 0
    def hasMore = false
    def offset = 0
    def batchSize = 1000
    def maxAlertsInChunk = 9000

    while (allAlerts.size() < maxAlertsInChunk && !checkTimeoutHard()) {
        def args = [
            'filter': filter,
            'fields': fields,
            'size': batchSize,
            'offset': offset,
            'sort': '+startEpoch'
        ]

        def response = apiGetV2(portalName, apiId, apiKey, "/alert/alerts", proxyInfo, args)
        if (response.get("errmsg", "OK") != "OK") {
            throw new Exception("Santaba returned errormsg: ${response?.errmsg}")
        }

        def items = response.items ?: []
        lastTotal = response.total ?: 0
        hasMore = lastTotal < 0

        if (items.size() == 0) {
            break // No more alerts in this batch
        }

        allAlerts.addAll(items)
        offset += batchSize

        if (debugMode) println "    SubBatch: offset=${offset - batchSize}, got=${items.size()}, total=${lastTotal}, hasMore=${hasMore}"

        // Stop if we got fewer than requested (end of data)
        if (items.size() < batchSize) {
            break
        }

        // Stop if we would exceed 9k in next iteration
        if (allAlerts.size() + batchSize > maxAlertsInChunk) {
            break
        }
    }

    return [
        alerts: allAlerts,
        lastTotal: lastTotal,
        hasMore: hasMore
    ]
}


// V4: NEW - Get last cached startEpoch instead of endEpoch
def getLastCachedStartEpoch(cacheLimit, cacheName, cacheObject) {
    def maxStartEpoch = 0

    for (int i = 0; i < cacheLimit; i++) {
        def partitionJson = cacheObject.get("${cacheName}-${i}")
        if (!partitionJson) break

        try {
            def partition = new JsonSlurper().parseText(partitionJson)
            if (partition && partition.size() > 0) {
                def partitionMax = partition.collect { it.startEpoch }.max()
                if (partitionMax > maxStartEpoch) {
                    maxStartEpoch = partitionMax
                }
            }
        } catch (Exception e) {
            // Skip corrupted partition
            continue
        }
    }

    return maxStartEpoch
}

// V4: UPDATED - Clean based on startEpoch, not endEpoch
def cleanOldAlertsFromCache(cacheLimit, cacheName, cacheObject, startEpoch, cacheTimeout) {
    def totalValidAlerts = 0
    def compactedPartitions = []
    def partitionSize = 15000

    // Read all partitions and filter out old alerts
    for (int i = 0; i < cacheLimit; i++) {
        def partitionJson = cacheObject.get("${cacheName}-${i}")
        if (!partitionJson) break

        try {
            def partition = new JsonSlurper().parseText(partitionJson)
            // V4: Filter by startEpoch instead of endEpoch
            def validAlerts = partition.findAll { it.startEpoch > startEpoch }

            if (validAlerts.size() > 0) {
                compactedPartitions.add(validAlerts)
                totalValidAlerts += validAlerts.size()
            }
        } catch (Exception e) {
            // Skip corrupted partition
            continue
        }
    }

    // Clear all partitions
    removeAlertCache(cacheLimit, cacheName, cacheObject)

    // Rebuild partitions with valid alerts only
    def allValidAlerts = []
    compactedPartitions.each { alerts -> allValidAlerts.addAll(alerts) }

    if (allValidAlerts.size() > 0) {
        // V4: Sort alerts by startEpoch to maintain chronological order
        allValidAlerts = allValidAlerts.sort { it.startEpoch }

        def newPartitions = allValidAlerts.collate(partitionSize)
        newPartitions.eachWithIndex { alerts, i ->
            if (i < cacheLimit) {
                def alertsJson = new JsonBuilder(alerts).toString()
                cacheObject.set("${cacheName}-${i}", alertsJson, cacheTimeout * 1000)
            }
        }
    }

    return totalValidAlerts
}

// NEW: Helper functions for incremental caching (keeping existing ones)
def isCachePopulated(cacheLimit, cacheName, cacheObject) {
    return cacheObject.get("${cacheName}-0") != null
}

def getCurrentCacheSize(cacheLimit, cacheName, cacheObject) {
    def totalAlerts = 0

    for (int i = 0; i < cacheLimit; i++) {
        def partitionJson = cacheObject.get("${cacheName}-${i}")
        if (!partitionJson) break

        try {
            def partition = new JsonSlurper().parseText(partitionJson)
            totalAlerts += partition.size()
        } catch (Exception e) {
            continue
        }
    }

    return totalAlerts
}

def addAlertsToCache(cacheLimit, cacheName, cacheObject, newAlerts, cacheTimeout) {
    def partitionSize = 15000

    // V4: Sort new alerts by startEpoch before adding
    newAlerts = newAlerts.sort { it.startEpoch }

    // Find the last partition with space
    def lastPartitionIndex = -1
    def lastPartition = []

    for (int i = 0; i < cacheLimit; i++) {
        def partitionJson = cacheObject.get("${cacheName}-${i}")
        if (!partitionJson) {
            lastPartitionIndex = i
            break
        } else {
            try {
                def partition = new JsonSlurper().parseText(partitionJson)
                if (partition.size() < partitionSize) {
                    lastPartitionIndex = i
                    lastPartition = partition
                    break
                }
                lastPartitionIndex = i + 1
            } catch (Exception e) {
                // Corrupted partition, replace it
                lastPartitionIndex = i
                break
            }
        }
    }

    if (lastPartitionIndex >= cacheLimit) {
        throw new Exception("Cache partition limit reached")
    }

    def remainingAlerts = new ArrayList(newAlerts)

    // Fill existing partial partition if exists
    if (lastPartition.size() > 0 && lastPartition.size() < partitionSize) {
        def spaceAvailable = partitionSize - lastPartition.size()
        def alertsToAdd = Math.min(spaceAvailable, remainingAlerts.size())

        lastPartition.addAll(remainingAlerts.take(alertsToAdd))
        remainingAlerts = remainingAlerts.drop(alertsToAdd)

        def alertsJson = new JsonBuilder(lastPartition).toString()
        cacheObject.set("${cacheName}-${lastPartitionIndex}", alertsJson, cacheTimeout * 1000)

        lastPartitionIndex++
    }

    // Create new partitions for remaining alerts
    while (remainingAlerts.size() > 0 && lastPartitionIndex < cacheLimit) {
        def alertsForPartition = remainingAlerts.take(partitionSize)
        remainingAlerts = remainingAlerts.drop(partitionSize)

        def alertsJson = new JsonBuilder(alertsForPartition).toString()
        cacheObject.set("${cacheName}-${lastPartitionIndex}", alertsJson, cacheTimeout * 1000)

        lastPartitionIndex++
    }

    if (remainingAlerts.size() > 0) {
        throw new Exception("Could not cache ${remainingAlerts.size()} alerts - partition limit reached")
    }
}

// FIXED: Original cache functions with proper partition sizing
def removeAlertCache(cacheLimit,cacheName,cacheObject){
    def i = 0
    while(i < cacheLimit){
        cacheObject.remove("${cacheName}-${i}")
        i++
    }
}

def getAlertCache(cacheLimit,cacheName,cacheObject){
    def alerts = []
    def i = 0
    while(i < cacheLimit){
        String alertsCache = cacheObject.get("${cacheName}-${i}");
        if(alertsCache == null){
            break
        }
        else{
            def cachedAlerts = new JsonSlurper().parseText(alertsCache)
            alerts.addAll(cachedAlerts)
            i++
        }
    }
    if(alerts){
        return new JsonBuilder( alerts ).toString()
    }
    else{
        return null
    }
}

// FIXED: Simple partition creation with 15k per partition
def setAlertCacheFixed(cacheLimit,cacheName,cacheObject,cacheAlerts,cacheTimeout){
    def partitionSize = 15000

    // V4: Sort alerts by startEpoch before partitioning
    cacheAlerts = cacheAlerts.sort { it.startEpoch }

    // Clear old cache partitions first
    removeAlertCache(cacheLimit, cacheName, cacheObject)

    def alertLists = cacheAlerts.collate(partitionSize)

    // Check partition count before processing
    if (alertLists.size() > cacheLimit) {
        println "WarnPartitionCount=${alertLists.size()}"
        println "MaxPartitionsAllowed=${cacheLimit}"
        println "AlertsToCache=${cacheAlerts.size()}"
        println "PartitionSize=${partitionSize}"

        // Only cache what we can fit
        alertLists = alertLists.take(cacheLimit)
        println "TruncatedToPartitions=${alertLists.size()}"
        println "TruncatedToAlerts=${alertLists.size() * partitionSize}"
    }

    alertLists.eachWithIndex { alerts, i ->
        if (i < cacheLimit) {
            def alertsJson = new JsonBuilder(alerts).toString()
            cacheObject.set("${cacheName}-${i}", alertsJson, cacheTimeout * 1000)
            alerts.clear()
        }
    }

    // Store metadata
    def metadata = new JsonBuilder([
        partitions: Math.min(alertLists.size(), cacheLimit),
        totalSize: Math.min(cacheAlerts.size(), cacheLimit * partitionSize),
        partitionSize: partitionSize,
        timestamp: System.currentTimeMillis(),
        truncated: alertLists.size() > cacheLimit,
        sortedBy: "startEpoch"  // V4: Track sorting method
    ]).toString()
    cacheObject.set("${cacheName}-meta", metadata, cacheTimeout * 1000)
}

def formatGroupIds(alerts){
    alerts.each{alert ->
        alert.monitorObjectGroups.each{group ->
            if(alert.groupIds){
                alert.groupIds = "${alert.groupIds},${(group.findAll {it.key == 'id'}).id}"
            }
            else{
                alert.groupIds = "${(group.findAll {it.key == 'id'}).id}"
            }
        }
        alert.remove('monitorObjectGroups')
    }
    return alerts
}

// API helper functions (unchanged)
static String generateAuth(id, key, path) {
    Long epoch_time = System.currentTimeMillis()
    Mac hmac = Mac.getInstance("HmacSHA256")
    hmac.init(new SecretKeySpec(key.getBytes(), "HmacSHA256"))
    def signature = Hex.encodeHexString(hmac.doFinal("GET${epoch_time}${path}".getBytes())).bytes.encodeBase64()

    return "LMv1 ${id}:${signature}:${epoch_time}"
}

List apiGetManyV2(portalName, apiId, apiKey, endPoint, proxyInfo, Map args=[:]) {
    def pageSize = args.get('size', 1000)
    List items = []
    args['size'] = pageSize

    def pageCount = 0
    while (true) {
        pageCount += 1
        args['size'] = pageSize
        args['offset'] = items.size()

        def response = apiGetV2(portalName, apiId, apiKey, endPoint, proxyInfo, args)
        if (response.get("errmsg", "OK") != "OK") {
            throw new Exception("Santaba returned errormsg: ${response?.errmsg}")
        }
        items.addAll(response.items)

        if (response.items.size() < pageSize) break
    }
    return items
}

def apiGetV2(portalName, apiId, apiKey, endPoint, proxyInfo, Map args=[:]) {
    def request = rawGetV2(portalName, apiId, apiKey, endPoint, proxyInfo, args)
    if (request.getResponseCode() == 200) {
        def payload = new JsonSlurper().parseText(request.content.text)
        return payload
    }
    else {
        throw new Exception("Server return HTTP code ${request.getResponseCode()} for endpoint ${endPoint}")
    }
}

def rawGetV2(portalName, apiId, apiKey, endPoint, proxyInfo, Map args=[:]) {
    def auth = generateAuth(apiId, apiKey, endPoint)
    def headers = ["Authorization": auth, "Content-Type": "application/json", "X-Version":"3"]
    def url = "https://${portalName}.logicmonitor.com/santaba/rest${endPoint}"
    if (args) {
        def encodedArgs = []
        args.each{ k,v ->
            if(k == "filter" || k == "fields" || k == "sort"){
                encodedArgs << "${k}=${v.toString()}"
            }
            else{
                encodedArgs << "${k}=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}"
            }
        }
        url += "?${encodedArgs.join('&')}"
    }

    def request
    if (proxyInfo.enabled) {
        request = url.toURL().openConnection(proxyInfo.proxy)
    }
    else {
        request = url.toURL().openConnection()
    }
    request.setRequestMethod("GET")
    request.setDoOutput(true)
    headers.each{ k,v ->
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
            enabled : true,
            host : hostProps.get("proxy.host") ?: Settings.getSetting("proxy.host"),
            port : hostProps.get("proxy.port") ?: Settings.getSetting("proxy.port") ?: 3128,
            user : Settings.getSetting("proxy.user"),
            pass : Settings.getSetting("proxy.pass")
        ]

        proxyInfo["proxy"] = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyInfo.host, proxyInfo.port.toInteger()))
    }

    return proxyInfo
}
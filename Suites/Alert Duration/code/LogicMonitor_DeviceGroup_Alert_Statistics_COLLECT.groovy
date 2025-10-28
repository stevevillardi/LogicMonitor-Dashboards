/*******************************************************************************
 * © 2007-2023 - LogicMonitor, Inc. All rights reserved.
 ******************************************************************************/
import groovy.json.*
import java.util.Arrays

def durationPeriod = hostProps.get("alert.analysis.period") ?: hostProps.get("alert.duration.period") ?: "30" //Default to 30 day(s)
def durationMinimum = hostProps.get("alert.analysis.minimumTimeInSeconds") ?: hostProps.get("alert.duration.minimumTimeInSeconds") ?: "0" //Default to include alerts of any duration)
def maxAlertCacheSize = 100 //Number of scriptCache keys to utilize since max cache size is limited to around 20,000 alerts

def scriptCache

// Performance and timeout management
def SCRIPT_START_TIME = System.currentTimeMillis()
def MAX_EXECUTION_TIME = 110000 // 110 seconds (10s buffer)
def executionMetrics = [:]

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

/*Calculate start and end date for alert query*/
int timePeriod = 24 * durationPeriod.toInteger() //hours for rolling average
Date now = new Date()
long endEpoch = now.getTime() / 1000
long startEpoch = endEpoch - (timePeriod*3600)

def alerts
def groups

try {
    checkTimeout("Initial cache setup")

    scriptCache = this.class.classLoader.loadClass("com.santaba.agent.util.script.ScriptCache").getCache();

    // Load alerts from cache with performance tracking
    String alertsCache = trackPerformance("alertCacheRetrieval") {
        getAlertCache(maxAlertCacheSize,"DeviceAlertStats-AlertsCache",scriptCache)
    }
    if(alertsCache){
        alerts = trackPerformance("alertsParsing") {
            new JsonSlurper().parseText(alertsCache)
        }
    }

    // Load groups from cache with performance tracking
    String groupCache = trackPerformance("groupCacheRetrieval") {
        scriptCache.get("DeviceAlertStats-deviceGroupCache")
    }
    if(groupCache){
        groups = trackPerformance("groupsParsing") {
            new JsonSlurper().parseText(groupCache)
        }
    }
}
catch (Exception ex) {
    def executionTime = System.currentTimeMillis() - SCRIPT_START_TIME
    println "ScriptError=1"
    println "ErrorMessage=${ex.getMessage()}"
    println "ExecutionTimeAtError=${executionTime}"
    println "TimeoutExceeded=${executionTime >= MAX_EXECUTION_TIME ? 1 : 0}"

    // Output any performance metrics we collected before the error
    executionMetrics.each { operation, duration ->
        println "${operation}=${duration}"
    }

    println ex
    return -1
}

if(alerts && groups){
    checkTimeout("Alert processing")

    // Pre-index alerts by group IDs for efficient lookup instead of O(n²) findAll
    def alertsByGroup = trackPerformance("alertGroupIndexing") {
        def groupIndex = [:]
        alerts.each { alert ->
            if(alert.groupIds) {
                alert.groupIds.split(',').each { groupId ->
                    def trimmedGroupId = groupId.trim()
                    if(!groupIndex[trimmedGroupId]) {
                        groupIndex[trimmedGroupId] = []
                    }
                    groupIndex[trimmedGroupId] << alert
                }
            }
        }
        return groupIndex
    }

    def processedGroups = 0
    groups.each { group ->
        checkTimeout("Processing group ${group.id}")

        def groupAlerts = alertsByGroup[group.id.toString()]
        if(groupAlerts){
            // Process alerts for this group with single-pass optimization
            def processedAlerts = trackPerformance("groupAlertProcessing") {
                processGroupAlertsOptimized(groupAlerts, durationMinimum.toInteger())
            }

            calcDurationOptimized(processedAlerts.total,"", group.id)
            calcDurationOptimized(processedAlerts.warn,"warn", group.id)
            calcDurationOptimized(processedAlerts.error,"error", group.id)
            calcDurationOptimized(processedAlerts.critical,"critical", group.id)
        }
        else{
            // Output zero values for groups with no alerts
            outputZeroMetrics(group.id)
        }
        println "${group.id}.durationPeriod=${durationPeriod}"

        processedGroups++

        // Periodic timeout check for large group lists
        if(processedGroups % 50 == 0) {
            checkTimeout("Processed ${processedGroups} groups")
        }
    }

    // Performance and execution metrics
    def totalExecutionTime = System.currentTimeMillis() - SCRIPT_START_TIME
    println "totalExecutionTime=${totalExecutionTime}"
    println "processedGroups=${processedGroups}"
    println "alertsProcessed=${alerts.size()}"

    // Output individual operation timings
    executionMetrics.each { operation, duration ->
        println "${operation}=${duration}"
    }

    // Memory cleanup
    alerts?.clear()
    groups?.clear()
    alertsByGroup?.clear()
    executionMetrics?.clear()
}

return 0

def getAlertCache(cacheLimit,cacheName,cacheObject){
    def alerts = []
    def i = 0
    while(i < cacheLimit){
        String alertsCache = cacheObject.get("${cacheName}-${i}");
        // println "Getting cache index: ${i}"
        if(alertsCache == null){
            break
        }
        else{
            def cachedAlerts = new JsonSlurper().parseText(alertsCache)
            alerts.addAll(cachedAlerts)
            i++
        }
    }
    // println "Total cache size accross ${i} index(es), alert size ${alerts.size()}"
    if(alerts){
        return new JsonBuilder( alerts ).toString() // Use toString() for better performance
    }
    else{
        // println "No cache found"
        return null
    }
}

// Optimized single-pass alert processing function for groups
def processGroupAlertsOptimized(groupAlerts, durationMinimum) {
    def stats = [
        total: [count: 0, durations: []],
        warn: [count: 0, durations: []],
        error: [count: 0, durations: []],
        critical: [count: 0, durations: []]
    ]

    groupAlerts.each { alert ->
        double duration = alert.endEpoch - alert.startEpoch

        // Early termination for short alerts
        if (duration <= durationMinimum) return

        // Single calculation, multiple uses
        def severityKey = getSeverityKey(alert.severity.toInteger())

        stats.total.count++
        stats.total.durations << duration

        if (severityKey) {
            stats[severityKey].count++
            stats[severityKey].durations << duration
        }
    }

    return stats
}

// Helper function for severity mapping
private getSeverityKey(severity) {
    switch(severity) {
        case 2: return 'warn'
        case 3: return 'error'
        case 4: return 'critical'
        default: return null
    }
}

// Optimized percentile calculation using Arrays.sort for better performance
def getPercentileOptimized(collection, percentile) {
    if (!collection || collection.empty) return [percentileValue: 0.0, longestDuration: 0.0]

    // Use Arrays.sort for better performance
    def values = collection as double[]
    Arrays.sort(values)

    int index = Math.min((int)(values.length * percentile), values.length - 1)
    double longestDuration = (values[values.length - 1] / 60).round(2)

    // Calculate percentile value without modifying array
    double percentileValue = 0.0
    if (index > 0) {
        def sum = 0.0
        for (int i = 0; i <= index; i++) {
            sum += values[i]
        }
        percentileValue = ((sum / (index + 1)) / 60).round(2)
    }

    return [percentileValue: percentileValue, longestDuration: longestDuration]
}

// Optimized calculation function with better performance
def calcDurationOptimized(alertStats, dpPrefix, groupId) {
    if(alertStats.durations && !alertStats.durations.empty){
        println "${groupId}.${dpPrefix}AlertSize=${alertStats.count}"

        double duration = ((alertStats.durations.sum() / alertStats.durations.size()) / 60).round(2)
        println "${groupId}.${dpPrefix}AverageDuration=${duration}"

        def percentileResult = getPercentileOptimized(alertStats.durations, 0.95)
        println "${groupId}.${dpPrefix}AverageDuration95th=${percentileResult.percentileValue}"
        println "${groupId}.${dpPrefix}longestAlertDuration=${percentileResult.longestDuration}"
    }
    else{
        outputZeroMetricsForPrefix(groupId, dpPrefix)
    }
}

// Output zero metrics for a specific prefix
def outputZeroMetricsForPrefix(groupId, dpPrefix) {
    println "${groupId}.${dpPrefix}longestAlertDuration=0"
    println "${groupId}.${dpPrefix}AlertSize=0"
    println "${groupId}.${dpPrefix}AverageDuration=0"
    println "${groupId}.${dpPrefix}AverageDuration95th=0"
}

// Consolidated zero metrics output function for all prefixes
def outputZeroMetrics(groupId) {
    ["", "warn", "error", "critical"].each { prefix ->
        println "${groupId}.${prefix}longestAlertDuration=0"
        println "${groupId}.${prefix}AlertSize=0"
        println "${groupId}.${prefix}AverageDuration=0"
        println "${groupId}.${prefix}AverageDuration95th=0"
    }
}

def calcDuration(collection, dpPrefix, groupId) {
    if(collection){
        println "${groupId}.${dpPrefix}AlertSize=${collection.size()}"

        double duration = ((collection."${dpPrefix}Duration".sum() / collection.size()) / 60).round(2)
        println "${groupId}.${dpPrefix}AverageDuration=${duration}"

        double duration95th =  getPercentile(collection."${dpPrefix}Duration", 0.95, dpPrefix, groupId)
        println "${groupId}.${dpPrefix}AverageDuration95th=${duration95th}"
    }
    else{
        println "${groupId}.${dpPrefix}AlertSize=0"
        println "${groupId}.${dpPrefix}AverageDuration=0"
        println "${groupId}.${dpPrefix}AverageDuration95th=0"
        println "${groupId}.${dpPrefix}AverageDuration95th=0"
        println "${groupId}.${dpPrefix}LongestAlertDuration=0"
    }
}

// Legacy function kept for compatibility (now optimized)
Double getPercentile(collection, percentile, dpPrefix, groupId) {
    def result = getPercentileOptimized(collection, percentile)
    println "${groupId}.${dpPrefix}longestAlertDuration=${result.longestDuration}"
    return result.percentileValue
}
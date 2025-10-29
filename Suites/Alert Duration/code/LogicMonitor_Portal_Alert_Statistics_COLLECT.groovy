/*******************************************************************************
 * © 2007-2023 - LogicMonitor, Inc. All rights reserved.
 ******************************************************************************/
import groovy.json.*
import java.util.Arrays

def durationPeriod = hostProps.get("alert.analysis.period") ?: hostProps.get("alert.duration.period") ?: "30" //Default to 30 day(s)
def durationMinimum = hostProps.get("alert.analysis.minimumTimeInSeconds") ?: hostProps.get("alert.duration.minimumTimeInSeconds") ?: "0" //Default to include alerts of any duration)

def maxAlertCacheSize = hostProps.get("alert.analysis.maxAlertCachePartitions")?.toInteger() ?: 10

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
def scriptCache

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

if(alerts){
    checkTimeout("Alert processing")

    // Process alerts with single-pass optimization
    def processedAlerts = trackPerformance("alertProcessing") {
        processAlertsOptimized(alerts, durationMinimum.toInteger())
    }

    calcDurationOptimized(processedAlerts.total,"")
    calcDurationOptimized(processedAlerts.warn,"warn")
    calcDurationOptimized(processedAlerts.error,"error")
    calcDurationOptimized(processedAlerts.critical,"critical")

    // Performance and execution metrics
    def totalExecutionTime = System.currentTimeMillis() - SCRIPT_START_TIME
    println "totalExecutionTime=${totalExecutionTime}"
    println "alertsProcessed=${alerts.size()}"

    // Output individual operation timings
    executionMetrics.each { operation, duration ->
        println "${operation}=${duration}"
    }

    // Memory cleanup
    alerts?.clear()
    executionMetrics?.clear()
}
else{
    outputZeroMetrics()
}
println "durationPeriod=${durationPeriod}"

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

// Optimized single-pass alert processing function
def processAlertsOptimized(alerts, durationMinimum) {
    def stats = [
        total: [count: 0, durations: []],
        warn: [count: 0, durations: []],
        error: [count: 0, durations: []],
        critical: [count: 0, durations: []]
    ]

    alerts.each { alert ->
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
def calcDurationOptimized(alertStats, dpPrefix) {
    if(alertStats.durations && !alertStats.durations.empty){
        println "${dpPrefix}alertSize=${alertStats.count}"

        double duration = ((alertStats.durations.sum() / alertStats.durations.size()) / 60).round(2)
        println "${dpPrefix}averageDuration=${duration}"

        def percentileResult = getPercentileOptimized(alertStats.durations, 0.95)
        println "${dpPrefix}averageDuration95th=${percentileResult.percentileValue}"
        println "${dpPrefix}longestAlertDuration=${percentileResult.longestDuration}"
    }
    else{
        outputZeroMetricsForPrefix(dpPrefix)
    }
}

// Output zero metrics for a specific prefix
def outputZeroMetricsForPrefix(dpPrefix) {
    println "${dpPrefix}longestAlertDuration=0"
    println "${dpPrefix}alertSize=0"
    println "${dpPrefix}averageDuration=0"
    println "${dpPrefix}averageDuration95th=0"
}

// Consolidated zero metrics output function for all prefixes
def outputZeroMetrics() {
    ["", "warn", "error", "critical"].each { prefix ->
        println "${prefix}longestAlertDuration=0"
        println "${prefix}alertSize=0"
        println "${prefix}averageDuration=0"
        println "${prefix}averageDuration95th=0"
    }
}

// Legacy function kept for compatibility (now optimized)
def calcDuration(collection, dpPrefix) {
    if(collection){
        println "${dpPrefix}AlertSize=${collection.size()}"

        double duration = ((collection."${dpPrefix}Duration".sum() / collection.size()) / 60).round(2)
        println "${dpPrefix}AverageDuration=${duration}"

        double duration95th =  getPercentile(collection."${dpPrefix}Duration", 0.95)
        println "${dpPrefix}AverageDuration95th=${duration95th}"
    }
    else{
        println "${dpPrefix}AlertSize=0"
        println "${dpPrefix}AverageDuration=0"
        println "${dpPrefix}AverageDuration95th=0"
    }
}

Double getPercentile(collection, percentile) {
    def result = getPercentileOptimized(collection, percentile)
    return result.percentileValue
}
/*******************************************************************************
 * Alert Cache Inspector Script
 * Lists current cache partitions and their sizes for debugging/monitoring
 ******************************************************************************/

import groovy.json.JsonSlurper
import groovy.json.*

// Configuration
def maxAlertCachePartitions = 100
def scriptCache

try {
    scriptCache = this.class.classLoader.loadClass("com.santaba.agent.util.script.ScriptCache").getCache()

    println "=== Alert Cache Inspection ==="
    println "Timestamp: ${new Date()}"
    println ""

    // Check collection state
    String stateJson = scriptCache.get("DeviceAlertStats-CollectionState")
    if (stateJson) {
        def state = new JsonSlurper().parseText(stateJson)
        println "Collection State:"
        println "  - Complete: ${state.isComplete}"
        println "  - Current Partition: ${state.currentPartition}"
        println "  - Last End Epoch: ${state.lastEndEpoch}"
        println "  - Total Processed: ${state.totalProcessed}"
        if (state.lastEndEpoch) {
            def lastDate = new Date(state.lastEndEpoch * 1000)
            println "  - Last Date: ${lastDate}"
        }
        println ""
    } else {
        println "Collection State: Not found (fresh start)"
        println ""
    }

    // Check cache options
    String optionsJson = scriptCache.get("DeviceAlertStats-AlertsCache-Options")
    if (optionsJson) {
        def options = new JsonSlurper().parseText(optionsJson)
        println "Cache Options:"
        println "  - Exclude UnACKed: ${options.excludeUnACKedAlerts}"
        println "  - Exclude SDTed: ${options.excludeSDTedAlerts}"
        println "  - Datasource Filter: ${options.datasourceFilter}"
        println "  - Lookback Period: ${options.lookbackPeriod}"
        println ""
    } else {
        println "Cache Options: Not found"
        println ""
    }

    // Check alert partitions with detailed time analysis
    println "Alert Cache Partitions:"
    println "Partition | Size  | Format  | Sample Alert"
    println "----------|-------|---------|-------------"

    def totalAlerts = 0
    def activePartitions = 0
    def allStartEpochs = []
    def allEndEpochs = []

    for (int i = 0; i < maxAlertCachePartitions; i++) {
        String partitionData = scriptCache.get("DeviceAlertStats-AlertsCache-${i}")

        if (partitionData) {
            try {
                def alerts = new JsonSlurper().parseText(partitionData)
                def partitionSize = alerts.size()
                totalAlerts += partitionSize
                activePartitions++

                // Determine format and get epoch data
                def format = "Unknown"
                def sampleAlert = ""
                def startEpochs = []
                def endEpochs = []

                if (alerts && alerts.size() > 0) {
                    def firstAlert = alerts[0]

                    if (firstAlert.severity != null) {
                        format = "Original"
                        sampleAlert = "ID:${firstAlert.id}, Sev:${firstAlert.severity}"
                        startEpochs = alerts.collect { it.startEpoch }.findAll { it != null }
                        endEpochs = alerts.collect { it.endEpoch }.findAll { it != null }
                    } else if (firstAlert.sev != null) {
                        format = "Optimized"
                        sampleAlert = "ID:${firstAlert.id}, Sev:${firstAlert.sev}"
                        startEpochs = alerts.collect { it.start }.findAll { it != null }
                        endEpochs = alerts.collect { it.end }.findAll { it != null }
                    }
                }

                printf "%9d | %5d | %-7s | %s%n", i, partitionSize, format, sampleAlert

                // Detailed time analysis for this partition
                if (startEpochs.size() > 0 && endEpochs.size() > 0) {
                    def sortedStartEpochs = startEpochs.sort()
                    def sortedEndEpochs = endEpochs.sort()

                    def minStart = sortedStartEpochs.first()
                    def middleStart = sortedStartEpochs[Math.floor(sortedStartEpochs.size() / 2) as int]
                    def maxStart = sortedStartEpochs.last()

                    def minEnd = sortedEndEpochs.first()
                    def middleEnd = sortedEndEpochs[Math.floor(sortedEndEpochs.size() / 2) as int]
                    def maxEnd = sortedEndEpochs.last()

                    // Convert epoch seconds to milliseconds using Long arithmetic to avoid overflow
                    def convertEpochToDate = { epoch ->
                        // Use Long arithmetic to prevent integer overflow
                        def epochInMs = (Long.valueOf(epoch)) * 1000L
                        return new Date(epochInMs)
                    }

                    // Debug: show raw epoch values
                    println "          DEBUG: Raw epoch values"
                    println "            Start min/max: ${minStart} / ${maxStart}"
                    println "            End min/max: ${minEnd} / ${maxEnd}"

                    println "          Start Times:"
                    println "            Min:    ${minStart} (${convertEpochToDate(minStart)})"
                    println "            Middle: ${middleStart} (${convertEpochToDate(middleStart)})"
                    println "            Max:    ${maxStart} (${convertEpochToDate(maxStart)})"
                    println "          End Times:"
                    println "            Min:    ${minEnd} (${convertEpochToDate(minEnd)})"
                    println "            Middle: ${middleEnd} (${convertEpochToDate(middleEnd)})"
                    println "            Max:    ${maxEnd} (${convertEpochToDate(maxEnd)})"
                    println ""

                    allStartEpochs.addAll(startEpochs)
                    allEndEpochs.addAll(endEpochs)
                } else {
                    println "          No time data available"
                    println ""
                }

            } catch (Exception ex) {
                printf "%9d | %5s | %-7s | ERROR: %s%n", i, "ERROR", "Corrupt", ex.message
                println ""
            }
        } else {
            // Stop at first empty partition (they should be sequential)
            break
        }
    }

    println "----------|-------|---------|-------------"
    println "Summary:"
    println "  - Active Partitions: ${activePartitions}"
    println "  - Total Alerts: ${totalAlerts}"
    println "  - Average per Partition: ${activePartitions > 0 ? Math.round(totalAlerts / activePartitions) : 0}"
    println ""

    // Overall alert time range analysis
    if (allStartEpochs.size() > 0 && allEndEpochs.size() > 0) {
        println "Overall Alert Time Analysis:"

        // Start epochs
        def sortedStartEpochs = allStartEpochs.sort()
        def minStartEpoch = sortedStartEpochs.first()
        def maxStartEpoch = sortedStartEpochs.last()
        def middleStartEpoch = sortedStartEpochs[Math.floor(sortedStartEpochs.size() / 2) as int]

        // End epochs
        def sortedEndEpochs = allEndEpochs.sort()
        def minEndEpoch = sortedEndEpochs.first()
        def maxEndEpoch = sortedEndEpochs.last()
        def middleEndEpoch = sortedEndEpochs[Math.floor(sortedEndEpochs.size() / 2) as int]

        // Convert epoch seconds to milliseconds using Long arithmetic to avoid overflow
        def convertEpochToDate = { epoch ->
            return new Date((Long.valueOf(epoch)) * 1000L)
        }

        println "  Start Epochs:"
        println "    - Min:    ${minStartEpoch} (${convertEpochToDate(minStartEpoch)})"
        println "    - Middle: ${middleStartEpoch} (${convertEpochToDate(middleStartEpoch)})"
        println "    - Max:    ${maxStartEpoch} (${convertEpochToDate(maxStartEpoch)})"
        println ""
        println "  End Epochs:"
        println "    - Min:    ${minEndEpoch} (${convertEpochToDate(minEndEpoch)})"
        println "    - Middle: ${middleEndEpoch} (${convertEpochToDate(middleEndEpoch)})"
        println "    - Max:    ${maxEndEpoch} (${convertEpochToDate(maxEndEpoch)})"
        println ""

        // Time span analysis
        def totalTimeSpan = maxEndEpoch - minStartEpoch
        def daysSpan = totalTimeSpan / 86400  // seconds to days
        println "  Time Span Analysis:"
        println "    - Total Span: ${totalTimeSpan} seconds (${String.format('%.1f', daysSpan)} days)"
        println "    - Oldest Alert Started: ${convertEpochToDate(minStartEpoch)}"
        println "    - Newest Alert Ended: ${convertEpochToDate(maxEndEpoch)}"
        println ""
    } else {
        println "No alert time data available"
        println ""
    }

    // Check other caches
    println "Other Caches:"

    // Device groups
    String deviceGroupCache = scriptCache.get("DeviceAlertStats-deviceGroupCache")
    String deviceGroupExp = scriptCache.get("DeviceAlertStats-deviceGroupCache-Expiration")
    if (deviceGroupCache) {
        def deviceGroups = new JsonSlurper().parseText(deviceGroupCache)
        def expDate = deviceGroupExp ? new Date(Long.valueOf(deviceGroupExp) * 1000) : "Unknown"
        println "  - Device Groups: ${deviceGroups.size()} groups, expires: ${expDate}"
    } else {
        println "  - Device Groups: Not cached"
    }

    // Resources
    String resourceCache = scriptCache.get("DeviceAlertStats-ResourceCache")
    String resourceExp = scriptCache.get("DeviceAlertStats-ResourceCache-Expiration")
    if (resourceCache) {
        def resources = new JsonSlurper().parseText(resourceCache)
        def expDate = resourceExp ? new Date(Long.valueOf(resourceExp) * 1000) : "Unknown"
        println "  - Resources: ${resources.size()} devices, expires: ${expDate}"
    } else {
        println "  - Resources: Not cached"
    }

    println ""
    println "=== End Inspection ==="

} catch (Exception ex) {
    println "ERROR: ${ex.message}"
    ex.printStackTrace()
}

// Helper method for formatted printing (since printf might not be available)
def printf(format, ...args) {
    println String.format(format, args)
}
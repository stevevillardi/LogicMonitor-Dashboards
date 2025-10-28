/*******************************************************************************
 * © 2007-2023 - LogicMonitor, Inc. All rights reserved.
 ******************************************************************************/
import groovy.json.*

def devices

Map deviceType = [
    0: "Standard Resources",
    2: "AWS Resources",
    4: "Azure Resources",
    6: "Services",
    7: "GCP Resources",
    8: "K8s Resources",
    10: "SaaS Resources",
    11: "Synthetics"
]

try {
    scriptCache = this.class.classLoader.loadClass("com.santaba.agent.util.script.ScriptCache").getCache();
    //scriptCache.remove("DeviceAlertStats-ResourceCache")
    String resourceCache = scriptCache.get("DeviceAlertStats-ResourceCache");
    if(resourceCache == null){
        return -1
    }
    else{
        devices = new JsonSlurper().parseText(resourceCache)
    }
} 
catch (Exception ex) {
	println ex
    return -1
}

if(devices){
    devices.each { device -> 
        
        println "${device.id}##${device.displayName}######auto.device.type=${deviceType.getOrDefault(device.deviceType,"Other Resources")}"
    }
}

return 0
/*******************************************************************************
 * © 2007-2023 - LogicMonitor, Inc. All rights reserved.
 ******************************************************************************/
import groovy.json.*

def groups

try {
    scriptCache = this.class.classLoader.loadClass("com.santaba.agent.util.script.ScriptCache").getCache();
    //scriptCache.remove("DeviceAlertStats-deviceGroupCache")
    String deviceGroupCache = scriptCache.get("DeviceAlertStats-deviceGroupCache");
    if(deviceGroupCache == null){
        return -1
    }
    else{
        groups = new JsonSlurper().parseText(deviceGroupCache)
    }
} 
catch (Exception ex) {
	println ex
    return -1
}

if(groups){
    groups.each { group -> 
        
        println "${group.id}##${group.name}##${group.fullPath}####"
    }
}
return 0

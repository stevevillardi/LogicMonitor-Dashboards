/*******************************************************************************
 * © 2007-2024 - LogicMonitor, Inc. All rights reserved.
 ******************************************************************************/
import com.logicmonitor.common.sse.utils.GroovyScriptHelper
import com.logicmonitor.mod.Snippets
import groovy.json.JsonOutput

def debug = false
def log = false     //to find items in log use command - grep "VMware_vSphere_ESN" wrapper.log

def props, host, displayname
try
{
    host = hostProps.get('system.hostname')
    props = hostProps
    displayname = props.get('system.displayname')
    debug = true // Set debug to true to ensure we do not output sensitive properties
}
catch (Exception e)
{
    props = netscanProps
    host = props.get('vcenter.hostname')
    displayname = props.get('vcenter.displayname') ?: host
}
def user = props.get('vcenter.user') ?: props.get('vcsa.user') ?: props.get('esx.user')
def pass = props.get('vcenter.pass') ?: props.get('vcsa.pass') ?: props.get('esx.pass')
def addr = props.get('vcenter.url') ?: props.get('vcsa.url') ?: props.get('esx.url') ?: "https://${host}/sdk"
if (!user || !pass || !addr || !host || !displayname)
{
    throw new Exception("Required parameters are missing.  vcenter.user, vcenter.pass, vcenter.hostname  and vcenter.display name are required")
}
def hostnameSource = props.get("hostname.source")?.toLowerCase()
Boolean skipDeviceDedupe = props.get("skip.device.dedupe", "false").toBoolean()
def esxUser = props.get('esx.user')
def esxPass = props.get('esx.pass')
int eTimeout = 3
def rootFolder = props.get('rootFolder') ?: "vCenterResources"
def includeESXiHosts = (props.get('discover.esx') ?: true).toBoolean()
def includeVMs = (props.get('discover.vm') ?: true).toBoolean()
def includeHostsAndClustersView = (props.get('view.hostandcluster') ?: true).toBoolean()
def includeVMsAndTemplatesView = (props.get('view.vmsandtemplates') ?: true).toBoolean()
def includeStandaloneVM = (props.get('view.standaloneVm') ?: true).toBoolean()
def applyVCenterCredentialsToESXHost = (props.get('esx.vcentercredentials') ?: false).toBoolean()
modLoader = GroovyScriptHelper.getInstance()._getScript('Snippets', Snippets.getLoader()).withBinding(getBinding())
def emit = modLoader.load("lm.emit", "1.1")
def debugSnip = modLoader.load("lm.debug", "1.0")
def lmvSphere = modLoader.load("vmware.vsphere", "1.0")
def httpSnippet = modLoader.load("proto.http", "0")
def lmDebug = debugSnip.debugSnippetFactory(out, debug, log, "VMware_vSphere_Enhanced_Netscan")
def vSphere = lmvSphere.vSphereWebServicesAPIFactory(addr, user, pass, lmDebug)
def http = httpSnippet.httpSnippetFactory(props)
def lmApiSnippet = modLoader.load("lm.api", "0")
def lmApi = lmApiSnippet.lmApiSnippetFactory(props, http, lmDebug)
def maxESXiLoginFailure = (props.get('esx.maxloginfailures', '10').trim()).toInteger()
int ttlESXiLoginFailure = 0

List fields = ["name", "currentCollectorId", "displayName"]
Map args = ["size": 1000, "fields": fields.join(",")]
def lmDevices, pathFlag, portalInfo, timeLimitSec, timeLimitMs
if (!skipDeviceDedupe)
{
    portalInfo = lmApi.apiCallInfo("Devices", args)
    timeLimitSec = props.get("lmapi.timelimit.sec", "60").toInteger()
    timeLimitMs = (timeLimitSec) ? Math.min(Math.max(timeLimitSec, 30), 120) * 1000 : 60000
    if (portalInfo.timeEstimateMs > timeLimitMs)
    {
        lmDebug.LMDebug("INFO: Estimate indicates LM API calls would take longer than time limit configured.  Proceeding with individual queries by display name for each device to add.")
        lmDebug.LMDebug("\t${portalInfo}\n\tNOTE:  Time limit is set to ${timeLimitSec} seconds.  Adjust this limit by setting the property lmapi.timelimit.sec.  Max 120 seconds, min 30 seconds.")
        pathFlag = "ind"
    }
    else
    {
        lmDebug.LMDebug("INFO: Response time indicates LM API calls will complete in a reasonable time range.  Proceeding to collect info on all devices to cross reference and prevent duplicate device creation.\n\t${portalInfo}")
        pathFlag = "all"
        lmDevices = lmApi.getPortalDevices(args)
    }
}
def now = new Date()
def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.s z"
TimeZone tz = TimeZone.getDefault()

Map duplicateResources = [
"date"     : now.format(dateFormat, tz),
"message"  : "Duplicate display names found within LogicMonitor portal wherein hostname in LM does not match hostname in Netscan output.  Refer to documentation for how to resolve name collisions using 'hostname.source' netscan property.",
"total"    : 0,
"resources": []
]

List resources = []
Map ilpCredentials = [:]
Map tags = [:]
// Determine the vSphere host that we are making API queries to
Map productType = ['vpx': 0, 'embeddedEsx': 1]
def productId = productType[vSphere.getAbout().productLineId]

switch (productId)
{
    case 0: // vCenter
        ilpCredentials['vcenter.user'] = user
        ilpCredentials['vcenter.pass'] = pass
        ilpCredentials['vcenter.addr'] = addr

        tags = lmvSphere.vSphereAutomationAPIFactory(host, user, pass, lmDebug)
        .withCloseable() { it.getTagMap() }
        .collectEntries { k, v -> [(k): v.values] }

        lmDebug.LMDebugPrint("INFO: vCenter tags found: ${tags}")
        break
    case 1: // ESXi
        ilpCredentials['esx.user'] = esxUser ?: user
        ilpCredentials['esx.pass'] = esxPass ?: pass
        ilpCredentials['esx.addr'] = addr
        break
        // gsx(VMware_Server) and esx(VMware_ESX) are not supported
}

def vms = vSphere.getMOs('VirtualMachine').each { vSphere.updateEntityLineage(it) }
def hosts = vSphere.getMOs('HostSystem').each { vSphere.updateEntityLineage(it) }

tagPropPrefix = 'VMware.vCenter.tag'
Closure tagFormatter = { (it) ? it.collectEntries { _cat, _tag -> ["${tagPropPrefix}.${_cat}", _tag.join(',')] } : [:] }

def foundHost = null
// Discover each VM
vms.each { _vm ->
    Map device = [:]
    foundHost = (foundHost ?: (vSphere.isVmTheHost(_vm) ? _vm : null))
    if (!includeVMs && foundHost != _vm)
    {
        return
    }

    def MOR = _vm.MOR
    if (!MOR)
    {
        lmDebug.LMDebug("ERROR: MOR not found for ${_vm}, skipping")
        return
    }

    def deviceGroups = []
    def compLine = vSphere.getComputeLineage(MOR?.val)
    def vmLine = vSphere.getVmLineage(MOR?.val)
    def compFolder = (includeHostsAndClustersView) ? folderFormatter(compLine, rootFolder, includeStandaloneVM) : null
    def vmFolder = (includeVMsAndTemplatesView) ? folderFormatter(vmLine, rootFolder, includeStandaloneVM) : null

    if (foundHost == _vm)
    {
        // Rely on the vmFolder here. If customer is not including standalone VMs, but the vCenter is a standalone VM, discover it anyway
        def baseFolder = (vmFolder ?: folderFormatter(vmLine, rootFolder, includeStandaloneVM))?.split('/')[0]
        deviceGroups << baseFolder
    }

    if (includeVMs)
    {
        deviceGroups << compFolder << vmFolder
    }
    deviceGroups = deviceGroups.minus(null)

    if (!deviceGroups)
    {
        lmDebug.LMDebug("DEBUG: VM ${_vm.name} skipped due to no containing folder determined. This could be due to different filters being set.")
        return
    }

    def hostname = _vm.name
    lmDebug.LMDebug("\t\tINFO: hostname for VM ${_vm.name} set to $hostname")

    /***** add all properties to the device *****/
    if (foundHost == _vm)
    { // This is the vCenter VM
        device = [
        'hostname'   : "${host}",
        'displayname': "${displayname}",
        'hostProps'  : [
        'system.categories': 'VMware_vCenter,VMware_VM',
        'vCenter.vm.name'  : _vm.name
        ],
        'groupName'  : deviceGroups
        ]
        device.hostProps += tagFormatter(tags[vSphere.rootFolder.MOR.value])
    }
    else
    {
        device = [
        'hostname'   : hostname,
        'displayname': _vm.name,
        'hostProps'  : [
        'system.categories': 'VMware_VM',
        'vCenter.vm.name'  : _vm.name
        ],
        'groupName'  : deviceGroups
        ]
    }

    // If the netscan is running on a vCenter device
    if (productId == 0)
    {
        // Add vCenter tags
        device.hostProps += tagFormatter(tags[MOR?.value as String])

        // Add all parent entities as device properties
        def lineage = lineageParser(compLine + vmLine)
        lineage.each { k, v -> device.hostProps?."vcenter.${k.toLowerCase()}" = v.join(',') }
    }

    duplicateCheckAndAdd(device, resources, duplicateResources, lmApi, pathFlag, hostnameSource, skipDeviceDedupe, lmDevices, args, lmDebug)
}
if (includeESXiHosts)
{
    hosts.each { _host ->
        Map device = [:]

        def MOR = _host.MOR
        if (!MOR)
        {
            lmDebug.LMDebug("ERROR: MOR not found for ${_host}, skipping")
            return
        }

        /****** Device group set ******/
        def deviceGroups = []
        def compLine = vSphere.getComputeLineage(_host.MOR?.val)
        def compFolder = folderFormatter(compLine, rootFolder, includeStandaloneVM)
        deviceGroups << "${compFolder}/ESXi hosts"
        deviceGroups = deviceGroups.minus(null)


        def vSphere1 = null
        def addr1 = "https://${_host.name}/sdk"
        def user1 = esxUser ?: user
        def pass1 = esxPass ?: pass

        lmDebug.LMDebug("INFO: Attempting to login to the ESX host ${_host} (${_host.name}) with user $user1")

        try
        {
            if (ttlESXiLoginFailure > maxESXiLoginFailure)
            {
                vSphere1 = lmvSphere.vSphereWebServicesAPIFactory(addr1, user1, pass1, eTimeout, eTimeout, lmDebug)
            }
            else
            {
                vSphere1 = null
            }
        }
        catch (Exception e)
        {
            lmDebug.LMDebug("\tDEBUG: Unable to login to ESXi host ${_host} at ${addr1}, skipping...")
            lmDebug.LMDebug("\t\tERROR: $e")
            ttlESXiLoginFailure++
        }
        if (!vSphere1)
        {
            lmDebug.LMDebug("DEBUG: Unable to connect to ESXi host ${_host.name} with the provided credentials")

            // We were unable to connect to the ESXi host directly, just add it as a standard device
            device = [
            'hostname'   : _host.name,
            'displayname': _host.name,
            'hostProps'  : ['esx.netscan.status': 'Netscan did not connect to ESX host.'], // Do not set the system category 'VMware_ESXi' since we can't actually use this as an ESXi device
            'groupName'  : deviceGroups
            ]
        }
        else
        {
            lmDebug.LMDebug("INFO: Successful connection to ESXi host ${_host.name}")
            def esxHost = vSphere1.getMOs('HostSystem')[0]
            device = [
            'hostname'   : esxHost.name,
            'displayname': esxHost.name,
            'hostProps'  : [
            'esx.user'         : user1,
            'esx.pass'         : pass1,
            'esx.addr'         : addr1,
            'system.categories': 'VMware_ESXi'
            ],
            'groupName'  : deviceGroups
            ]
        }

        device.hostProps.'vcenter.hostname' = host
        if (applyVCenterCredentialsToESXHost)
        {
            device.hostProps += ilpCredentials
        }

        // If the netscan is running on a vCenter device
        if (productId == 0)
        {
            // Add vCenter tags
            device.hostProps += tagFormatter(tags[MOR?.value as String])

            // Add all parent entities as device properties
            def lineage = lineageParser(compLine)
            lineage.each { k, v -> device.hostProps?."vcenter.${k.toLowerCase()}" = v.join(',') }
        }

        duplicateCheckAndAdd(device, resources, duplicateResources, lmApi, pathFlag, hostnameSource, skipDeviceDedupe, lmDevices, args, lmDebug)
    }
}

if (duplicateResources["resources"].size() > 0)
{
    def netscanDupLog = new File("../logs/NetscanDuplicates/${rootFolder.replaceAll(" ", "_")}.json")
    new File(netscanDupLog.getParent()).mkdirs()
    duplicateResources["total"] = duplicateResources["resources"].size()
    def json = JsonOutput.prettyPrint(JsonOutput.toJson(duplicateResources))
    netscanDupLog.write(json)
    if (hostnameSource)
    {
        lmDebug.LMDebug("${duplicateResources["resources"].size()} devices found that were resolved with hostname.source=${hostnameSource} in netscan output.  See LogicMonitor/Agent/logs/NetscanDuplicates/${rootFolder.replaceAll(" ", "_")}.json for details.")
    }
    else
    {
        lmDebug.LMDebug("${duplicateResources["resources"].size()} devices found that were not reported in netscan output.  See LogicMonitor/Agent/logs/NetscanDuplicates/${rootFolder.replaceAll(" ", "_")}.json for details.")
    }
}
emit.resource(resources, debug)
return 0

String folderFormatter(List lineage, String rootFolder = '', includeStandaloneVM)
{
    if (lineage.size() > 1)
    {
        def folder = lineage.collect { Map folderMap ->
            switch (folderMap.type.toUpperCase())
            {
                case 'FOLDER':
                    if (folderMap.parent[0] == null)
                    { // This is the parent folder
                        return ((rootFolder) ?: "VMware - ${folderMap.name}")
                    }
                    else if (folderMap.name == 'vm')
                    {
                        return 'VMs' // VMs and templates folder
                    }
                    else
                    {
                        return folderMap.name
                    }
                    break
                case 'DATACENTER':
                    return "Datacenter - ${folderMap.name}"
                    break
                case 'CLUSTERCOMPUTERESOURCE':
                    return "Cluster - ${folderMap.name}"
                    break
                case 'RESOURCEPOOL':
                    return "Resource Pool - ${folderMap.name}"
                    break
                case 'COMPUTERESOURCE':
                    return 'Standalone ESXi hosts'
                default:
                    return folderMap.name
            }
            // Don't put the device in a folder named after it
        }[0..-2]

        if (lineage.size() >= 2)
        {
            // Check for standalone VMs that are not in a resourcePool, and put them in their own standalone folder
            if (lineage[-1].type == 'VIRTUALMACHINE' && (lineage[-2].type != 'RESOURCEPOOL' && lineage[-2].type != 'FOLDER'))
            {
                if (includeStandaloneVM)
                {
                    folder << 'Standalone VMs'
                }
                else
                {
                    // This is a standalone, and if we don't want to include it, return nothing
                    return null
                }
            }
        }
        return folder.join('/')
    }
    return null
}

Map lineageParser(List lineage)
{
    Map out = [:].withDefault { [] }

    lineage.each { out[it?.type] << it?.name }
    out.collectEntries { k, v ->
        v.unique()
        switch (k.toUpperCase())
        {
            case 'CLUSTERCOMPUTERESOURCE':
                k = 'CLUSTER'
                break
            case 'FOLDER':
                v.remove('vm') // Autogenerated folders
                v.remove('Datacenters')
                break
        }
        _out = [k, v]
    }
}

void duplicateCheckAndAdd(Map resource, resources, Map duplicateResources, def lmApi, def pathFlag, def hostnameSource, def skipDeviceDedupe, def lmDevices, def args, def lmDebug)
{
    if (skipDeviceDedupe)
    {
        resources.add(resource)
        return
    }

    // Check for existing resource in LM portal with this displayname
    String displayname = resource.displayname
    Integer collectorId
    def deviceMatch = null
    if (pathFlag == "ind")
    {
        deviceMatch = lmApi.findPortalDevice(displayname, args)
    }
    else if (pathFlag == "all")
    {
        deviceMatch = lmApi.checkExistingDevices(displayname, lmDevices)
    }
    lmDebug.LMDebug("Check for device matches: $deviceMatch")
    if (deviceMatch)
    {
        if (resource.hostname != deviceMatch.name)
        {
            def collisionInfo = [
            (resource.displayname): [
            "Netscan" : ["hostname": resource.hostname],
            "LM"      : [
            "hostname"   : deviceMatch.name,
            "collectorId": deviceMatch.currentCollectorId],
            "Resolved": false
            ]
            ]
            if (hostnameSource == "lm" || hostnameSource == "logicmonitor")
            {
                collisionInfo[displayname]["Resolved"] = true
                resource.hostname = deviceMatch.name
                collectorId = deviceMatch.currentCollectorId
                deviceMatch = false
            }
            else if (hostnameSource == "netscan")
            {
                collisionInfo[displayname]["Resolved"] = true
                resource.displayname = "${resource.displayname} - ${resource.hostname}"
                deviceMatch = false
            }
            else
            {
                lmDebug.LMDebug("ERROR: Could not find valid value for device property 'hostname.source'.")
                lmDebug.LMDebug("\t INFO: Value returned $hostnameSource, valid values are 'lm' or 'netscan'")
            }

            duplicateResources["resources"].add(collisionInfo)
        }
        else
        {
            deviceMatch = false
        }
    }

    if (collectorId)
    {
        resource.collectorId = collectorId
        duplicateResources["resources"][displayname]["Netscan"][0]["collectorId"] = collectorId
    }

    if (!deviceMatch)
    {
        resources.add(resource)
    }
}
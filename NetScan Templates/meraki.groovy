/*******************************************************************************
 * © 2007-2023 - LogicMonitor, Inc. All rights reserved.
 ******************************************************************************/
 
import com.logicmonitor.common.sse.utils.GroovyScriptHelper
import com.logicmonitor.mod.Snippets
import com.santaba.agent.AgentVersion
import java.text.DecimalFormat
import com.vmware.vim25.InvalidLoginFaultMsg
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
 
def debug = false
def log = false
 
// Bail out early if we don't have the correct minimum collector version to ensure netscan runs properly
if (AgentVersion.AGENT_VERSION.toInteger() < 32400) {
    throw new Exception("Upgrade collector running netscan to 32.400 or higher to run full featured enhanced netscan. Currently running version ${new DecimalFormat('00.000').format(collectorVersion / 1000)}.")
}
 
// Set props object based on whether or not we are running inside a netscan or debug console
def props
// Required properties
def host, displayName
try {
    host = hostProps.get('system.hostname') // This is the vCenter
    props = hostProps
    displayName = props.get('system.displayname')
    debug = true  // Set debug to true to ensure we do not output sensitive properties
}
catch (MissingPropertyException) {
    props = netscanProps
    host = props.get('vcenter.hostname')
    displayName = props.get('vcenter.displayname') ?: host
}
 
// Required properties
def user = props.get('vcenter.user') ?: props.get('vcsa.user') ?: props.get('esx.user')
def pass = props.get('vcenter.pass') ?: props.get('vcsa.pass') ?: props.get('esx.pass')
def addr = props.get('vcenter.url')  ?: props.get('vcsa.url')  ?: props.get('esx.url') ?: "https://${host}/sdk"
 
// Optional properties
def eUser = props.get('esx.user') // Additional credentials for a standalone host
def ePass = props.get('esx.pass')
 
def rootFolder = props.get('rootFolder') // Root folder can be nested, i.e. 'site1/subfolder1'
 
// Default to true
def includeVMs                  = (props.get('discover.vm')                                                   ?: true).toBoolean() // Toggle if we want to discover VMs, true = discover
def includeESXiHosts            = (props.get('discover.esxi',        props.get('filter.esxiHosts'))           ?: true).toBoolean() // Toggle if we want to discover ESXi hosts, true = discover
def includeHostsAndClustersView = (props.get('view.hostandcluster',  props.get('filter.hostandclusterview'))  ?: true).toBoolean() // Toggle for if we want to create the Host and Cluster view, true = create
def includeVMsAndTemplatesView  = (props.get('view.vmsandtemplates', props.get('filter.vmsandtemplatesview')) ?: true).toBoolean() // Toggle for if we want to create the VM and templates view (only VMs will be discovered), true = create
def includeStandaloneVM         = (props.get('view.standaloneVm',    props.get('filter.standaloneVm'))        ?: true).toBoolean() // Toggle for if we want to create the standalone VM folder, true = create
binding.setProperty('includeStandaloneVM', includeStandaloneVM) // Make it global
 
// Default to false
def applyVCenterCredentialsToESXHost = (props.get('esx.vcentercredentials') ?: false).toBoolean() // Toggle for if we want standalone ESXi hosts to also get vcenter.user/pass credentials. Note the ESXi modules only care about the ESX credentials.
 
 
modLoader = GroovyScriptHelper.getInstance()._getScript('Snippets', Snippets.getLoader()).withBinding(getBinding())
def emit      = modLoader.load("lm.emit", "1.1")
def debugSnip = modLoader.load("lm.debug", "1.0")
def lmDebug   = debugSnip.debugSnippetFactory(out, debug, log, "VMware_vSphere_ESN")
def lmvSphere = modLoader.load("vmware.vsphere", "1.0")
def vSphere   = lmvSphere.vSphereWebServicesAPIFactory(addr, user, pass, lmDebug)
 
String cacheFilename = "VMware_${host}_devices"
Map sensitiveProps = [
    "vcenter.pass" : pass,
    "vcsa.pass"    : pass,
    "esx.pass"     : ePass,
]
 
// Determine whether there are devices cached on disk that still need to be added from previous netscan runs
if (processResourcesJson(emit, cacheFilename, sensitiveProps, lmDebug)) return 0
 
List<Map> resources = []
Map<String, String> ilpCredentials = [:]
Map tags
// Determine the vSphere host that we are making API queries to
Map<String, Integer> productType = ['vpx' :   0, 'embeddedEsx' : 1]
def productId = productType[vSphere.getAbout().productLineId]
switch (productId) {
    case 0: // vCenter
        ilpCredentials['vcenter.user'] = user
        ilpCredentials['vcenter.pass'] = pass
        ilpCredentials['vcenter.addr'] = addr
        tags = lmvSphere.vSphereAutomationAPIFactory(host, user, pass).withCloseable(){ it.getTagMap() } // Get all vCenter tags
        lmDebug.LMDebugPrint( "DEBUG: vCenter tags found: ${tags}" )
        break
    case 1: // ESXi
        ilpCredentials['esx.user'] = user
        ilpCredentials['esx.pass'] = pass
        ilpCredentials['esx.addr'] = addr
        break
        // gsx(VMware_Server) and esx(VMware_ESX) are not supported
}
 
def vms = vSphere.getMOs('VirtualMachine').each{ vSphere.updateEntityLineage(it) }
def hosts = vSphere.getMOs('HostSystem').each{ vSphere.updateEntityLineage(it) }
// Discover each VM
if (includeVMs) {
    vms.each { _vm ->
        Map device = [:]
 
        def MOR = _vm.MOR
        if (!MOR) {
            lmDebug.LMDebugPrint("DEBUG: MOR not found for ${_vm}, skipping")
            return
        }
 
        def compLine = vSphere.getComputeLineage(MOR?.val)
        def vmLine = vSphere.getVmLineage(MOR?.val)
        def compFolder = (includeHostsAndClustersView) ? folderFormatter(compLine, rootFolder) : null
        def vmFolder   = (includeVMsAndTemplatesView)  ? folderFormatter(vmLine,   rootFolder) : null
 
        if (!compFolder && !vmFolder) {
            lmDebug.LMDebugPrint("DEBUG: VM ${_vm.name} skipped due to no containing folder determined. This could be due to different filters being set.")
            return
        }
 
        def guest = _vm.guest
        def ips = []
 
        guest?.net?.eachWithIndex { nic, number -> ips << nic?.ipAddress }
        def hostname = ips.flatten().findAll { it.contains('.') }
 
        def foundDNS = true
        if (!hostname) { // Couldn't find any IPs
            lmDebug.LMDebugPrint("No IPs could not be found for VM: ${_vm.name}")
            if (guest?.hostName) {
                hostname[0] = guest?.hostName
            } else {
                hostname[0] = _vm.name
                foundDNS = false
            }
        }
 
        if (productId == 0 && hostname?.contains(host)) { // This is the vCenter VM
            def baseFolder = (vmFolder ?: folderFormatter(vmLine, rootFolder))?.split('/')[0]
            // Rely on the vmFolder here. If customer is not including standalone VMs, but the vCenter is a standalone VM, discover it anyway
            device = [
                    'hostname'   : "${host}",
                    'displayname': "${displayName}",
                    'hostProps'  : [
                            'system.categories': 'VMware_vCenter,VMware_VM'
                    ],
                    'groupName'  : [baseFolder, compFolder, vmFolder].minus(null)
            ]
        } else {
            device = [
                    'hostname'   : hostname[0],
                    'displayname': _vm.name,
                    'hostProps'  : [
                            'system.categories': 'VMware_VM'
                    ],
                    'groupName'  : [compFolder, vmFolder].minus(null)
            ]
        }
 
        // If the netscan is running on a vCenter device
        if (productId == 0) {
            // Add vCenter tags
            def tagP = tags[MOR?.value as String]?.values.collect { k, v ->
                v.collect { "${k}.${it}" }
            }.flatten().join(",")
            device.hostProps.'vcenter.tags' = tagP
 
            // Add all parent entities as device properties
            def lineage = lineageParser(compLine + vmLine)
            lineage.each { k, v -> device.hostProps?."vcenter.${k.toLowerCase()}" = v.join(',') }
        }
 
        device.hostProps.'netscan.powerstate' = _vm.runtime.powerState // Add power state for filtering
        device.hostProps.'netscan.foundDNS' = foundDNS as String
        device.hostProps += ilpCredentials
 
        resources.add(device)
    }
}
 
if (includeESXiHosts) {
    hosts.each{ _host ->
        Map device = [:]
 
        def MOR = _host.MOR
        if (!MOR) {
            lmDebug.LMDebugPrint( "DEBUG: MOR not found for ${_host}, skipping" )
            return
        }
 
        def compLine = vSphere.getComputeLineage(_host.MOR?.val)
        def compFolder = folderFormatter(compLine, rootFolder)
 
        def vSphere1
        lmDebug.LMDebugPrint( "DEBUG: Attempting to login to the ESX host ${_host}" )
 
        def addr1 = "https://${_host.name}/sdk"
        def user1 = eUser ?: user
        def pass1 = ePass ?: pass
        try {
            vSphere1 = lmvSphere.vSphereWebServicesAPIFactory(addr1, user1, pass1, 10, 10)
        } catch (InvalidLoginFaultMsg e) {
            lmDebug.LMDebugPrint( "DEBUG: Unable to login to ESXi host ${_host} at ${addr1}, skipping..." )
        }
        if (!vSphere1) {
            lmDebug.LMDebugPrint( "DEBUG: Unable to connect to ESXi host ${_host.name} with the provided credentials" )
 
            // We were unable to connect to the ESXi host directly, just add it as a standard device
            device = [
                    'hostname'   : _host.name,
                    'displayname': _host.name,
                    'hostProps'  : [:], // Do not set the system category 'VMware_ESXi' since we can't actually use this as an ESXi device
                    'groupName'  : ["${compFolder}/ESXi hosts"].minus(null)
            ]
        } else {
            lmDebug.LMDebugPrint( "DEBUG: Successful connection to ESXi host ${_host.name}" )
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
                    'groupName'  : ["${compFolder}/ESXi hosts"].minus(null)
            ]
        }
 
        device.hostProps.'vcenter.hostname' = host
        if (applyVCenterCredentialsToESXHost) { device.hostProps += ilpCredentials }
 
        // If the netscan is running on a vCenter device
        if (productId == 0) {
            // Add vCenter tags
            def tagP = tags[MOR?.value as String]?.values.collect{ k, v ->
                v.collect{ "${k}.${it}" }
            }.flatten().join(",")
            device.hostProps.'vcenter.tags' = tagP
 
            // Add all parent entities as device properties
            def lineage = lineageParser(compLine)
            lineage.each{ k, v -> device.hostProps?."vcenter.${k.toLowerCase()}" = v.join(',') }
        }
 
        resources.add(device)
    }
}
 
emitWriteJsonResources(emit, cacheFilename, resources, lmDebug)
 
return 0
 
 
/**
 * Function for transforming the inventory view/device lineage -> formatted LM group structure
 *
 * @param lineage Lineage generated from vSphere.getComputeLineage/vSphere.getVMLineage functions
 * @param rootFolder optional property to set the root folder for the vSphere that all other folders will be nested in
 * @return String of the formatted folder for netscan groupName parameter
 */
String folderFormatter(List<Map> lineage, String rootFolder = '') {
    if (lineage.size() > 1) {
        def folder = lineage.collect{
            switch (it.type.toUpperCase()) {
                case 'FOLDER' :
                    if (it.parent[0] == null) { // This is the parent folder
                        return ((rootFolder) ?: "VMware - ${it.name}")
                    } else if (it.name == 'vm') {
                        return 'VMs' // VMs and templates folder
                    } else {
                        return it.name
                    }
                    break
                case 'DATACENTER' :
                    return "Datacenter - ${it.name}"
                    break
                case 'CLUSTERCOMPUTERESOURCE' :
                    return "Cluster - ${it.name}"
                    break
                case 'RESOURCEPOOL' :
                    return "Resource Pool - ${it.name}"
                    break
                case 'VIRTUALMACHINE' :
                case 'HOSTSYSTEM' :
                    // The devices we want to discover
                    return it.name
                    break
                case 'COMPUTERESOURCE' :
                    return 'Standalone ESXi hosts'
                default:
                    return it.name
            }
            // Don't put the device in a folder named after it
        }[0..-2]
 
        if (lineage.size() >= 2) {
            // Check for standalone VMs that are not in a resourcePool, and put them in their own standalone folder
            if ( lineage[-1].type == 'VIRTUALMACHINE' && (lineage[-2].type != 'RESOURCEPOOL' && lineage[-2].type != 'FOLDER')) {
                if (includeStandaloneVM) {
                    folder << 'Standalone VMs'
                } else {
                    // This is a standalone, and if we don't want to include it, return nothing
                    return null
                }
            }
        }
        return folder.join('/')
    } else {
        return
    }
}
 
 
/**
 * Determines which nested folders/datacenter/cluster/resourcepool/etc. that an object belongs to.
 * Ignores the auto-generated folders that all VMs exist in. These folders are not visible in the VM, but do exist in the backend
 *
 * @param lineage Lineage generated from vSphere.getComputeLineage/vSphere.getVMLineage functions
 * @return Map of the folders/datacenter/cluster/resourcepool/etc. that are provided from the lineage
 */
Map<String, List<String>> lineageParser(List<Map> lineage) {
    Map<String, List<String>> out = [:].withDefault{[]}
 
    lineage.each{ out[it?.type] << it?.name }
    out.collectEntries{ k, v ->
        v.unique()
        switch (k.toUpperCase()) {
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
 
 
/**
 * Sanitizes filepath and instantiates File object
 *
 * @param filename String
 * @param fileExtension String
 * @return File object using sanitized relative filepath
*/
File newFile(String filename, String fileExtension) {
    // Ensure relative filepath is complete with extension type
    def filepath
    if (!filename.startsWith("./")) {
        filepath = "./${filename}"
    }
    if (!filepath.endsWith(".${fileExtension}")) {
        filepath = "${filepath}.${fileExtension}"
    }
 
    return new File(filepath)
}
 
 
/**
 * Replaces cached props stored with bogus values with their correct values
 *
 * @param cachedProps Map of hostProps values stored in file cache
 * @param sensitiveProps Map of sensitive properties configured in the netscan to use for updating cachedProps values
 * @return completeHostProps Map updated hostProps with no bogus values
*/
Map processCachedHostProps(Map cachedProps, Map sensitiveProps) {
    Map completeHostProps = cachedProps.collectEntries{ k, v ->
                                if (sensitiveProps.containsKey(k)) {
                                    return [k as String, sensitiveProps[k]]
                                }
                                else {
                                    return [k as String, v as String]
                                }
                            }
    // Verify that we do not have any remaining properties with fake values; stop the show if we do
    def missingKeys = completeHostProps.findAll{ k,v -> v == "***" }
    if (missingKeys) {
        throw new Exception(" Unable to update all cached sensitive properties with appropriate values.  Check Netscan properties and ensure the following keys have been added with values other than ***:\n\t${missingKeys.keySet().join(",")}")
    }
    return completeHostProps
}
 
 
/**
 * Processes a JSON file representing resources cached to disk on the collector
 *
 * @param emit Snippet object for lm.emit (requires version 1.0)
 * @param filename String
 * @param sensitiveProps Map of sensitive properties configured in the netscan to use for updating cachedProps values
 * @param lmDebug Snippet object class instantiation of lm.debug (requires version 1.0)
 * @return Boolean indicator of whether processing was successful
*/
Boolean processResourcesJson(emit, String filename, Map sensitiveProps, lmDebug) {
    File cacheFile = newFile(filename, "json")
    def cachedDevices
    try {
        cachedDevices = new JsonSlurper().parse(cacheFile)
    }
    catch (JsonException) {
        lmDebug.LMDebugPrint("No file found under ${cacheFile.name}; proceeding with API calls to retrieve devices.\n")
        return false
    }
 
    if (!cachedDevices) {
        lmDebug.LMDebugPrint("No cached devices found in ${cacheFile.name}; proceeding with API calls to retrieve devices.\n")
        return false
    }
    lmDebug.LMDebugPrint("${cachedDevices.size()} devices retrieved from cache file ${cacheFile.name}")
 
    // Updated cached devices to include proper values for sensitive properties stored in cache
    cachedDevices.each{ device ->
        if (device["hostProps"]) device["hostProps"] = processCachedHostProps(device["hostProps"], sensitiveProps)
    }
 
    emitWriteJsonResources(emit, filename, cachedDevices, lmDebug)
    return true
}
 
 
/**
 * Output resources to stdout and cache any remainders to JSON file on collector disk
 *
 * @param emit Snippet object for lm.emit (requires version 1.0)
 * @param filename String
 * @param resources List<Map> resources to be added from netscan
 * @param lmDebug Snippet object class instantiation of lm.debug (requires version 1.0)
*/
def emitWriteJsonResources(emit, String filename, List<Map> resources, lmDebug) {
    def chunkSize = 600
    def chunk = Math.min(resources.size(), chunkSize)
    lmDebug.LMDebugPrint("Adding ${chunk} devices.")
    // Output resources in chunk size deemed safe by platform team
    emit.resource(resources[0..chunk-1], lmDebug.debug)
 
    File cacheFile = newFile(filename, "json")
    // If the number of resources is less than or equal to our chunk size, our batching is complete and we can delete the file and exit
    if (resources.size() <= chunk) {
        cacheFile.delete()
        lmDebug.LMDebugPrint("All known devices have been reported.")
        return
    }
    // Remove sensitive properties prior to storing data in cache file; hardcode to true to ensure props are masked regardless of debug mode
    def remainingResources = emit.sanitizeResourceSensitiveProperties(resources, true)
    remainingResources = remainingResources[chunk..-1]
    def jsonRR = JsonOutput.toJson(remainingResources)
    // println JsonOutput.prettyPrint(jsonRR) // Uncomment for debugging purposes if needed
 
    lmDebug.LMDebug("Caching ${remainingResources.size()} devices to disk to add to portal in upcoming netscan executions.")
    cacheFile.write(jsonRR)
    return
}
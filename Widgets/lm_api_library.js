/**
 * LogicMonitor API Library
 * A reusable library for making authenticated API calls to LogicMonitor's REST API
 * 
 * @version 1.0.0
 * @author Steve Villardi
 * 
 * FEATURES:
 * - Automatic CSRF token management
 * - Automatic pagination support
 * - ID resolution (use names instead of IDs)
 * - Comprehensive error handling
 * - Easy-to-use helper functions
 * 
 * INCLUDED FUNCTIONS:
 * 
 * Core:
 *   - LMClient() - Low-level API call function
 *   - fetchCsrfToken() - Get CSRF token
 * 
 * Utilities:
 *   - buildFilter() - Build filter strings
 *   - fetchAllPaginated() - Auto-paginate results
 * 
 * Devices:
 *   - getDevice() - Get single device by ID or name
 *   - getDevices() - Get multiple devices with filters
 * 
 * Datasources:
 *   - getDatasource() - Get datasource by ID or name
 *   - getDeviceDatasource() - Get device datasource (hdsId)
 *   - getDatasourceInstances() - Get instances for a datasource
 *   - getDatasourceInstanceData() - Get data for an instance
 * 
 * Alerts:
 *   - getAlerts() - Get alerts with flexible filters
 *   - getDeviceAlerts() - Get alerts for a device
 *   - getGroupAlerts() - Get alerts for a group
 * 
 * QUICK START:
 * 
 * // Get device by name
 * const device = await getDevice({ name: 'myserver.example.com' });
 * 
 * // Get critical alerts
 * const alerts = await getAlerts({
 *   filters: { severity: 'critical', cleared: 'false' },
 *   fetchAll: true
 * });
 * 
 * // Get CPU data
 * const data = await getDatasourceInstanceData({
 *   deviceName: 'myserver.example.com',
 *   datasourceName: 'CPU',
 *   instanceName: 'CPU-0',
 *   start: Date.now() - 3600000,
 *   end: Date.now(),
 *   datapoints: 'CPUBusyPercent'
 * });
 * 
 * For full documentation, see lm_api_library_README.md
 */

/**
 * Fetches a Cross-Site Request Forgery (CSRF) token required for subsequent API calls.
 *
 * This function makes a preliminary request to a dummy endpoint solely to retrieve
 * the CSRF token from the response headers.
 *
 * @async
 * @function fetchCsrfToken
 * @returns {Promise<string>} A promise that resolves with the CSRF token.
 * @throws {Error} If the fetch request fails or the token is not found in headers.
 */
async function fetchCsrfToken() {
	console.debug('Fetching CSRF token...');
	const response = await fetch('/santaba/rest/functions/dummy', {
		method: 'GET',
		headers: {
			'X-Csrf-Token': 'Fetch', // Specific header to request the token
			'Accept': 'application/json',
			'X-Version': '3', // Specify API version if required by this endpoint
		},
		credentials: 'include', // Include cookies for session management/CSRF
	});

	if (!response.ok) {
		throw new Error(`Failed to fetch CSRF token: ${response.status} ${response.statusText}`);
	}

	const token = response.headers.get('X-Csrf-Token');
	if (!token) {
		throw new Error('CSRF token not found in response headers.');
	}
	console.debug('CSRF Token fetched successfully.');
	return token;
}

/**
 * Performs an HTTP request to the LogicMonitor REST API.
 *
 * This function handles fetching a CSRF token, constructing the API request,
 * sending the request, and processing the response. It supports common HTTP verbs
 * and automatically includes necessary headers and credentials.
 *
 * @async
 * @function LMClient
 * @param {object} options - The options for the API request.
 * @param {string} options.resourcePath - The specific API resource path (e.g., /device/devices).
 * @param {string} [options.queryParams=''] - Optional query parameters string (e.g., ?filter=name:value).
 * @param {'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'} options.httpVerb - The HTTP method to use.
 * @param {object | Array<unknown>} [options.postBody] - The JSON payload for POST/PUT/PATCH requests.
 * @param {string} [options.apiVersion='3'] - The API version to use. Default is "3".
 * @returns {Promise<object>} A promise that resolves with the JSON response body on success.
 * @throws {Error} Throws an Error on API errors (>=300 status), network issues,
 *                 token fetching problems, or JSON handling errors. The error object
 *                 may contain 'status' and 'statusText' properties for API errors.
 */
async function LMClient({
	resourcePath,
	queryParams = '', // Default queryParams to empty string
	httpVerb,
	postBody,
	apiVersion = '3',
}) {
	console.debug('LMClient called with:', { resourcePath, queryParams, httpVerb, postBody, apiVersion });
	// Validate required parameters
	if (!resourcePath || !httpVerb) {
		throw new Error('Missing required parameters: resourcePath and httpVerb must be provided.');
	}
	const validVerbs = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'];
	if (!validVerbs.includes(httpVerb)) {
		throw new Error(`Invalid httpVerb: ${httpVerb}. Must be one of ${validVerbs.join(', ')}`);
	}


	console.debug(`Initiating LogicMonitor API call: ${httpVerb} ${resourcePath}${queryParams}`);

	try {
		// 1. Fetch the CSRF token
		const csrfToken = await fetchCsrfToken();

		// 2. Construct the API URL and request options
		const apiUrl = `/santaba/rest${resourcePath}${queryParams}`;
		const headers = {
			'Content-Type': 'application/json', // Consistently set Content-Type
			'Accept': 'application/json', // Expect JSON response
			'X-Csrf-Token': csrfToken,
			'X-Version': apiVersion, // Use the appropriate API version for the main request
		};

		const requestOptions = {
			method: httpVerb,
			headers: headers,
			credentials: 'include', // Necessary for session/cookie-based auth
		};

		// 3. Add body only for relevant methods
		if (postBody && (httpVerb === 'POST' || httpVerb === 'PUT' || httpVerb === 'PATCH')) {
			try {
				requestOptions.body = JSON.stringify(postBody);
				console.debug('Request body included:', postBody);
			} catch (stringifyError) {
				console.error('Failed to stringify postBody:', stringifyError);
				// Add user-friendly message to the error
				stringifyError.message = `Invalid postBody provided. Could not stringify to JSON. Original error: ${stringifyError.message}`;
				throw stringifyError;
			}
		}

		// 4. Make the API call
		console.debug(`Executing fetch to: ${apiUrl}`);
		const response = await fetch(apiUrl, requestOptions);
		console.debug(`Received response status: ${response.status} ${response.statusText}`);

		// 5. Process the response
		if (response.ok) { // ok is true for statuses 200-299
			// Handle potential empty response body for certain success statuses (e.g., 204 No Content)
			if (response.status === 204) {
				console.debug('Received 204 No Content response.');
				return {}; // Return an empty object for 204
			}
			try {
				// Assume response is JSON if status is ok and not 204
				const data = await response.json();
				console.debug('API call successful, response data received.'); // Avoid logging potentially sensitive data by default
				return data;
			} catch (jsonError) {
				console.error('Failed to parse JSON response:', jsonError);
				// Create a new error with more context
				const parseError = new Error(`Successfully received response (${response.status}), but failed to parse JSON body. Original error: ${jsonError.message}`);
				parseError.status = response.status; // Attach status for context
				parseError.statusText = response.statusText;
				throw parseError;
			}
		} else {
			// Handle API errors (status >= 300)
			const error = new Error(`API Error: ${response.status} ${response.statusText}`);
			error.status = response.status;
			error.statusText = response.statusText;

			// Attempt to get more details from the error response body
			try {
				const errorBody = await response.text(); // Use text first in case it's not JSON
				error.body = errorBody || 'No additional error details provided.'; // Attach body to error
				console.warn(`API Error Body: ${error.body}`); // Log the raw error body
			} catch (bodyError) {
				console.warn('Could not read error response body:', bodyError);
				error.body = 'Could not read error response body.';
			}
			console.error('LogicMonitor API Error:', { status: error.status, statusText: error.statusText });
			throw error; // Throw the augmented error object
		}
	} catch (error) {
		// Catch errors from fetchCsrfToken, fetch itself (network errors), or JSON parsing/stringifying
		console.error('An error occurred in LMClient:', error.message || error);

		// Re-throw the error to be handled by the caller.
		// Ensure it's always an Error object.
		if (error instanceof Error) {
			throw error;
		} else {
			// If it's not an Error object (e.g., the thrown API error object), wrap it
			const wrappedError = new Error(error.message || 'An unexpected error occurred during the API call.');
			// Copy relevant properties if they exist
			if (error && typeof error === 'object') {
				if ('status' in error) wrappedError.status = error.status;
				if ('statusText' in error) wrappedError.statusText = error.statusText;
				if ('body' in error) wrappedError.body = error.body;
			}
			throw wrappedError;
		}
	}
}

// ============================================================================
// UTILITY FUNCTIONS
// ============================================================================

/**
 * Builds a filter string for LogicMonitor API queries.
 * 
 * @function buildFilter
 * @param {object} filters - Object with filter key-value pairs
 * @returns {string} Formatted filter string for API query
 * 
 * @example
 * buildFilter({ name: 'mydevice', 'alertStatus': '*critical*' })
 * // Returns: 'name:mydevice,alertStatus:*critical*'
 */
function buildFilter(filters) {
	if (!filters || typeof filters !== 'object') {
		return '';
	}
	
	return Object.entries(filters)
		.filter(([key, value]) => value !== null && value !== undefined)
		.map(([key, value]) => `${key}:"${value}"`)
		.join(',');
}

/**
 * Fetches all items from a paginated API endpoint.
 * Automatically handles pagination to retrieve all results.
 * 
 * @async
 * @function fetchAllPaginated
 * @param {object} options - Options for the paginated fetch
 * @param {string} options.resourcePath - The API resource path
 * @param {string} [options.filter=''] - Filter string for the query
 * @param {string} [options.fields=''] - Comma-separated list of fields to return
 * @param {number} [options.size=1000] - Number of items per page (max 1000)
 * @returns {Promise<Array>} Array of all items from all pages
 * 
 * @example
 * const allDevices = await fetchAllPaginated({
 *   resourcePath: '/device/devices',
 *   filter: 'displayName~*server*'
 * });
 */
async function fetchAllPaginated({ resourcePath, filter = '', fields = '', size = 1000 }) {
	let allItems = [];
	let offset = 0;
	let totalItems = null;
	
	while (totalItems === null || offset < totalItems) {
		const queryParams = new URLSearchParams();
		queryParams.append('size', size);
		queryParams.append('offset', offset);
		if (filter) queryParams.append('filter', filter);
		if (fields) queryParams.append('fields', fields);
		
		const response = await LMClient({
			resourcePath: resourcePath,
			queryParams: `?${queryParams.toString()}`,
			httpVerb: 'GET'
		});
		
		if (totalItems === null) {
			totalItems = response.total || 0;
		}
		
		if (response.items && response.items.length > 0) {
			allItems = allItems.concat(response.items);
			offset += response.items.length;
		} else {
			break; // No more items
		}
	}
	
	return allItems;
}

// ============================================================================
// DEVICE FUNCTIONS
// ============================================================================

/**
 * Gets a device by ID or name.
 * 
 * @async
 * @function getDevice
 * @param {object} options - Options for device lookup
 * @param {number} [options.id] - Device ID
 * @param {string} [options.name] - Device name (displayName)
 * @param {string} [options.fields=''] - Comma-separated list of fields to return
 * @returns {Promise<object|null>} Device object or null if not found
 * @throws {Error} If neither id nor name is provided
 * 
 * @example
 * const device = await getDevice({ name: 'myserver.example.com' });
 * const device = await getDevice({ id: 123 });
 */
async function getDevice({ id, name, fields = '' }) {
	if (!id && !name) {
		throw new Error('Either id or name must be provided');
	}
	
	// If ID is provided, fetch directly
	if (id) {
		try {
			const queryParams = fields ? `?fields=${fields}` : '';
			const response = await LMClient({
				resourcePath: `/device/devices/${id}`,
				queryParams: queryParams,
				httpVerb: 'GET'
			});
			return response;
		} catch (error) {
			console.warn(`Device with ID ${id} not found:`, error.message);
			return null;
		}
	}
	
	// If name is provided, search for it
	const filter = `displayName:${name}`;
	const queryParams = new URLSearchParams();
	queryParams.append('filter', filter);
	queryParams.append('size', '1');
	if (fields) queryParams.append('fields', fields);
	
	const response = await LMClient({
		resourcePath: '/device/devices',
		queryParams: `?${queryParams.toString()}`,
		httpVerb: 'GET'
	});
	
	return response.items && response.items.length > 0 ? response.items[0] : null;
}

/**
 * Gets multiple devices based on filter criteria.
 * 
 * @async
 * @function getDevices
 * @param {object} options - Options for device search
 * @param {object} [options.filters={}] - Filter criteria (e.g., { displayName: '*server*', hostStatus: 'normal' })
 * @param {string} [options.fields=''] - Comma-separated list of fields to return
 * @param {boolean} [options.fetchAll=false] - If true, fetches all pages; if false, returns first page only
 * @param {number} [options.size=50] - Number of items per page (only used if fetchAll is false)
 * @returns {Promise<Array>} Array of device objects
 * 
 * @example
 * const devices = await getDevices({ 
 *   filters: { displayName: '*server*', hostStatus: 'normal' },
 *   fetchAll: true 
 * });
 */
async function getDevices({ filters = {}, fields = '', fetchAll = false, size = 50 } = {}) {
	const filter = buildFilter(filters);
	
	if (fetchAll) {
		return await fetchAllPaginated({
			resourcePath: '/device/devices',
			filter: filter,
			fields: fields
		});
	}
	
	const queryParams = new URLSearchParams();
	queryParams.append('size', size);
	if (filter) queryParams.append('filter', filter);
	if (fields) queryParams.append('fields', fields);
	
	const response = await LMClient({
		resourcePath: '/device/devices',
		queryParams: `?${queryParams.toString()}`,
		httpVerb: 'GET'
	});
	
	return response.items || [];
}

// ============================================================================
// DATASOURCE FUNCTIONS
// ============================================================================

/**
 * Gets a datasource by ID or name.
 * 
 * @async
 * @function getDatasource
 * @param {object} options - Options for datasource lookup
 * @param {number} [options.id] - Datasource ID
 * @param {string} [options.name] - Datasource name
 * @param {string} [options.fields=''] - Comma-separated list of fields to return
 * @returns {Promise<object|null>} Datasource object or null if not found
 * @throws {Error} If neither id nor name is provided
 * 
 * @example
 * const ds = await getDatasource({ name: 'Ping' });
 * const ds = await getDatasource({ id: 456 });
 */
async function getDatasource({ id, name, fields = '' }) {
	if (!id && !name) {
		throw new Error('Either id or name must be provided');
	}
	
	// If ID is provided, fetch directly
	if (id) {
		try {
			const queryParams = fields ? `?fields=${fields}` : '';
			const response = await LMClient({
				resourcePath: `/setting/datasources/${id}`,
				queryParams: queryParams,
				httpVerb: 'GET'
			});
			return response;
		} catch (error) {
			console.warn(`Datasource with ID ${id} not found:`, error.message);
			return null;
		}
	}
	
	// If name is provided, search for it
	const filter = `name:${name}`;
	const queryParams = new URLSearchParams();
	queryParams.append('filter', filter);
	queryParams.append('size', '1');
	if (fields) queryParams.append('fields', fields);
	
	const response = await LMClient({
		resourcePath: '/setting/datasources',
		queryParams: `?${queryParams.toString()}`,
		httpVerb: 'GET'
	});
	
	return response.items && response.items.length > 0 ? response.items[0] : null;
}

/**
 * Gets the device datasource (HDS) for a specific device and datasource combination.
 * This is needed to get the hdsId required for instance queries.
 * 
 * @async
 * @function getDeviceDatasource
 * @param {object} options - Options for device datasource lookup
 * @param {number} options.deviceId - Device ID
 * @param {number} [options.datasourceId] - Datasource ID
 * @param {string} [options.datasourceName] - Datasource name (alternative to datasourceId)
 * @returns {Promise<object|null>} Device datasource object or null if not found
 * @throws {Error} If deviceId is not provided or if neither datasourceId nor datasourceName is provided
 * 
 * @example
 * const hds = await getDeviceDatasource({ 
 *   deviceId: 123, 
 *   datasourceName: 'Ping' 
 * });
 */
async function getDeviceDatasource({ deviceId, datasourceId, datasourceName }) {
	if (!deviceId) {
		throw new Error('deviceId is required');
	}
	
	if (!datasourceId && !datasourceName) {
		throw new Error('Either datasourceId or datasourceName must be provided');
	}
	
	// If we only have datasource name, look up the ID first
	if (!datasourceId && datasourceName) {
		const datasource = await getDatasource({ name: datasourceName });
		if (!datasource) {
			console.warn(`Datasource with name ${datasourceName} not found`);
			return null;
		}
		datasourceId = datasource.id;
	}
	
	// Get device datasources and find the matching one
	const filter = `dataSourceId:${datasourceId}`;
	const queryParams = new URLSearchParams();
	queryParams.append('filter', filter);
	queryParams.append('size', '1');
	
	const response = await LMClient({
		resourcePath: `/device/devices/${deviceId}/devicedatasources`,
		queryParams: `?${queryParams.toString()}`,
		httpVerb: 'GET'
	});
	
	return response.items && response.items.length > 0 ? response.items[0] : null;
}

// ============================================================================
// DATASOURCE INSTANCE FUNCTIONS
// ============================================================================

/**
 * Gets datasource instances for a device.
 * Automatically resolves device and datasource IDs if names are provided.
 * 
 * @async
 * @function getDatasourceInstances
 * @param {object} options - Options for instance lookup
 * @param {number} [options.deviceId] - Device ID
 * @param {string} [options.deviceName] - Device name (alternative to deviceId)
 * @param {number} [options.datasourceId] - Datasource ID
 * @param {string} [options.datasourceName] - Datasource name (alternative to datasourceId)
 * @param {object} [options.filters={}] - Additional filters (e.g., { name: '*eth*' })
 * @param {string} [options.fields=''] - Comma-separated list of fields to return
 * @param {boolean} [options.fetchAll=false] - If true, fetches all pages
 * @returns {Promise<Array>} Array of instance objects
 * @throws {Error} If required parameters are missing
 * 
 * @example
 * const instances = await getDatasourceInstances({
 *   deviceName: 'myserver.example.com',
 *   datasourceName: 'Interfaces',
 *   filters: { name: '*eth*' },
 *   fetchAll: true
 * });
 */
async function getDatasourceInstances({ 
	deviceId, 
	deviceName, 
	datasourceId, 
	datasourceName, 
	filters = {}, 
	fields = '', 
	fetchAll = false 
}) {
	// Resolve device ID if needed
	if (!deviceId && deviceName) {
		const device = await getDevice({ name: deviceName });
		if (!device) {
			throw new Error(`Device with name ${deviceName} not found`);
		}
		deviceId = device.id;
	}
	
	if (!deviceId) {
		throw new Error('Either deviceId or deviceName must be provided');
	}
	
	// Get the device datasource (hdsId)
	const hds = await getDeviceDatasource({ deviceId, datasourceId, datasourceName });
	if (!hds) {
		throw new Error(`Datasource not found on device`);
	}
	
	const filter = buildFilter(filters);
	
	if (fetchAll) {
		return await fetchAllPaginated({
			resourcePath: `/device/devices/${deviceId}/devicedatasources/${hds.id}/instances`,
			filter: filter,
			fields: fields
		});
	}
	
	const queryParams = new URLSearchParams();
	queryParams.append('size', '50');
	if (filter) queryParams.append('filter', filter);
	if (fields) queryParams.append('fields', fields);
	
	const response = await LMClient({
		resourcePath: `/device/devices/${deviceId}/devicedatasources/${hds.id}/instances`,
		queryParams: `?${queryParams.toString()}`,
		httpVerb: 'GET'
	});
	
	return response.items || [];
}

/**
 * Gets data for a specific datasource instance.
 * Automatically resolves all required IDs if names are provided.
 * 
 * @async
 * @function getDatasourceInstanceData
 * @param {object} options - Options for data retrieval
 * @param {number} [options.deviceId] - Device ID
 * @param {string} [options.deviceName] - Device name (alternative to deviceId)
 * @param {number} [options.datasourceId] - Datasource ID
 * @param {string} [options.datasourceName] - Datasource name (alternative to datasourceId)
 * @param {number} [options.instanceId] - Instance ID
 * @param {string} [options.instanceName] - Instance name (alternative to instanceId)
 * @param {number} [options.start] - Start time (epoch milliseconds)
 * @param {number} [options.end] - End time (epoch milliseconds)
 * @param {string} [options.datapoints] - Comma-separated list of datapoint names
 * @param {number} [options.period=1] - Data aggregation period
 * @returns {Promise<object>} Instance data object
 * @throws {Error} If required parameters are missing
 * 
 * @example
 * const data = await getDatasourceInstanceData({
 *   deviceName: 'myserver.example.com',
 *   datasourceName: 'CPU',
 *   instanceName: 'CPU-0',
 *   start: Date.now() - 3600000, // 1 hour ago
 *   end: Date.now(),
 *   datapoints: 'CPUBusyPercent'
 * });
 */
async function getDatasourceInstanceData({ 
	deviceId, 
	deviceName, 
	datasourceId, 
	datasourceName, 
	instanceId, 
	instanceName,
	start,
	end,
	datapoints,
	period = 1
}) {
	// Resolve device ID if needed
	if (!deviceId && deviceName) {
		const device = await getDevice({ name: deviceName });
		if (!device) {
			throw new Error(`Device with name ${deviceName} not found`);
		}
		deviceId = device.id;
	}
	
	if (!deviceId) {
		throw new Error('Either deviceId or deviceName must be provided');
	}
	
	// Get the device datasource (hdsId)
	const hds = await getDeviceDatasource({ deviceId, datasourceId, datasourceName });
	if (!hds) {
		throw new Error(`Datasource not found on device`);
	}
	
	// Resolve instance ID if needed
	if (!instanceId && instanceName) {
		const instances = await getDatasourceInstances({
			deviceId,
			datasourceId: hds.dataSourceId,
			filters: { name: instanceName }
		});
		
		if (!instances || instances.length === 0) {
			throw new Error(`Instance with name ${instanceName} not found`);
		}
		instanceId = instances[0].id;
	}
	
	if (!instanceId) {
		throw new Error('Either instanceId or instanceName must be provided');
	}
	
	// Build query parameters
	const queryParams = new URLSearchParams();
	queryParams.append('period', period);
	if (start) queryParams.append('start', start);
	if (end) queryParams.append('end', end);
	if (datapoints) queryParams.append('datapoints', datapoints);
	
	const response = await LMClient({
		resourcePath: `/device/devices/${deviceId}/devicedatasources/${hds.id}/instances/${instanceId}/data`,
		queryParams: `?${queryParams.toString()}`,
		httpVerb: 'GET'
	});
	
	return response;
}

// ============================================================================
// ALERT FUNCTIONS
// ============================================================================

/**
 * Gets alerts based on flexible filter criteria.
 * 
 * @async
 * @function getAlerts
 * @param {object} options - Options for alert retrieval
 * @param {object} [options.filters={}] - Filter criteria
 * @param {string} [options.filters.resourceId] - Filter by device ID
 * @param {string} [options.filters.resourceName] - Filter by device name (supports wildcards)
 * @param {string} [options.filters.resourceGroupId] - Filter by device group ID
 * @param {string} [options.filters.severity] - Filter by severity (e.g., 'critical', 'error', 'warn')
 * @param {string} [options.filters.cleared] - Filter by cleared status ('true' or 'false')
 * @param {string} [options.filters.acked] - Filter by acknowledged status ('true' or 'false')
 * @param {string} [options.filters.rule] - Filter by alert rule name
 * @param {string} [options.filters.datasource] - Filter by datasource name (supports wildcards)
 * @param {string} [options.fields=''] - Comma-separated list of fields to return
 * @param {boolean} [options.fetchAll=false] - If true, fetches all pages
 * @param {number} [options.size=50] - Number of items per page (only used if fetchAll is false)
 * @returns {Promise<Array>} Array of alert objects
 * 
 * @example
 * // Get all critical alerts for a specific device
 * const alerts = await getAlerts({
 *   filters: { 
 *     resourceName: 'myserver.example.com',
 *     severity: 'critical',
 *     cleared: 'false'
 *   },
 *   fetchAll: true
 * });
 * 
 * @example
 * // Get all unacknowledged alerts for a device group
 * const alerts = await getAlerts({
 *   filters: { 
 *     resourceGroupId: '123',
 *     acked: 'false'
 *   }
 * });
 */
async function getAlerts({ filters = {}, fields = '', fetchAll = false, size = 50 } = {}) {
	const filter = buildFilter(filters);
	
	if (fetchAll) {
		return await fetchAllPaginated({
			resourcePath: '/alert/alerts',
			filter: filter,
			fields: fields
		});
	}
	
	const queryParams = new URLSearchParams();
	queryParams.append('size', size);
	if (filter) queryParams.append('filter', filter);
	if (fields) queryParams.append('fields', fields);
	
	const response = await LMClient({
		resourcePath: '/alert/alerts',
		queryParams: `?${queryParams.toString()}`,
		httpVerb: 'GET'
	});
	
	return response.items || [];
}

/**
 * Gets alerts for a specific device.
 * Convenience function that wraps getAlerts with device-specific filtering.
 * 
 * @async
 * @function getDeviceAlerts
 * @param {object} options - Options for alert retrieval
 * @param {number} [options.deviceId] - Device ID
 * @param {string} [options.deviceName] - Device name (alternative to deviceId)
 * @param {object} [options.additionalFilters={}] - Additional filter criteria
 * @param {string} [options.fields=''] - Comma-separated list of fields to return
 * @param {boolean} [options.fetchAll=false] - If true, fetches all pages
 * @returns {Promise<Array>} Array of alert objects
 * @throws {Error} If neither deviceId nor deviceName is provided
 * 
 * @example
 * const alerts = await getDeviceAlerts({
 *   deviceName: 'myserver.example.com',
 *   additionalFilters: { severity: 'critical' },
 *   fetchAll: true
 * });
 */
async function getDeviceAlerts({ deviceId, deviceName, additionalFilters = {}, fields = '', fetchAll = false }) {
	if (!deviceId && !deviceName) {
		throw new Error('Either deviceId or deviceName must be provided');
	}
	
	// Resolve device ID if needed
	if (!deviceId && deviceName) {
		const device = await getDevice({ name: deviceName });
		if (!device) {
			throw new Error(`Device with name ${deviceName} not found`);
		}
		deviceId = device.id;
	}
	
	const filters = {
		resourceId: deviceId,
		...additionalFilters
	};
	
	return await getAlerts({ filters, fields, fetchAll });
}

/**
 * Gets alerts for a specific device group.
 * Convenience function that wraps getAlerts with group-specific filtering.
 * 
 * @async
 * @function getGroupAlerts
 * @param {object} options - Options for alert retrieval
 * @param {number} options.groupId - Device group ID
 * @param {object} [options.additionalFilters={}] - Additional filter criteria
 * @param {string} [options.fields=''] - Comma-separated list of fields to return
 * @param {boolean} [options.fetchAll=false] - If true, fetches all pages
 * @returns {Promise<Array>} Array of alert objects
 * @throws {Error} If groupId is not provided
 * 
 * @example
 * const alerts = await getGroupAlerts({
 *   groupId: 123,
 *   additionalFilters: { cleared: 'false' },
 *   fetchAll: true
 * });
 */
async function getGroupAlerts({ groupId, additionalFilters = {}, fields = '', fetchAll = false }) {
	if (!groupId) {
		throw new Error('groupId is required');
	}
	
	const filters = {
		resourceGroupId: groupId,
		...additionalFilters
	};
	
	return await getAlerts({ filters, fields, fetchAll });
}

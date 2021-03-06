/*
 * Copyright (c) 2014, salesforce.com, inc.
 * All rights reserved.
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * - Neither the name of salesforce.com, inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission of salesforce.com, inc.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.androidsdk.smartsync.manager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import com.salesforce.androidsdk.accounts.UserAccount;
import com.salesforce.androidsdk.app.SalesforceSDKManager;
import com.salesforce.androidsdk.rest.ApiVersionStrings;
import com.salesforce.androidsdk.rest.RestClient;
import com.salesforce.androidsdk.rest.RestRequest;
import com.salesforce.androidsdk.rest.RestRequest.RestMethod;
import com.salesforce.androidsdk.rest.RestResponse;
import com.salesforce.androidsdk.smartstore.app.SalesforceSDKManagerWithSmartStore;
import com.salesforce.androidsdk.smartstore.store.IndexSpec;
import com.salesforce.androidsdk.smartstore.store.QuerySpec;
import com.salesforce.androidsdk.smartstore.store.SmartStore;
import com.salesforce.androidsdk.smartsync.util.Constants;


/**
 * Sync Manager
 */
public class SyncManager {
	private static Map<String, SyncManager> INSTANCES;
    private final ExecutorService threadPool = Executors.newFixedThreadPool(1);
	
    private String apiVersion;
	private SmartStore smartStore;
	private RestClient restClient;
	
	// SmartStore
	public static final String SYNCS_SOUP = "syncs_soup";
    
    // Sync
	public static final String SYNC_TYPE = "type";
	public static final String SYNC_TARGET = "target";
	public static final String SYNC_SOUP_NAME = "soupName";
	public static final String SYNC_OPTIONS = "options";
	public static final String SYNC_STATUS = "status";
	public static final String SYNC_PROGRESS = "progress";
	public static final String SYNC_TOTAL_SIZE = "totalSize";
	public static final String SYNC_FIELDLIST = "fieldlist";
	public static final String SYNC_SOBJECT_TYPE = "sobjectType";	
	
	// Target
	public static final String QUERY_TYPE = "type";
	public static final String QUERY = "query";

	// Local fields
	public static final String LOCALLY_CREATED = "__locally_created__";
	public static final String LOCALLY_UPDATED = "__locally_updated__";
	public static final String LOCALLY_DELETED = "__locally_deleted__";
	public static final String LOCAL = "__local__";

	// Broadcast
	public static final String SYNC_AS_STRING = "syncAsString";
	public static final String SYNC_INTENT_ACTION = "com.salesforce.androidsdk.smartsync.manager.SyncManager.UPDATE_SYNC";
	
	/**
     * Returns the instance of this class associated with this user account.
     *
     * @param account User account.
     * @return Instance of this class.
     */
    public static synchronized SyncManager getInstance(UserAccount account) {
        return getInstance(account, null);
    }

    /**
     * Returns the instance of this class associated with this user and community.
     *
     * @param account User account.
     * @param communityId Community ID.
     * @return Instance of this class.
     */
    public static synchronized SyncManager getInstance(UserAccount account, String communityId) {
        if (account == null) {
            account = SalesforceSDKManagerWithSmartStore.getInstance().getUserAccountManager().getCurrentUser();
        }
        if (account == null) {
            return null;
        }
        String uniqueId = account.getUserId();
        if (UserAccount.INTERNAL_COMMUNITY_ID.equals(communityId)) {
            communityId = null;
        }
        if (!TextUtils.isEmpty(communityId)) {
            uniqueId = uniqueId + communityId;
        }
        SyncManager instance = null;
        if (INSTANCES == null) {
            INSTANCES = new HashMap<String, SyncManager>();
            instance = new SyncManager(account, communityId);
            INSTANCES.put(uniqueId, instance);
        } else {
            instance = INSTANCES.get(uniqueId);
        }
        if (instance == null) {
            instance = new SyncManager(account, communityId);
            INSTANCES.put(uniqueId, instance);
        }
        return instance;
    }

    /**
     * Resets the Sync manager associated with this user account.
     *
     * @param account User account.
     */
    public static synchronized void reset(UserAccount account) {
        reset(account, null);
    }

    /**
     * Resets the Sync manager associated with this user and community.
     *
     * @param account User account.
     * @param communityId Community ID.
     */
    public static synchronized void reset(UserAccount account, String communityId) {
        if (account == null) {
            account = SalesforceSDKManagerWithSmartStore.getInstance().getUserAccountManager().getCurrentUser();
        }
        if (account != null) {
            String uniqueId = account.getUserId();
            if (UserAccount.INTERNAL_COMMUNITY_ID.equals(communityId)) {
                communityId = null;
            }
            if (!TextUtils.isEmpty(communityId)) {
                uniqueId = uniqueId + communityId;
            }
            if (INSTANCES != null) {
                INSTANCES.remove(uniqueId);
            }
        }
    }

    /**
     * Private parameterized constructor.
     *
     * @param account User account.
     * @param communityId Community ID.
     */
    private SyncManager(UserAccount account, String communityId) {
        apiVersion = ApiVersionStrings.VERSION_NUMBER;
        smartStore = CacheManager.getInstance(account, communityId).getSmartStore();
        restClient = NetworkManager.getInstance(account, communityId).getRestClient();
        
    	setupSyncsSoupIfNeeded();
    }
    
    /**
     * 
     */
    private void setupSyncsSoupIfNeeded() {
    	if (smartStore.hasSoup(SYNCS_SOUP)) 
    		return;
    	
    	final IndexSpec[] indexSpecs = {
    			new IndexSpec(SYNC_TYPE, SmartStore.Type.string)
    	};    	
		smartStore.registerSoup(SYNCS_SOUP, indexSpecs);
    }
    
    public JSONObject recordSync(Type type, JSONObject target, String soupName, JSONObject options) throws JSONException {
    	JSONObject sync = new JSONObject();
    	sync.put(SYNC_TYPE, type.name());
    	sync.put(SYNC_TARGET, target);
    	sync.put(SYNC_SOUP_NAME, soupName);
    	sync.put(SYNC_OPTIONS, options);
    	sync.put(SYNC_STATUS, Status.NEW.name());
    	sync.put(SYNC_PROGRESS, 0);
    	sync.put(SYNC_TOTAL_SIZE, -1);

    	sync = smartStore.upsert(SYNCS_SOUP, sync);
    	return sync;
    }
    
    public JSONObject getSyncStatus(long syncId) throws JSONException {
    	JSONArray syncs = smartStore.retrieve(SYNCS_SOUP, syncId);

    	if (syncs == null || syncs.length() == 0) 
    		return null;
    	
    	return syncs.getJSONObject(0);
    }

    public void runSync(final long syncId) throws JSONException {
    	JSONArray syncs = smartStore.retrieve(SYNCS_SOUP, syncId);
    	
    	if (syncs == null || syncs.length() == 0) 
    		throw new SmartSyncException("Sync not found: " + syncId);
    	
    	final JSONObject sync = syncs.getJSONObject(0);
    	final Type type = Type.valueOf(sync.getString(SYNC_TYPE));
    	
    	// Run (on a separate thread)
		threadPool.execute(new Runnable() {
			@Override
			public void run() {
				try {
					updateSync(sync, Status.RUNNING, 0, -1 /* don't change */);
					switch (type) {
					case syncDown: syncDown(sync); break;
					case syncUp:   syncUp(sync); break;
					default: throw new SmartSyncException("Unknown sync type: " + type);
			    	}
			    	// Update status to done
					updateSync(sync, Status.DONE, 100, -1 /* don't change */);
				}
				catch (Exception e) {
					Log.e("SmartSyncManager:runSync", "Error during sync: " + syncId, e);
					// Update status to failed
					updateSync(sync, Status.FAILED,  -1 /* don't change*/, -1 /* don't change */);
				}
			}
		});
    }

    /**
     * Update sync with new status, progress, totalSize
     * @param sync 
     * @param status
     * @param progress pass -1 to keep the current value
     * @param totalSize pass -1 to keep the current value
     */
    private void updateSync(JSONObject sync, Status status, int progress, int totalSize) {
    	long syncId = -1;
    	try {
        	syncId = sync.getLong(SmartStore.SOUP_ENTRY_ID);
    		sync.put(SYNC_STATUS, status.name());
			if (progress >=0) sync.put(SYNC_PROGRESS, progress);
			if (totalSize >= 0) sync.put(SYNC_TOTAL_SIZE, totalSize);
	    	smartStore.update(SYNCS_SOUP, sync, syncId);
	    	
	    	Intent intent = new Intent();
	    	intent.setAction(SYNC_INTENT_ACTION);
	    	intent.putExtra(SYNC_AS_STRING, sync.toString());
			SalesforceSDKManager.getInstance().getAppContext().sendBroadcast(intent);
    	}
    	catch (JSONException e) {
    		Log.e("SmartSyncManager:updateSync", "Unexpected json error for sync: " + syncId, e);
    	}
    }
    
    private void syncUp(JSONObject sync) throws Exception {
		String soupName = sync.getString(SYNC_SOUP_NAME);
		JSONObject options = sync.getJSONObject(SYNC_OPTIONS);
		JSONArray fieldlist = options.getJSONArray(SYNC_FIELDLIST);
		QuerySpec querySpec = QuerySpec.buildExactQuerySpec(soupName, LOCAL, "true", 2000); // XXX that could use a lot of memory
		
		// Call smartstore
		JSONArray records = smartStore.query(querySpec, 0); // TBD deal with more than 2000 locally modified records
		int totalSize = records.length();
		updateSync(sync, Status.RUNNING, 0, totalSize);
		for (int i = 0; i < totalSize; i++) {
			JSONObject record = records.getJSONObject(i);
			
			// Do we need to do a create, update or delete
			Action action = null;
			if (record.getBoolean(LOCALLY_DELETED)) 
				action = Action.delete;
			else if (record.getBoolean(LOCALLY_CREATED))
				action = Action.create;
			else if (record.getBoolean(LOCALLY_UPDATED))
				action = Action.update;
			
			if (action == null) {
				// Nothing to do for this record
				continue;
			}

			// Getting type and id
			String objectType = (String) SmartStore.project(record, Constants.SOBJECT_TYPE);
			String objectId = record.getString(Constants.ID);
			
			// Fields to save (in the case of create or update)
			Map<String, Object> fields = new HashMap<String, Object>();
			if (action == Action.create || action == Action.update) {
				for (int j=0; j<fieldlist.length(); j++) {
					String fieldName = fieldlist.getString(j);
					if (!fieldName.equals(Constants.ID)) {
						fields.put(fieldName, record.get(fieldName));
					}
				}
			}
			
			// Building create/update/delete request
			RestRequest request = null;
			switch (action) {
			case create: request = RestRequest.getRequestForCreate(apiVersion, objectType, fields); break;
			case delete: request = RestRequest.getRequestForDelete(apiVersion, objectType, objectId); break;
			case update: request = RestRequest.getRequestForUpdate(apiVersion, objectType, objectId, fields); break;
			default:
				break;
			
			}
			
			// Call server
			RestResponse response = restClient.sendSync(request);
			// Update smartstore
			if (response.isSuccess()) {
				// Replace id with server id during create
				if (action == Action.create) {
					record.put(Constants.ID, response.asJSONObject().get(Constants.LID));
				}
				// Set local flags to false
				record.put(LOCAL, false);
				record.put(LOCALLY_CREATED, false);
				record.put(LOCALLY_UPDATED, false);
				record.put(LOCALLY_DELETED, false);
				
				// Remove entry on delete
				if (action == Action.delete) {
					smartStore.delete(soupName, record.getLong(SmartStore.SOUP_ENTRY_ID));				
				}
				// Update entry otherwise
				else {
					smartStore.update(soupName, record, record.getLong(SmartStore.SOUP_ENTRY_ID));				
				}
			}
			
			
			// Updating status
			int progress = (i+1)*100 / totalSize;
			if (progress < 100) {
				updateSync(sync, Status.RUNNING, progress, -1 /* don't change */);
			}			
		}
	}

	private void syncDown(JSONObject sync) throws Exception {
    	JSONObject target = sync.getJSONObject(SYNC_TARGET);
    	QueryType queryType = QueryType.valueOf(target.getString(QUERY_TYPE));
		
		switch(queryType) {
		case mru:  syncDownMru(sync); break;
		case soql: syncDownSoql(sync); break;
		case sosl: syncDownSosl(sync); break;
		default: throw new SmartSyncException("Unknown query type: " + queryType);
		}
	}
	
	private void syncDownMru(JSONObject sync) throws Exception {
    	JSONObject target = sync.getJSONObject(SYNC_TARGET);
		String sobjectType = target.getString(SYNC_SOBJECT_TYPE);
		List<String>fieldlist = toArray(target.getJSONArray(SYNC_FIELDLIST));
    	String soupName = sync.getString(SYNC_SOUP_NAME);
    	
    	// Get recent items ids from server
		RestRequest request = RestRequest.getRequestForMetadata(apiVersion, sobjectType);
		RestResponse response = restClient.sendSync(request);
		List<String> recentItems = pluck(response.asJSONObject().getJSONArray(Constants.RECENT_ITEMS), Constants.ID);

		// Building SOQL query to get requested at
		String soql = "SELECT " + TextUtils.join(", ", fieldlist) + " FROM " + sobjectType + " WHERE Id IN ('" + TextUtils.join("', '", recentItems) + "')";

		// Get recent items attributes from server
		request = RestRequest.getRequestForQuery(apiVersion, soql);
		response = restClient.sendSync(request);
		JSONObject responseJson = response.asJSONObject();
		JSONArray records = responseJson.getJSONArray(Constants.RECORDS);
		int totalSize = records.length();
		
		// Save to smartstore
		updateSync(sync, Status.RUNNING, 0, totalSize);
		if (totalSize > 0)
			saveRecordsToSmartStore(soupName, records);
	}

	private void syncDownSoql(JSONObject sync) throws Exception {
		JSONObject target = sync.getJSONObject(SYNC_TARGET);
		String query = target.getString(QUERY);
		String soupName = sync.getString(SYNC_SOUP_NAME);	
		RestRequest request = RestRequest.getRequestForQuery(apiVersion, query);
	
		// Call server
		RestResponse response = restClient.sendSync(request);
		JSONObject responseJson = response.asJSONObject();

		int countSaved = 0;
		int totalSize = responseJson.getInt(Constants.TOTAL_SIZE);
		updateSync(sync, Status.RUNNING, 0, totalSize);
		
		do {
			JSONArray records = responseJson.getJSONArray(Constants.RECORDS);
			// Save to smartstore
			saveRecordsToSmartStore(soupName, records);
			countSaved += records.length();
			
			// Update sync status
			if (countSaved < totalSize)
				updateSync(sync, Status.RUNNING, countSaved*100 / totalSize, -1 /* don't change */);

			// Fetch next records if any
			String nextRecordsUrl = responseJson.optString(Constants.NEXT_RECORDS_URL, null);
			responseJson = nextRecordsUrl == null ? null : restClient.sendSync(RestMethod.GET, nextRecordsUrl, null).asJSONObject();
		}
		while (responseJson != null);
	}

	private void syncDownSosl(JSONObject sync) throws Exception {
		JSONObject target = sync.getJSONObject(SYNC_TARGET);
		String query = target.getString(QUERY);
		String soupName = sync.getString(SYNC_SOUP_NAME);	
		RestRequest request = RestRequest.getRequestForSearch(apiVersion, query);
	
		// Call server
		RestResponse response = restClient.sendSync(request);
	
		// Parse response
		JSONArray records = response.asJSONArray();
		int totalSize = records.length();
		
		// Save to smartstore
		updateSync(sync, Status.RUNNING, 0, totalSize);
		if (totalSize > 0)
			saveRecordsToSmartStore(soupName, records);
	}

	@SuppressWarnings("unchecked")
	private <T> List<T> toArray(JSONArray jsonArray) throws JSONException {
		List<T> arr = new ArrayList<T>();
		for (int i=0; i<jsonArray.length(); i++) {
			arr.add((T) jsonArray.get(i));
		}
		return arr;
	}

	@SuppressWarnings("unchecked")
	private <T> List<T> pluck(JSONArray jsonArray, String key) throws JSONException {
		List<T> arr = new ArrayList<T>();
		for (int i=0; i<jsonArray.length(); i++) {
			arr.add((T) jsonArray.getJSONObject(i).get(key));
		}
		return arr;
	}
	
	private void saveRecordsToSmartStore(String soupName, JSONArray records)
			throws JSONException {
		// Save to SmartStore
		smartStore.beginTransaction();
		for (int i = 0; i < records.length(); i++) {
			JSONObject record = records.getJSONObject(i);
			record.put(LOCAL, false);
			record.put(LOCALLY_CREATED, false);
			record.put(LOCALLY_UPDATED, false);
			record.put(LOCALLY_DELETED, false);
			smartStore.upsert(soupName, records.getJSONObject(i), Constants.ID, false);
		}
		smartStore.setTransactionSuccessful();
		smartStore.endTransaction();
	}
    
    /**
     * Enum for sync type
     */
    public enum Type {
        syncDown,
        syncUp
    }
    
    /**
     * Enum for sync status
     *
     */
    public enum Status {
    	NEW,
    	RUNNING,
    	DONE,
    	FAILED
    }
    
    /**
     * Enum for query type
     */
    public enum QueryType {
    	mru,
    	sosl,
    	soql
    }
    
    /**
     * Enum for action
     *
     */
    public enum Action {
    	create,
    	update,
    	delete
    }
    
    /**
     * Exception thrown by smart sync manager
     *
     */
    public static class SmartSyncException extends RuntimeException {

    	public SmartSyncException(String message) {
            super(message);
        }

		private static final long serialVersionUID = 1L;
    }
    

    /**
     * Sets the network manager to be used.
     * This is primarily used only by tests.
     * 
     * @param networkMgr
     */
    public void setNetworkManager(NetworkManager networkMgr) {
        restClient = networkMgr.getRestClient();
    }
}
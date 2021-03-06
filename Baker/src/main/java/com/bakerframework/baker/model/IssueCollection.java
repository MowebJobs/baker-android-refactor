/**
 * Copyright (c) 2013-2014. Francisco Contreras, Holland Salazar.
 * Copyright (c) 2015. Tobias Strebitzer, Francisco Contreras, Holland Salazar.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials
 * provided with the distribution.
 * Neither the name of the Baker Framework nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
 * EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 **/
package com.bakerframework.baker.model;

import android.util.Log;

import com.bakerframework.baker.BakerApplication;
import com.bakerframework.baker.R;
import com.bakerframework.baker.events.DownloadManifestCompleteEvent;
import com.bakerframework.baker.events.DownloadManifestErrorEvent;
import com.bakerframework.baker.helper.FileHelper;
import com.bakerframework.baker.jobs.DownloadManifestJob;
import com.bakerframework.baker.settings.Configuration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.solovyev.android.checkout.Inventory;
import org.solovyev.android.checkout.Sku;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import de.greenrobot.event.EventBus;

import static org.solovyev.android.checkout.ProductTypes.IN_APP;
import static org.solovyev.android.checkout.ProductTypes.SUBSCRIPTION;

public class IssueCollection {

    private HashMap<String, Issue> issueMap;
    private List<String> categories;

    private Sku subscriptionSku;

    // Tasks management
    private DownloadManifestJob downloadManifestJob;

    // Data Processing
    String JSON_ENCODING = "utf-8";
    SimpleDateFormat SDF_INPUT = new SimpleDateFormat(BakerApplication.getInstance().getString(R.string.format_input_date), Locale.US);
    SimpleDateFormat SDF_OUTPUT = new SimpleDateFormat(BakerApplication.getInstance().getString(R.string.format_output_date), Locale.US);

    // Categories
    public static final String ALL_CATEGORIES_STRING = "All Categories";

    // Event callbacks
    private ArrayList<IssueCollectionListener> listeners = new ArrayList<>();

    public IssueCollection() {
        // Initialize issue map
        issueMap = new HashMap<>();
        EventBus.getDefault().register(this);
    }

    public List<String> getCategories() {
        return categories;
    }


    public Sku getSubscriptionSku() {
        return subscriptionSku;
    }

    public List<String> getSkuList() {
        List<String> skuList = new ArrayList<>();
        for(Issue issue : getIssues()) {
            if(issue.getProductId() != null && !issue.getProductId().equals("")) {
                skuList.add(issue.getProductId());
            }
        }
        return skuList;
    }

    public List<Issue> getIssues() {
        if(isLoading() || issueMap == null) {
            return new ArrayList<>();
        }else{
            return new ArrayList<>(issueMap.values());
        }
    }

    public Issue getIssueBySku(Sku sku) {
        for(Issue issue : getIssues()) {
            if(issue.getProductId() != null && issue.getProductId().equals(sku.id)) {
                return issue;
            }
        }
        return null;
    }

    public boolean isLoading() {
        return downloadManifestJob != null && !downloadManifestJob.isCompleted();
    }

    // Event listeners

    public void addListener(IssueCollectionListener listener) {
        this.listeners.add(listener);
    }

    public void removeListener(IssueCollectionListener listener) {
        this.listeners.remove(listener);
    }

    // Reload data from backend
    public void reload() {
        if(!isLoading()) {
            downloadManifestJob = new DownloadManifestJob(Configuration.getManifestUrl(), getCachedFile());
            BakerApplication.getInstance().getJobManager().addJobInBackground(downloadManifestJob);
        }else{
            throw new RuntimeException("reload method invoked on Manifest while already downloading data");
        }
    }

    public void processManifestFileFromCache() {
        processManifestFile(getCachedFile());
    }

    private void processManifestFile(File file)  {

        try {

            // Create issues
            processJson(FileHelper.getJsonArrayFromFile(file));

            // Process categories
            categories = extractAllCategories();

            // Trigger issues loaded event
            for (IssueCollectionListener listener : listeners) {
                listener.onIssueCollectionLoaded();
            }

        } catch (JSONException e) {
            Log.e(this.getClass().getName(), "processing error (invalid json): " + e);
        } catch (IOException e) {
            Log.e(this.getClass().getName(), "processing error (buffer error): " + e);
        } catch (ParseException e) {
            Log.e(this.getClass().getName(), "processing error (parse error): " + e);
        }

    }

    private void processJson(final JSONArray jsonArray) throws JSONException, ParseException, UnsupportedEncodingException {
        JSONObject json;
        JSONArray jsonCategories;
        List<String> categories;
        List<String> issueNameList = new ArrayList<>();

        // Loop through issues
        int length = jsonArray.length();
        for (int i = 0; i < length; i++) {
            json = new JSONObject(jsonArray.getString(i));

            // Get issue data from json
            String issueName = jsonString(json.getString("name"));
            String issueProductId = json.isNull("product_id") ? null : jsonString(json.getString("product_id"));
            String issueTitle = jsonString(json.getString("title"));
            String issueInfo = jsonString(json.getString("info"));
            String issueDate = jsonDate(json.getString("date"));
            String issueCover = jsonString(json.getString("cover"));
            String issueUrl = jsonString(json.getString("url"));
            int issueSize = json.has("size") ? json.getInt("size") : 0;

            Issue issue;
            if(issueMap.containsKey(issueName)) {
                // Get issue from issue map
                issue = issueMap.get(issueName);
                // Flag fields for update
                if(!issue.getCover().equals(issueCover)) {
                    issue.setCoverChanged(true);
                }
                if(!issue.getUrl().equals(issueUrl)) {
                    issue.setUrlChanged(true);
                }
            }else{
                // Create new issue and store in issue map
                issue = new Issue(issueName);
                issueMap.put(issueName, issue);
            }

            // Set issue data
            issue.setTitle(issueTitle);
            issue.setProductId(issueProductId);
            issue.setInfo(issueInfo);
            issue.setDate(issueDate);
            issue.setCover(issueCover);
            issue.setUrl(issueUrl);
            issue.setSize(issueSize);

            // Set categories
            if(json.has("categories")) {
                jsonCategories = json.getJSONArray("categories");
                categories = new ArrayList<>();
                for (int j = 0; j < jsonCategories.length(); j++) {
                    categories.add(jsonCategories.get(j).toString());
                }
                issue.setCategories(categories);
            }else{
                issue.setCategories(new ArrayList<String>());
            }

            // Add name to issue name list
            issueNameList.add(issueName);

        }

        // Get rid of old issues that are no longer in the manifest
        for(Issue issue : issueMap.values()) {
            if(!issueNameList.contains(issue.getName())) {
                issueMap.remove(issue);
            }
        }

    }

    // Helpers

    private String jsonDate(String value) throws ParseException {
        return SDF_OUTPUT.format(SDF_INPUT.parse(value));
    }

    private String jsonString(String value) throws UnsupportedEncodingException {
        if(value != null) {
            return new String(value.getBytes(JSON_ENCODING), JSON_ENCODING);
        }else{
            return null;
        }
    }

    private String getCachedPath() {
        return Configuration.getCacheDirectory() + File.separator + BakerApplication.getInstance().getString(R.string.path_shelf);
    }

    private File getCachedFile() {
        return new File(getCachedPath());
    }

    public boolean isCacheAvailable() {
        return getCachedFile().exists() && getCachedFile().isFile();
    }

    public void updatePricesFromProducts(Inventory.Products inventoryProducts) {

        // Handle single issue purchases
        boolean hasSubscription = false;
        final Inventory.Product subscriptionProductCollection = inventoryProducts.get(SUBSCRIPTION);
        if (subscriptionProductCollection.supported) {

            for (Sku sku : subscriptionProductCollection.getSkus()) {
                if(sku.id.equals(BakerApplication.getInstance().getString(R.string.google_play_subscription_id))) {
                    hasSubscription = inventoryProducts.get(SUBSCRIPTION).isPurchased(sku);
                    subscriptionSku = sku;
                }
            }
        }

        // Handle single issue purchases
        final Inventory.Product inAppProductCollection = inventoryProducts.get(IN_APP);
        if (inAppProductCollection.supported) {
            // Update issue prices
            for (Sku sku : inAppProductCollection.getSkus()) {
                Issue issue = getIssueBySku(sku);
                if(issue != null) {
                    // Check for subscription
                    if(hasSubscription) {
                        issue.setPurchased(true);
                    }else{
                        issue.setPurchased(inAppProductCollection.isPurchased(sku));
                    }
                    issue.setSku(sku);
                }
            }
        } else {
            Log.e(getClass().getName(), "Error: " + R.string.err_purchase_not_possible);
        }

    }

    public List<String> extractAllCategories() {

        // Collect all categories from issues
        List<String> allCategories = new ArrayList<>();

        for(Issue issue : issueMap.values()) {
            for(String category : issue.getCategories()) {
                if(allCategories.indexOf(category) == -1) {
                    allCategories.add(category);
                }
            }
        }

        // Sort categories
        Collections.sort(allCategories);

        // Append all categories item
        allCategories.add(0, ALL_CATEGORIES_STRING);

        return allCategories;
    }


    public List<Issue> getDownloadingIssues() {
        List<Issue> downloadingIssues = new ArrayList<>();
        for (Issue issue : issueMap.values()) {
            if(issue.isDownloading()) {
                downloadingIssues.add(issue);
            }
        }
        return downloadingIssues;
    }


    public void cancelDownloadingIssues(final List<Issue> downloadingIssues) {
        for (Issue issue : downloadingIssues) {
            if(issue.isDownloading()) {
                issue.cancelDownloadJob();
            }
        }
    }


    // @SuppressWarnings("UnusedDeclaration")
    public void onEventMainThread(DownloadManifestCompleteEvent event) {
        processManifestFile(getCachedFile());
    }

    // @SuppressWarnings("UnusedDeclaration")
    public void onEventMainThread(DownloadManifestErrorEvent event) {

    }


}

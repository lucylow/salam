/*
 * Copyright (C) 2009 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.odk.collect.android.activities;

import static org.odk.collect.settings.keys.ProjectKeys.KEY_PROTOCOL;

import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.LiveData;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.odk.collect.android.R;
import org.odk.collect.android.adapters.InstanceUploaderAdapter;
import org.odk.collect.android.backgroundwork.FormUpdateAndInstanceSubmitScheduler;
import org.odk.collect.android.backgroundwork.InstanceSubmitScheduler;
import org.odk.collect.android.dao.CursorLoaderFactory;
import org.odk.collect.android.databinding.InstanceUploaderListBinding;
import org.odk.collect.android.formlists.sorting.FormListSortingOption;
import org.odk.collect.android.gdrive.GoogleSheetsUploaderActivity;
import org.odk.collect.android.injection.DaggerUtils;
import org.odk.collect.androidshared.network.NetworkStateProvider;
import org.odk.collect.android.preferences.screens.ProjectPreferencesActivity;
import org.odk.collect.android.projects.CurrentProjectProvider;
import org.odk.collect.android.utilities.PlayServicesChecker;
import org.odk.collect.androidshared.ui.ToastUtils;
import org.odk.collect.androidshared.ui.multiclicksafe.MultiClickGuard;
import org.odk.collect.settings.SettingsProvider;
import org.odk.collect.settings.keys.ProjectKeys;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import timber.log.Timber;

/**
 * Responsible for displaying all the valid forms in the forms directory. Stores
 * the path to selected form for use by {@link MainMenuActivity}.
 *
 * @author Carl Hartung (carlhartung@gmail.com)
 * @author Yaw Anokwa (yanokwa@gmail.com)
 */

public class InstanceUploaderListActivity extends InstanceListActivity implements
        OnLongClickListener, AdapterView.OnItemClickListener, LoaderManager.LoaderCallbacks<Cursor> {
    private static final String SHOW_ALL_MODE = "showAllMode";
    private static final String INSTANCE_UPLOADER_LIST_SORTING_ORDER = "instanceUploaderListSortingOrder";

    private static final int INSTANCE_UPLOADER = 0;

    InstanceUploaderListBinding binding;

    @Inject
    CurrentProjectProvider currentProjectProvider;

    private boolean showAllMode;

    // Default to true so the send button is disabled until the worker status is updated by the
    // observer
    private boolean autoSendOngoing = true;

    @Inject
    NetworkStateProvider connectivityProvider;

    @Inject
    InstanceSubmitScheduler instanceSubmitScheduler;

    @Inject
    SettingsProvider settingsProvider;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Timber.i("onCreate");

        DaggerUtils.getComponent(this).inject(this);

        // set title
        setTitle(getString(R.string.send_data));
        binding = InstanceUploaderListBinding.inflate(LayoutInflater.from(this));
        setContentView(binding.getRoot());
        binding.uploadButton.setOnClickListener(v -> onUploadButtonsClicked());
        if (savedInstanceState != null) {
            showAllMode = savedInstanceState.getBoolean(SHOW_ALL_MODE);
        }

        init();
    }

    public void onUploadButtonsClicked() {
        if (!connectivityProvider.isDeviceOnline()) {
            ToastUtils.showShortToast(this, R.string.no_connection);
            return;
        }

        if (autoSendOngoing) {
            ToastUtils.showShortToast(this, R.string.send_in_progress);
            return;
        }

        long[] instanceIds = listView.getCheckedItemIds();

        if (instanceIds.length > 0) {
            selectedInstances.clear();
            setAllToCheckedState(listView, false);
            toggleButtonLabel(findViewById(R.id.toggle_button), listView);
            binding.uploadButton.setEnabled(false);

            uploadSelectedFiles(instanceIds);
        } else {
            // no items selected
            ToastUtils.showLongToast(this, R.string.noselect_error);
        }
    }

    void init() {
        binding.uploadButton.setText(R.string.send_selected_data);

        binding.toggleButton.setLongClickable(true);
        binding.toggleButton.setOnClickListener(v -> {
            ListView lv = listView;
            boolean allChecked = toggleChecked(lv);
            toggleButtonLabel(binding.toggleButton, lv);
            binding.uploadButton.setEnabled(allChecked);
            if (allChecked) {
                for (int i = 0; i < lv.getCount(); i++) {
                    selectedInstances.add(lv.getItemIdAtPosition(i));
                }
            } else {
                selectedInstances.clear();
            }
        });
        binding.toggleButton.setOnLongClickListener(this);

        setupAdapter();

        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setItemsCanFocus(false);
        listView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            binding.uploadButton.setEnabled(areCheckedItems());
        });

        sortingOptions = Arrays.asList(
                new FormListSortingOption(
                        R.drawable.ic_sort_by_alpha,
                        R.string.sort_by_name_asc
                ),
                new FormListSortingOption(
                        R.drawable.ic_sort_by_alpha,
                        R.string.sort_by_name_desc
                ),
                new FormListSortingOption(
                        R.drawable.ic_access_time,
                        R.string.sort_by_date_desc
                ),
                new FormListSortingOption(
                        R.drawable.ic_access_time,
                        R.string.sort_by_date_asc
                )
        );

        getSupportLoaderManager().initLoader(LOADER_ID, null, this);

        // Start observer that sets autoSendOngoing field based on AutoSendWorker status
        updateAutoSendStatus();
    }

    /**
     * Updates whether an auto-send job is ongoing.
     */
    private void updateAutoSendStatus() {
        // This shouldn't use WorkManager directly but it's likely this code will be removed when
        // we eventually move sending forms to a Foreground Service (rather than a blocking AsyncTask)
        String tag = ((FormUpdateAndInstanceSubmitScheduler) instanceSubmitScheduler).getAutoSendTag(currentProjectProvider.getCurrentProject().getUuid());
        LiveData<List<WorkInfo>> statuses = WorkManager.getInstance().getWorkInfosForUniqueWorkLiveData(tag);
        statuses.observe(this, workStatuses -> {
            if (workStatuses != null) {
                for (WorkInfo status : workStatuses) {
                    if (status.getState().equals(WorkInfo.State.RUNNING)) {
                        autoSendOngoing = true;
                        return;
                    }
                }
                autoSendOngoing = false;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.uploadButton.setText(R.string.send_selected_data);
    }

    private void uploadSelectedFiles(long[] instanceIds) {
        String server = settingsProvider.getUnprotectedSettings().getString(KEY_PROTOCOL);

        if (server.equalsIgnoreCase(ProjectKeys.PROTOCOL_GOOGLE_SHEETS)) {
            // if it's Sheets, start the Sheets uploader
            // first make sure we have a google account selected
            if (new PlayServicesChecker().isGooglePlayServicesAvailable(this)) {
                Intent i = new Intent(this, GoogleSheetsUploaderActivity.class);
                i.putExtra(FormEntryActivity.KEY_INSTANCES, instanceIds);
                startActivityForResult(i, INSTANCE_UPLOADER);
            } else {
                new PlayServicesChecker().showGooglePlayServicesAvailabilityErrorDialog(this);
            }
        } else {
            // otherwise, do the normal aggregate/other thing.
            Intent i = new Intent(this, InstanceUploaderActivity.class);
            i.putExtra(FormEntryActivity.KEY_INSTANCES, instanceIds);
            startActivityForResult(i, INSTANCE_UPLOADER);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.instance_uploader_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!MultiClickGuard.allowClick(getClass().getName())) {
            return true;
        }

        switch (item.getItemId()) {
            case R.id.menu_preferences:
                createPreferencesMenu();
                return true;
            case R.id.menu_change_view:
                showSentAndUnsentChoices();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void createPreferencesMenu() {
        Intent i = new Intent(this, ProjectPreferencesActivity.class);
        startActivity(i);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long rowId) {
        if (listView.isItemChecked(position)) {
            selectedInstances.add(listView.getItemIdAtPosition(position));
        } else {
            selectedInstances.remove(listView.getItemIdAtPosition(position));
        }

        binding.uploadButton.setEnabled(areCheckedItems());
        Button toggleSelectionsButton = findViewById(R.id.toggle_button);
        toggleButtonLabel(toggleSelectionsButton, listView);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SHOW_ALL_MODE, showAllMode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == RESULT_CANCELED) {
            selectedInstances.clear();
            return;
        }

        switch (requestCode) {
            // returns with a form path, start entry
            case INSTANCE_UPLOADER:
                if (intent.getBooleanExtra(FormEntryActivity.KEY_SUCCESS, false)) {
                    listView.clearChoices();
                    if (listAdapter.isEmpty()) {
                        finish();
                    }
                }
                break;
        }
        super.onActivityResult(requestCode, resultCode, intent);
    }

    private void setupAdapter() {
        listAdapter = new InstanceUploaderAdapter(this, null);
        listView.setAdapter(listAdapter);
        checkPreviouslyCheckedItems();
    }

    @Override
    protected String getSortingOrderKey() {
        return INSTANCE_UPLOADER_LIST_SORTING_ORDER;
    }

    @Override
    protected void updateAdapter() {
        getSupportLoaderManager().restartLoader(LOADER_ID, null, this);
    }

    @NonNull
    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        showProgressBar();
        if (showAllMode) {
            return new CursorLoaderFactory(currentProjectProvider).createCompletedUndeletedInstancesCursorLoader(getFilterText(), getSortingOrder());
        } else {
            return new CursorLoaderFactory(currentProjectProvider).createFinalizedInstancesCursorLoader(getFilterText(), getSortingOrder());
        }
    }

    @Override
    public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
        hideProgressBarAndAllow();
        listAdapter.changeCursor(cursor);
        checkPreviouslyCheckedItems();
        toggleButtonLabel(findViewById(R.id.toggle_button), listView);
    }

    @Override
    public void onLoaderReset(@NonNull Loader<Cursor> loader) {
        listAdapter.swapCursor(null);
    }

    @Override
    public boolean onLongClick(View v) {
        return showSentAndUnsentChoices();
    }

    /*
     * Create a dialog with options to save and exit, save, or quit without
     * saving
     */
    private boolean showSentAndUnsentChoices() {
        String[] items = {getString(R.string.show_unsent_forms),
                getString(R.string.show_sent_and_unsent_forms)};

        AlertDialog alertDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.change_view))
                .setNeutralButton(getString(R.string.cancel), (dialog, id) -> {
                    dialog.cancel();
                })
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: // show unsent
                            showAllMode = false;
                            updateAdapter();
                            break;

                        case 1: // show all
                            showAllMode = true;
                            updateAdapter();
                            break;

                        case 2:// do nothing
                            break;
                    }
                }).create();
        alertDialog.show();
        return true;
    }
}

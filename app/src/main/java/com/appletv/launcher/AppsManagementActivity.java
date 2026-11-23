package com.appletv.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** TV-remote-friendly controls for app visibility and the non-dock grid order. */
public final class AppsManagementActivity extends LauncherActivity {
    static final String EXTRA_SELECT_PACKAGE = "select_package";

    private final List<AppRow> apps = new ArrayList<>();
    private final Set<String> userHidden = new HashSet<>();
    private final Set<String> selected = new HashSet<>();
    private final Set<String> dockApps = new HashSet<>();
    private final Set<String> homeDockApps = new HashSet<>();
    private final Set<String> dockExcludedApps = new HashSet<>();
    private final List<String> order = new ArrayList<>();
    private FocusGlassController focusGlass;
    private LinearLayout rows;
    private LinearLayout folderActionsRow;
    private LinearLayout dockActionsRow;
    private TextView createFolderChoice;
    private TextView manageFoldersChoice;
    private TextView showSelectedChoice;
    private TextView hideSelectedChoice;
    private View pendingInitialFocus;
    private boolean changed;
    private boolean animations;
    private boolean reducedMotion;
    private String uninstallingPackage;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_apps_management);
        Config config = Config.load(this);
        animations = config.animations;
        reducedMotion = config.reducedMotion;
        focusGlass = new FocusGlassController(this);
        applyAppearance();
        rows = findViewById(R.id.appRows);
        folderActionsRow = findViewById(R.id.folderActionsRow);
        dockActionsRow = findViewById(R.id.dockActionsRow);
        createFolderChoice = findViewById(R.id.createFolderChoice);
        manageFoldersChoice = findViewById(R.id.manageFoldersChoice);
        showSelectedChoice = findViewById(R.id.showSelectedChoice);
        hideSelectedChoice = findViewById(R.id.hideSelectedChoice);
        createFolderChoice.setOnClickListener(v -> createFolder());
        manageFoldersChoice.setOnClickListener(v -> manageFolders());
        showSelectedChoice.setOnClickListener(v -> setSelectedAppsVisible(true));
        hideSelectedChoice.setOnClickListener(v -> setSelectedAppsVisible(false));
        SharedPreferences preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        userHidden.addAll(preferences.getStringSet("user_hidden_apps", Collections.emptySet()));
        dockExcludedApps.addAll(preferences.getStringSet(
                DockApps.EXCLUDED_APPS_KEY, Collections.emptySet()));
        String savedOrder = preferences.getString("app_order", "");
        if (savedOrder != null && !savedOrder.isEmpty()) order.addAll(Arrays.asList(savedOrder.split(",")));
        loadApps();
        String packageToSelect = getIntent().getStringExtra(EXTRA_SELECT_PACKAGE);
        if (packageToSelect != null && !homeDockApps.contains(packageToSelect)
                && !userHidden.contains(packageToSelect)
                && apps.stream().anyMatch(app -> app.pkg.equals(packageToSelect))) {
            Set<String> groupedPackages = new HashSet<>();
            for (LauncherFolders.Folder folder : LauncherFolders.load(this)) {
                groupedPackages.addAll(folder.packages);
            }
            if (!groupedPackages.contains(packageToSelect)) selected.add(packageToSelect);
        }
        renderRows(packageToSelect, 2);
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> fixedHidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        Map<String, AppRow> unique = new LinkedHashMap<>();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo info : pm.queryIntentActivities(probe, 0)) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || fixedHidden.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, info.activityInfo.name);
            AppRow current = unique.get(pkg);
            if (current == null) {
                unique.put(pkg, new AppRow(pkg, String.valueOf(info.loadLabel(pm)), component,
                        info.loadIcon(pm)));
            } else {
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch != null && component.equals(launch.getComponent())) current.component = component;
            }
        }
        Config config = Config.load(this);
        dockApps.clear();
        dockApps.addAll(config.dockApps);
        for (String pkg : config.dockApps) {
            if (fixedHidden.contains(pkg) || unique.containsKey(pkg)) continue;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            ResolveInfo info = pm.resolveActivity(launch, 0);
            if (info == null || info.activityInfo == null || !pkg.equals(info.activityInfo.packageName)) continue;
            String label = "com.amazon.avod".equals(pkg)
                    ? getString(R.string.prime_video) : String.valueOf(info.loadLabel(pm));
            unique.put(pkg, new AppRow(pkg, label, launch.getComponent(), info.loadIcon(pm)));
        }
        apps.clear();
        apps.addAll(unique.values());
        if (order.isEmpty()) {
            Collections.sort(apps, Comparator.comparing(app -> app.label.toLowerCase()));
            for (AppRow app : apps) order.add(app.pkg);
        } else {
            apps.sort((left, right) -> {
                int li = order.indexOf(left.pkg), ri = order.indexOf(right.pkg);
                if (li < 0 && ri < 0) return left.label.compareToIgnoreCase(right.label);
                if (li < 0) return 1;
                if (ri < 0) return -1;
                return Integer.compare(li, ri);
            });
            for (AppRow app : apps) if (!order.contains(app.pkg)) order.add(app.pkg);
        }
        refreshHomeDockApps(Config.load(this));
    }

    private void renderRows() {
        renderRows(null, 1);
    }

    private void renderRows(String focusPackage, int focusAction) {
        rows.removeAllViews();
        View focusTarget = null;
        List<View[]> actionRows = new ArrayList<>();
        Set<String> groupedPackages = new HashSet<>();
        for (LauncherFolders.Folder folder : LauncherFolders.load(this)) groupedPackages.addAll(folder.packages);
        refreshBulkActionVisibility(groupedPackages);
        for (int i = 0; i < apps.size(); i++) {
            final int index = i;
            AppRow app = apps.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(16), dp(9), dp(16), dp(9));
            row.setBackgroundResource(LauncherUiSettings.isLight(this)
                    ? R.drawable.glass_panel_light : R.drawable.glass_panel);
            row.setElevation(dp(2));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(dp(5), dp(5), dp(5), dp(5));
            android.graphics.drawable.GradientDrawable iconBackground =
                    new android.graphics.drawable.GradientDrawable();
            iconBackground.setColor(LauncherUiSettings.isLight(this) ? 0xFFE2E2E7 : 0x3329292D);
            iconBackground.setStroke(dp(1), LauncherUiSettings.isLight(this)
                    ? 0x26000000 : 0x26FFFFFF);
            iconBackground.setCornerRadius(dp(12));
            icon.setBackground(iconBackground);
            row.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));

            LinearLayout appDetails = new LinearLayout(this);
            appDetails.setOrientation(LinearLayout.VERTICAL);
            appDetails.setGravity(Gravity.CENTER_VERTICAL);
            appDetails.setPadding(dp(16), 0, dp(14), 0);
            TextView label = new TextView(this);
            label.setText(app.label);
            label.setTextColor(LauncherUiSettings.isLight(this)
                    ? 0xFF1C1C1E : getColor(R.color.tvos_label_primary));
            label.setTextSize(18);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            appDetails.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout metadata = new LinearLayout(this);
            metadata.setGravity(Gravity.CENTER_VERTICAL);
            metadata.setOrientation(LinearLayout.HORIZONTAL);
            TextView packageName = new TextView(this);
            packageName.setText(app.pkg);
            packageName.setTextColor(LauncherUiSettings.isLight(this)
                    ? 0xFF7A7A80 : getColor(R.color.tvos_label_secondary));
            packageName.setTextSize(12);
            packageName.setSingleLine(true);
            packageName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            metadata.addView(packageName, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            boolean onHomeDock = homeDockApps.contains(app.pkg);
            if (onHomeDock) {
                TextView dockBadge = new TextView(this);
                dockBadge.setText(R.string.home_dock_badge);
                dockBadge.setTextColor(0xFFFFFFFF);
                dockBadge.setTextSize(11);
                dockBadge.setTypeface(null, android.graphics.Typeface.BOLD);
                dockBadge.setSingleLine(true);
                dockBadge.setPadding(dp(9), dp(3), dp(9), dp(3));
                dockBadge.setBackgroundResource(R.drawable.dock_status_badge);
                LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                badgeParams.setMargins(dp(8), 0, 0, 0);
                metadata.addView(dockBadge, badgeParams);
            }
            appDetails.addView(metadata, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(appDetails, new LinearLayout.LayoutParams(0, dp(64), 1f));

            TextView visibility = actionButton(userHidden.contains(app.pkg)
                    ? R.string.show_app : R.string.hide_app);
            visibility.setOnClickListener(v -> {
                if (userHidden.contains(app.pkg)) userHidden.remove(app.pkg);
                else {
                    userHidden.add(app.pkg);
                    selected.remove(app.pkg);
                }
                saveChoices();
                renderRows(app.pkg, 1);
            });
            addActionButton(row, visibility, 100);

            boolean dockFavorite = homeDockApps.contains(app.pkg);
            boolean inFolder = groupedPackages.contains(app.pkg);
            int selectionLabel = inFolder ? R.string.in_folder : selected.contains(app.pkg)
                    ? R.string.selected_app : dockFavorite
                    ? R.string.dock_favorite : R.string.select_app;
            TextView selection = actionButton(selectionLabel);
            selection.setEnabled(!inFolder);
            selection.setAlpha(selection.isEnabled() ? 1f : 0.5f);
            selection.setOnClickListener(v -> {
                if (selected.contains(app.pkg)) selected.remove(app.pkg);
                else selected.add(app.pkg);
                renderRows(app.pkg, 2);
            });
            addActionButton(row, selection, 108);

            TextView up = actionButton(R.string.move_up);
            up.setEnabled(i > 0);
            up.setOnClickListener(v -> move(index, -1));
            addActionButton(row, up, 90);

            TextView down = actionButton(R.string.move_down);
            down.setEnabled(i < apps.size() - 1);
            down.setOnClickListener(v -> move(index, 1));
            addActionButton(row, down, 90);

            TextView uninstall = actionButton(R.string.app_info_uninstall);
            uninstall.setOnClickListener(v -> uninstallApp(app.pkg));
            addActionButton(row, uninstall, 120);

            View[] actions = {visibility, selection, up, down, uninstall};
            linkVerticalFocus(actions, i, actionRows);
            actionRows.add(actions);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(10);
            rows.addView(row, rowParams);
            if (app.pkg.equals(focusPackage)) {
                int actionIndex = Math.max(0, Math.min(focusAction - 1, actions.length - 1));
                if (!actions[actionIndex].isEnabled()) actionIndex = 0;
                focusTarget = actions[actionIndex];
            }
        }
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_apps_to_manage);
            empty.setTextColor(LauncherUiSettings.isLight(this)
                    ? 0xFF6E6E73 : getColor(R.color.tvos_label_secondary));
            empty.setTextSize(18);
            empty.setPadding(dp(16), dp(20), dp(16), dp(20));
            rows.addView(empty);
        }
        if (!actionRows.isEmpty()) {
            linkBulkActionFocus(actionRows);
            for (int row = 0; row + 1 < actionRows.size(); row++) {
                for (int column = 0; column < actionRows.get(row).length; column++) {
                    actionRows.get(row)[column].setNextFocusDownId(
                            nearestEnabledAction(actionRows.get(row + 1), column).getId());
                }
            }
        }
        View initialFocus = focusTarget != null ? focusTarget
                : actionRows.isEmpty() ? null : actionRows.get(0)[0];
        if (initialFocus != null) {
            pendingInitialFocus = initialFocus;
            rows.post(() -> {
                if (!initialFocus.requestFocus()) pendingInitialFocus = initialFocus;
                else pendingInitialFocus = null;
            });
        }
    }

    private void refreshBulkActionVisibility(Set<String> groupedPackages) {
        int selectedCount = 0;
        boolean hasDockSelection = false;
        boolean hasNonDockSelection = false;
        boolean canCreateFolder = true;
        for (AppRow app : apps) {
            if (!selected.contains(app.pkg)) continue;
            selectedCount++;
            hasDockSelection |= homeDockApps.contains(app.pkg);
            hasNonDockSelection |= !homeDockApps.contains(app.pkg);
            canCreateFolder &= !userHidden.contains(app.pkg) && !groupedPackages.contains(app.pkg);
        }

        createFolderChoice.setVisibility(selectedCount >= 2 && canCreateFolder
                ? View.VISIBLE : View.GONE);
        manageFoldersChoice.setVisibility(LauncherFolders.load(this).isEmpty()
                ? View.GONE : View.VISIBLE);
        showSelectedChoice.setVisibility(hasNonDockSelection ? View.VISIBLE : View.GONE);
        hideSelectedChoice.setVisibility(hasDockSelection ? View.VISIBLE : View.GONE);
        folderActionsRow.setVisibility(createFolderChoice.getVisibility() == View.VISIBLE
                || manageFoldersChoice.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
        dockActionsRow.setVisibility(showSelectedChoice.getVisibility() == View.VISIBLE
                || hideSelectedChoice.getVisibility() == View.VISIBLE ? View.VISIBLE : View.GONE);
    }

    private void linkBulkActionFocus(List<View[]> actionRows) {
        View firstRowFirst = actionRows.get(0)[0];
        View firstRowLast = actionRows.get(0)[actionRows.get(0).length - 1];
        View folderDownForCreate = visibleAction(showSelectedChoice, hideSelectedChoice, firstRowFirst);
        View folderDownForManage = visibleAction(hideSelectedChoice, showSelectedChoice, firstRowLast);
        View folderForAdd = visibleAction(createFolderChoice, manageFoldersChoice, null);
        View folderForRemove = visibleAction(manageFoldersChoice, createFolderChoice, null);

        if (createFolderChoice.getVisibility() == View.VISIBLE) {
            createFolderChoice.setNextFocusDownId(folderDownForCreate == null
                    ? View.NO_ID : folderDownForCreate.getId());
        }
        if (manageFoldersChoice.getVisibility() == View.VISIBLE) {
            manageFoldersChoice.setNextFocusDownId(folderDownForManage == null
                    ? View.NO_ID : folderDownForManage.getId());
        }
        if (showSelectedChoice.getVisibility() == View.VISIBLE) {
            showSelectedChoice.setNextFocusDownId(firstRowFirst.getId());
            showSelectedChoice.setNextFocusUpId(folderForAdd == null ? View.NO_ID : folderForAdd.getId());
        }
        if (hideSelectedChoice.getVisibility() == View.VISIBLE) {
            hideSelectedChoice.setNextFocusDownId(firstRowLast.getId());
            hideSelectedChoice.setNextFocusUpId(folderForRemove == null
                    ? View.NO_ID : folderForRemove.getId());
        }
    }

    private View visibleAction(View preferred, View fallback, View lastResort) {
        if (preferred.getVisibility() == View.VISIBLE) return preferred;
        if (fallback.getVisibility() == View.VISIBLE) return fallback;
        return lastResort;
    }

    private void linkVerticalFocus(View[] actions, int rowIndex, List<View[]> actionRows) {
        for (int column = 0; column < actions.length; column++) {
            View action = actions[column];
            View upTarget = rowIndex == 0 ? visibleAction(
                    column < 3 ? showSelectedChoice : hideSelectedChoice,
                    column < 3 ? hideSelectedChoice : showSelectedChoice,
                    visibleAction(createFolderChoice, manageFoldersChoice, null))
                    : nearestEnabledAction(actionRows.get(rowIndex - 1), column);
            action.setNextFocusUpId(upTarget == null ? View.NO_ID : upTarget.getId());
            final int actionColumn = column;
            action.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    View target = rowIndex > 0
                            ? nearestEnabledAction(actionRows.get(rowIndex - 1), actionColumn)
                            : visibleAction(actionColumn < 3 ? showSelectedChoice : hideSelectedChoice,
                                    actionColumn < 3 ? hideSelectedChoice : showSelectedChoice,
                                    visibleAction(createFolderChoice, manageFoldersChoice, null));
                    if (target == null) return false;
                    target.requestFocus();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && rowIndex + 1 < actionRows.size()) {
                    nearestEnabledAction(actionRows.get(rowIndex + 1), actionColumn).requestFocus();
                    return true;
                }
                return false;
            });
        }
    }

    private View nearestEnabledAction(View[] actions, int preferredColumn) {
        for (int distance = 0; distance < actions.length; distance++) {
            int left = preferredColumn - distance;
            if (left >= 0 && actions[left].isEnabled()) return actions[left];
            int right = preferredColumn + distance;
            if (distance > 0 && right < actions.length && actions[right].isEnabled()) {
                return actions[right];
            }
        }
        return actions[0];
    }

    private TextView actionButton(int textId) {
        TextView button = new TextView(this);
        button.setId(View.generateViewId());
        button.setText(textId);
        button.setGravity(Gravity.CENTER);
        boolean light = LauncherUiSettings.isLight(this);
        button.setTextColor(getResources().getColorStateList(
                light ? R.color.pill_text_light : R.color.pill_text_dark));
        button.setTextSize(15);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackgroundResource(light ? R.drawable.settings_choice_bg_light
                : R.drawable.glass_pill_bg);
        return button;
    }

    private void addActionButton(LinearLayout row, View button, int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(50));
        params.setMargins(dp(4), 0, 0, 0);
        row.addView(button, params);
    }

    private void applyAppearance() {
        boolean light = LauncherUiSettings.isLight(this);
        findViewById(R.id.appsRoot).setBackgroundResource(light
                ? R.drawable.settings_backdrop_light : R.drawable.settings_backdrop);
        findViewById(R.id.appsHeaderPanel).setBackgroundResource(light
                ? R.drawable.glass_panel_light : R.drawable.glass_panel);
        ((TextView) findViewById(R.id.appsTitle)).setTextColor(light
                ? 0xFF1C1C1E : getColor(R.color.tvos_label_primary));
        ((TextView) findViewById(R.id.appsHint)).setTextColor(light
                ? 0xFF6E6E73 : getColor(R.color.tvos_label_secondary));
        for (int id : new int[]{R.id.createFolderChoice, R.id.manageFoldersChoice}) {
            TextView choice = findViewById(id);
            choice.setBackgroundResource(light ? R.drawable.settings_choice_bg_light
                    : R.drawable.glass_pill_bg);
            choice.setTextColor(getResources().getColorStateList(light
                    ? R.color.settings_choice_text_light : R.color.settings_choice_text_dark));
        }
    }

    private void move(int index, int direction) {
        int destination = index + direction;
        if (destination < 0 || destination >= apps.size()) return;
        String focusedPackage = apps.get(index).pkg;
        Collections.swap(apps, index, destination);
        order.clear();
        for (AppRow app : apps) order.add(app.pkg);
        saveChoices();
        renderRows(focusedPackage, direction < 0 ? 3 : 4);
    }

    private void createFolder() {
        List<String> folderPackages = new ArrayList<>();
        Set<String> groupedPackages = new HashSet<>();
        for (LauncherFolders.Folder folder : LauncherFolders.load(this)) {
            groupedPackages.addAll(folder.packages);
        }
        for (AppRow app : apps) {
            if (selected.contains(app.pkg) && !groupedPackages.contains(app.pkg)) {
                folderPackages.add(app.pkg);
            }
        }
        if (folderPackages.size() < 2) {
            Toast.makeText(this, R.string.select_two_apps, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Collections.disjoint(folderPackages, userHidden)) {
            Toast.makeText(this, R.string.show_selected_before_folder, Toast.LENGTH_SHORT).show();
            return;
        }
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setHint(R.string.folder_name);
        int padding = dp(20);
        name.setPadding(padding, dp(8), padding, dp(8));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.create_folder)
                .setView(name)
                .setPositiveButton(android.R.string.ok, (actionDialog, which) -> {
                    String title = name.getText().toString().trim();
                    if (title.isEmpty()) {
                        Toast.makeText(this, R.string.folder_name_required, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<LauncherFolders.Folder> folders = LauncherFolders.load(this);
                    folders.add(new LauncherFolders.Folder(title, folderPackages));
                    if (!LauncherFolders.save(this, folders)) {
                        Toast.makeText(this, R.string.folder_save_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    List<String> updatedDockApps = DockApps.removeSelected(
                            Config.load(this).dockApps, new HashSet<>(folderPackages));
                    getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                            .putString("dock_apps", join(updatedDockApps))
                            .putString("dock_order", join(updatedDockApps))
                            .putBoolean("apps_changed", true)
                            .apply();
                    selected.clear();
                    changed = true;
                    setResult(RESULT_OK);
                    loadApps();
                    renderRows();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        showAnimatedAlertDialog(dialog);
    }

    private void manageFolders() {
        List<LauncherFolders.Folder> folders = LauncherFolders.load(this);
        if (folders.isEmpty()) {
            Toast.makeText(this, R.string.no_folders, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            names[i] = getString(R.string.folder_summary, folders.get(i).name, folders.get(i).packages.size());
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.manage_folders)
                .setItems(names, (ignored, index) -> {
                    AlertDialog deleteDialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.delete_folder_title)
                        .setMessage(getString(R.string.delete_folder_message, folders.get(index).name))
                        .setPositiveButton(R.string.delete, (confirm, which) -> {
                            folders.remove(index);
                            if (!LauncherFolders.save(this, folders)) {
                                Toast.makeText(this, R.string.folder_save_failed, Toast.LENGTH_LONG).show();
                                return;
                            }
                            changed = true;
                            markHomeChanged();
                            setResult(RESULT_OK);
                            loadApps();
                            renderRows();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
                    showAnimatedAlertDialog(deleteDialog);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        showAnimatedAlertDialog(dialog);
    }

    private void showAnimatedAlertDialog(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null && animations && !reducedMotion) {
            window.setWindowAnimations(R.style.PopupDialogAnimation);
        }
        dialog.show();
    }

    private void uninstallApp(String packageName) {
        uninstallingPackage = packageName;
        Intent uninstall = new Intent(Intent.ACTION_DELETE,
                android.net.Uri.parse("package:" + packageName));
        try {
            startActivity(uninstall);
        } catch (RuntimeException exception) {
            uninstallingPackage = null;
            Toast.makeText(this, R.string.app_info_uninstall_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void setSelectedAppsVisible(boolean visible) {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.select_apps_first, Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> selectedPackages = new ArrayList<>();
        Set<String> installed = new HashSet<>();
        for (AppRow app : apps) {
            installed.add(app.pkg);
            if (selected.contains(app.pkg)) selectedPackages.add(app.pkg);
        }

        Config config = Config.load(this);
        List<String> updatedDockApps;
        Set<String> updatedDockExcludedApps = new HashSet<>(dockExcludedApps);
        if (visible) {
            userHidden.removeAll(selected);
            updatedDockExcludedApps.removeAll(selected);
            Set<String> blockedDockApps = new HashSet<>(userHidden);
            blockedDockApps.addAll(updatedDockExcludedApps);
            updatedDockApps = DockApps.addSelected(config.dockApps, installed,
                    blockedDockApps, selectedPackages, config.dockAppCount);
        } else {
            Set<String> selectedDockApps = new HashSet<>(selected);
            selectedDockApps.retainAll(homeDockApps);
            updatedDockExcludedApps.addAll(selectedDockApps);
            updatedDockApps = DockApps.removeSelected(config.dockApps, selectedDockApps);
        }
        int notAdded = 0;
        if (visible) {
            for (String packageName : selectedPackages) {
                if (!updatedDockApps.contains(packageName)) notAdded++;
            }
        }

        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                .putStringSet("user_hidden_apps", new HashSet<>(userHidden))
                .putStringSet(DockApps.EXCLUDED_APPS_KEY, updatedDockExcludedApps)
                .putString("dock_apps", join(updatedDockApps))
                .putString("dock_order", join(updatedDockApps))
                .putString("app_order", join(order))
                .putBoolean("apps_changed", true)
                .apply();
        dockExcludedApps.clear();
        dockExcludedApps.addAll(updatedDockExcludedApps);
        selected.clear();
        changed = true;
        setResult(RESULT_OK);
        if (notAdded > 0) {
            Toast.makeText(this, R.string.home_dock_full, Toast.LENGTH_LONG).show();
        }
        loadApps();
        renderRows(selectedPackages.isEmpty() ? null : selectedPackages.get(0), 2);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (uninstallingPackage == null) return;

        String packageName = uninstallingPackage;
        uninstallingPackage = null;
        loadApps();
        boolean stillInstalled = false;
        for (AppRow app : apps) {
            if (app.pkg.equals(packageName)) {
                stillInstalled = true;
                break;
            }
        }
        if (!stillInstalled) {
            userHidden.remove(packageName);
            selected.remove(packageName);
            order.remove(packageName);
            saveChoices();
            markHomeChanged();
        }
        renderRows(packageName, 1);
    }

    @Override
    protected void onDestroy() {
        if (focusGlass != null) focusGlass.close();
        super.onDestroy();
    }

    private void saveChoices() {
        changed = true;
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                .putStringSet("user_hidden_apps", new HashSet<>(userHidden))
                .putString("app_order", join(order))
                .putBoolean("apps_changed", true)
                .apply();
        setResult(RESULT_OK);
    }

    private void markHomeChanged() {
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                .putBoolean("apps_changed", true)
                .apply();
    }

    private String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refreshHomeDockApps(Config config) {
        homeDockApps.clear();
        int appSlots = config.dockAppCount;
        if (appSlots == 0) return;
        Set<String> installed = new HashSet<>();
        for (AppRow app : apps) installed.add(app.pkg);
        Set<String> grouped = new HashSet<>();
        Map<String, Set<String>> folderMembers = new LinkedHashMap<>();
        Map<String, String> folderByPackage = new LinkedHashMap<>();
        int folderIndex = 0;
        for (LauncherFolders.Folder folder : LauncherFolders.load(this)) {
            grouped.addAll(folder.packages);
            Set<String> members = new HashSet<>();
            for (String packageName : folder.packages) {
                if (installed.contains(packageName) && !userHidden.contains(packageName)
                        && !config.dockApps.contains(packageName)
                        && !folderByPackage.containsKey(packageName)) {
                    members.add(packageName);
                }
            }
            if (members.size() >= 2) {
                String folderKey = String.valueOf(folderIndex);
                folderMembers.put(folderKey, members);
                for (String packageName : members) {
                    folderByPackage.put(packageName, folderKey);
                    grouped.add(packageName);
                }
            }
            folderIndex++;
        }
        int dockItemCount = 0;
        for (String packageName : config.dockApps) {
            if (dockItemCount >= appSlots) break;
            if (installed.contains(packageName) && !userHidden.contains(packageName)
                    && !dockExcludedApps.contains(packageName) && !grouped.contains(packageName)
                    && homeDockApps.add(packageName)) {
                dockItemCount++;
            }
        }
        if (!config.fillDock) return;
        Set<String> addedFolders = new HashSet<>();
        for (AppRow app : apps) {
            if (dockItemCount >= appSlots) break;
            String folderKey = folderByPackage.get(app.pkg);
            if (folderKey != null) {
                if (addedFolders.add(folderKey) && folderMembers.containsKey(folderKey)) {
                    dockItemCount++;
                }
                continue;
            }
            if (!userHidden.contains(app.pkg) && !dockExcludedApps.contains(app.pkg)
                    && !grouped.contains(app.pkg) && homeDockApps.add(app.pkg)) {
                dockItemCount++;
            }
        }
    }

    @Override
    public void onBackPressed() {
        setResult(changed ? RESULT_OK : RESULT_CANCELED);
        super.onBackPressed();
        if (animations && !reducedMotion) {
            overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pendingInitialFocus != null) {
            View focus = pendingInitialFocus;
            pendingInitialFocus = null;
            focus.post(() -> {
                if (!focus.requestFocus()) pendingInitialFocus = focus;
            });
        }
    }

    private static final class AppRow {
        final String pkg;
        final String label;
        final Drawable icon;
        ComponentName component;

        AppRow(String pkg, String label, ComponentName component, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }
}

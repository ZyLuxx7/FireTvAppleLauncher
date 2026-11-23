package com.appletv.launcher;

import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** TV app grid opened from the fixed Apps tile on Home. */
public final class AllAppsActivity extends LauncherActivity {
    static final String EXTRA_FOCUS_SEARCH = "com.appletv.launcher.extra.FOCUS_APP_SEARCH";
    private final List<AppTile> apps = new ArrayList<>();
    private final List<AppTile> allApps = new ArrayList<>();
    private final List<String> dockApps = new ArrayList<>();
    private AppsAdapter adapter;
    private FocusGlassController focusGlass;
    private FrameLayout root;
    private ImageView backdrop;
    private View backdropShade;
    private TextView drawerTitle;
    private EditText searchField;
    private TextView emptyState;
    private RecyclerView grid;
    private Config config;
    private String wallpaperId;
    private Bitmap fallbackCustomWallpaper;
    private int fallbackBackdropResource;
    private int fallbackBackdropColor;
    private boolean fallbackLightAppearance;
    private boolean backdropInitialized;
    private boolean mediaArtworkBackdrop;
    private String mediaBackdropKey;
    private boolean appearanceApplied;
    private boolean appliedLightAppearance;
    private boolean nowPlayingReceiverRegistered;
    private int columns;
    private boolean lightAppearance;
    private final BroadcastReceiver nowPlayingUpdates = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateAppsBackdrop();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_apps_drawer);
        wallpaperId = HomeWallpaperCatalog.selectedId(this);
        root = findViewById(R.id.appsDrawerRoot);
        backdrop = findViewById(R.id.appsDrawerBackdrop);
        backdropShade = findViewById(R.id.appsDrawerBackdropShade);
        config = Config.load(this);
        prepareFallbackBackdrop();
        focusGlass = new FocusGlassController(this);
        drawerTitle = findViewById(R.id.appsDrawerTitle);
        drawerTitle.setText(R.string.quick_apps);

        dockApps.addAll(config.dockApps);

        searchField = findViewById(R.id.allAppsSearch);
        searchField.setHint(R.string.quick_search_hint);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                filterApps(value.toString());
            }

            @Override
            public void afterTextChanged(Editable value) { }
        });
        grid = findViewById(R.id.allAppsGrid);
        emptyState = findViewById(R.id.allAppsEmpty);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        columns = Math.max(3, Math.min(7, config.columns));
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        adapter = new AppsAdapter();
        grid.setAdapter(adapter);
        loadApps();
        allApps.addAll(apps);
        adapter.notifyDataSetChanged();
        updateEmptyState();
        updateAppsBackdrop();
        if (getIntent().getBooleanExtra(EXTRA_FOCUS_SEARCH, false)) {
            searchField.post(() -> {
                searchField.requestFocus();
                InputMethodManager inputMethodManager = getSystemService(InputMethodManager.class);
                if (inputMethodManager != null) {
                    inputMethodManager.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        } else if (!apps.isEmpty()) {
            focusFirstApp();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nowPlayingUpdates,
                    new IntentFilter(NowPlayingListener.ACTION_UPDATED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(nowPlayingUpdates, new IntentFilter(NowPlayingListener.ACTION_UPDATED));
        }
        nowPlayingReceiverRegistered = true;
        updateAppsBackdrop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAppsBackdrop();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (config.animations) {
            overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
        }
    }

    @Override
    protected void onStop() {
        if (nowPlayingReceiverRegistered) {
            unregisterReceiver(nowPlayingUpdates);
            nowPlayingReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (nowPlayingReceiverRegistered) unregisterReceiver(nowPlayingUpdates);
        if (focusGlass != null) focusGlass.close();
        super.onDestroy();
    }

    private void prepareFallbackBackdrop() {
        fallbackLightAppearance = config.wallpaper == null
                && LauncherUiSettings.isLight(this) && HomeWallpaperCatalog.isBanner(wallpaperId);
        fallbackBackdropColor = fallbackLightAppearance ? 0xFFF2F2F7 : config.backgroundColor;
        if (config.wallpaper != null) {
            fallbackCustomWallpaper = WallpaperSize.decode(
                    config.wallpaper, getResources().getDisplayMetrics());
            if (fallbackCustomWallpaper != null) {
                fallbackLightAppearance = false;
                fallbackBackdropColor = config.backgroundColor;
                return;
            }
            Log.w("AppleTv.AllApps", "Could not decode configured wallpaper: "
                    + config.wallpaper.getPath());
        }
        if (!fallbackLightAppearance) {
            fallbackBackdropResource = HomeWallpaperCatalog.drawable(wallpaperId);
        }
    }

    private void updateAppsBackdrop() {
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        if (snapshot != null && snapshot.active && snapshot.artwork != null) {
            String nextTrackKey = snapshot.packageName + "\n" + snapshot.title + "\n"
                    + snapshot.artist + "\n" + snapshot.spotifyTrackUri + "\n"
                    + snapshot.spotifyArtworkUrl;
            if (!mediaArtworkBackdrop || !nextTrackKey.equals(mediaBackdropKey)) {
                backdrop.setImageBitmap(snapshot.artwork);
            }
            mediaArtworkBackdrop = true;
            mediaBackdropKey = nextTrackKey;
            backdrop.setVisibility(View.VISIBLE);
            backdropShade.setVisibility(View.VISIBLE);
            root.setBackgroundColor(config.backgroundColor);
            lightAppearance = false;
        } else if (mediaArtworkBackdrop || !backdropInitialized) {
            showFallbackBackdrop();
        }
        backdropInitialized = true;
        applyDrawerAppearance();
    }

    private void showFallbackBackdrop() {
        mediaArtworkBackdrop = false;
        mediaBackdropKey = null;
        root.setBackgroundColor(fallbackBackdropColor);
        backdropShade.setVisibility(View.GONE);
        if (fallbackCustomWallpaper != null) {
            backdrop.setImageBitmap(fallbackCustomWallpaper);
            backdrop.setVisibility(View.VISIBLE);
        } else if (fallbackBackdropResource != 0) {
            backdrop.setImageResource(fallbackBackdropResource);
            backdrop.setVisibility(View.VISIBLE);
        } else {
            backdrop.setImageDrawable(null);
            backdrop.setVisibility(View.GONE);
        }
        lightAppearance = fallbackLightAppearance;
    }

    private void applyDrawerAppearance() {
        if (appearanceApplied && appliedLightAppearance == lightAppearance) return;
        appearanceApplied = true;
        appliedLightAppearance = lightAppearance;
        drawerTitle.setTextColor(lightAppearance
                ? 0xFF1C1C1E : getColor(R.color.tvos_label_primary));
        searchField.setTextColor(getColorStateList(lightAppearance
                ? R.color.quick_search_text_light : R.color.quick_search_text_dark));
        searchField.setHintTextColor(getColorStateList(lightAppearance
                ? R.color.quick_search_hint_light : R.color.quick_search_hint_dark));
        searchField.setBackgroundResource(lightAppearance
                ? R.drawable.input_row_bg_light : R.drawable.input_row_bg);
        emptyState.setTextColor(lightAppearance
                ? 0xFF6E6E73 : getColor(R.color.tvos_label_secondary));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Set<String> fixedHidden = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        Set<String> userHidden = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getStringSet("user_hidden_apps", Collections.emptySet());
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        addCandidates(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, candidates, fixedHidden, userHidden);
        addCandidates(pm, Intent.CATEGORY_LAUNCHER, false, candidates, fixedHidden, userHidden);

        for (String pkg : dockApps) {
            if (candidates.containsKey(pkg) || fixedHidden.contains(pkg) || userHidden.contains(pkg)) continue;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            boolean leanback = launch != null;
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null || launch.getComponent() == null) continue;
            ResolveInfo info = pm.resolveActivity(launch, 0);
            if (info != null && info.activityInfo != null && pkg.equals(info.activityInfo.packageName)) {
                candidates.put(pkg, new Candidate(info, leanback));
            }
        }

        for (Map.Entry<String, Candidate> candidate : candidates.entrySet()) {
            ResolveInfo info = candidate.getValue().info;
            Drawable banner = info.activityInfo.loadBanner(pm);
            apps.add(new AppTile(candidate.getKey(), String.valueOf(info.loadLabel(pm)),
                    new ComponentName(candidate.getKey(), info.activityInfo.name),
                    banner == null ? info.loadIcon(pm) : banner, banner != null,
                    candidate.getValue().leanback));
        }
        String savedOrder = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString("app_order", "");
        List<String> appOrder = savedOrder == null || savedOrder.isEmpty()
                ? Collections.emptyList() : Arrays.asList(savedOrder.split(","));
        apps.sort((left, right) -> {
            int leftDock = dockApps.indexOf(left.pkg);
            int rightDock = dockApps.indexOf(right.pkg);
            if (leftDock >= 0 || rightDock >= 0) {
                if (leftDock < 0) return 1;
                if (rightDock < 0) return -1;
                return Integer.compare(leftDock, rightDock);
            }
            int leftOrder = appOrder.indexOf(left.pkg);
            int rightOrder = appOrder.indexOf(right.pkg);
            if (leftOrder >= 0 || rightOrder >= 0) {
                if (leftOrder < 0) return 1;
                if (rightOrder < 0) return -1;
                if (leftOrder != rightOrder) return Integer.compare(leftOrder, rightOrder);
            }
            return left.label.compareToIgnoreCase(right.label);
        });
    }

    private void filterApps(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        apps.clear();
        for (AppTile app : allApps) {
            if (normalized.isEmpty()
                    || app.label.toLowerCase(Locale.ROOT).contains(normalized)
                    || app.pkg.toLowerCase(Locale.ROOT).contains(normalized)) {
                apps.add(app);
            }
        }
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = apps.isEmpty();
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyState.setText(allApps.isEmpty()
                    ? R.string.no_apps_to_manage : R.string.quick_search_no_results);
        }
    }

    private void focusFirstApp() {
        grid.post(() -> {
            RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
            if (first != null) first.itemView.findViewById(R.id.card).requestFocus();
        });
    }

    private void addCandidates(PackageManager pm, String category, boolean leanback,
                               Map<String, Candidate> candidates, Set<String> fixedHidden,
                               Set<String> userHidden) {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo info : pm.queryIntentActivities(probe, 0)) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || fixedHidden.contains(pkg) || userHidden.contains(pkg)) continue;
            Candidate existing = candidates.get(pkg);
            if (existing == null || (leanback && !existing.leanback)) {
                candidates.put(pkg, new Candidate(info, leanback));
            }
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class AppsAdapter extends RecyclerView.Adapter<AppHolder> {
        AppsAdapter() {
            setHasStableIds(true);
        }

        @NonNull
        @Override
        public AppHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View item = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            float density = getResources().getDisplayMetrics().density;
            int innerWidth = getResources().getDisplayMetrics().widthPixels - dp(108);
            int width = AppAdapter.tileWidthPx(innerWidth, columns, density);
            int height = AppAdapter.tileHeightPx(width);

            View card = item.findViewById(R.id.card);
            ViewGroup.LayoutParams cardParams = card.getLayoutParams();
            cardParams.width = width;
            cardParams.height = height;
            card.setLayoutParams(cardParams);
            TextView name = item.findViewById(R.id.name);
            ViewGroup.MarginLayoutParams nameParams = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
            nameParams.width = width;
            nameParams.topMargin = height + dp(7);
            name.setLayoutParams(nameParams);
            return new AppHolder(item);
        }

        @Override
        public void onBindViewHolder(@NonNull AppHolder holder, int position) {
            AppTile app = apps.get(position);
            holder.name.setText(app.label);
            holder.name.setTextColor(lightAppearance ? 0xFF1C1C1E : getColor(R.color.tvos_label_primary));
            holder.name.setVisibility(View.VISIBLE);
            holder.name.setAlpha(1f);
            holder.banner.setVisibility(app.isBanner ? View.VISIBLE : View.GONE);
            holder.banner.setImageDrawable(app.isBanner ? app.artwork : null);
            holder.icon.setVisibility(app.isBanner ? View.GONE : View.VISIBLE);
            holder.icon.setImageDrawable(app.isBanner ? null : app.artwork);
            holder.icon.setPadding(app.isBanner ? 0 : dp(12), app.isBanner ? 0 : dp(12),
                    app.isBanner ? 0 : dp(12), app.isBanner ? 0 : dp(12));
            holder.card.setContentDescription(app.label);
            holder.card.setOnClickListener(v -> launch(app));
            holder.card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                view.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                        .translationZ(focused ? dp(8) : dp(2)).setDuration(150).start();
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        @Override
        public long getItemId(int position) {
            return apps.get(position).pkg.hashCode();
        }
    }

    private void launch(AppTile app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(app.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
        }
    }

    private final class AppHolder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView name;

        AppHolder(View item) {
            super(item);
            card = item.findViewById(R.id.card);
            banner = item.findViewById(R.id.banner);
            icon = item.findViewById(R.id.icon);
            name = item.findViewById(R.id.name);
            card.setFocusable(true);
            card.setClickable(true);
            float density = getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                            AppAdapter.cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);
        }
    }

    private static final class Candidate {
        final ResolveInfo info;
        final boolean leanback;

        Candidate(ResolveInfo info, boolean leanback) {
            this.info = info;
            this.leanback = leanback;
        }
    }

    private static final class AppTile {
        final String pkg;
        final String label;
        final ComponentName component;
        final Drawable artwork;
        final boolean isBanner;
        final boolean leanback;

        AppTile(String pkg, String label, ComponentName component, Drawable artwork,
                boolean isBanner, boolean leanback) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.artwork = artwork;
            this.isBanner = isBanner;
            this.leanback = leanback;
        }
    }
}

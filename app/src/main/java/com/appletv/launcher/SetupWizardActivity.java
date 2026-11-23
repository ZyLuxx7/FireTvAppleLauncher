package com.appletv.launcher;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

public final class SetupWizardActivity extends LauncherActivity {
    private static final String TAG = "AppleTv.Setup";
    private static final int LOCATION_REQUEST = 88;
    private static final int SHOW_TV_KEYBOARD_EXPLICIT = 0;
    private static final int SPOTIFY_REQUEST = 89;
    private static final String[] AVATARS = {"😀", "😎", "😈", "🥳", "🦊", "🐼", "🐯", "🦁"};
    private static final int PAGE_COUNT = 8;
    private static final int[] PAGE_TITLES = {
            R.string.setup_welcome_title, R.string.setup_profile_title,
            R.string.setup_appearance_title, R.string.setup_weather_title,
            R.string.setup_dock_title, R.string.setup_spotify_title,
            R.string.setup_finish_title, R.string.setup_mapper_title
    };
    private static final int[] PAGE_SUBTITLES = {
            R.string.setup_welcome_subtitle, R.string.setup_profile_subtitle,
            R.string.setup_appearance_subtitle, R.string.setup_weather_subtitle,
            R.string.setup_dock_subtitle, R.string.setup_spotify_subtitle,
            R.string.setup_finish_subtitle, R.string.setup_mapper_subtitle
    };

    private static final class DockAppChoice {
        final String packageName;
        final String label;
        final Drawable icon;

        DockAppChoice(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private SharedPreferences preferences;
    private LinearLayout options;
    private TextView title;
    private TextView subtitle;
    private TextView progress;
    private TextView backButton;
    private TextView nextButton;
    private LinearLayout pageIndicator;
    private ImageView background;
    private View backgroundShade;
    private LinearLayout avatarRow;
    private LinearLayout dockCountRow;
    private TextView dockCountStatus;
    private final List<DockAppChoice> dockAppChoices = new ArrayList<>();
    private final Map<String, LinearLayout> dockAppRows = new LinkedHashMap<>();
    private final List<TextView> dockCountChoices = new ArrayList<>();
    private final Set<String> selectedDockApps = new LinkedHashSet<>();
    private EditText profileNameInput;
    private int page;
    private int dockAppCount;
    private int selectedAvatarIndex;
    private String selectedLanguage;
    private String selectedWallpaper;
    private String selectedAppearance;
    private String selectedWeatherCity;
    private boolean useCurrentLocation;
    private boolean showWeather;
    private boolean easySetupSelected;
    private boolean initialFocusSet;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_setup_wizard);
        preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        page = state == null ? 0 : state.getInt("page", 0);
        page = Math.max(0, Math.min(PAGE_COUNT - 1, page));
        selectedLanguage = preferences.getString("language", "");
        selectedWallpaper = HomeWallpaperCatalog.selectedId(this);
        selectedAppearance = preferences.getString("appearance", "dark");
        if (!"light".equals(selectedAppearance) && !"dark".equals(selectedAppearance)) {
            selectedAppearance = LauncherUiSettings.isLight(this) ? "light" : "dark";
        }
        dockAppCount = Math.max(Config.MIN_DOCK_APP_COUNT, Math.min(Config.MAX_DOCK_APP_COUNT,
                preferences.getInt("dock_app_count", config.dockAppCount)));
        String savedDockApps = preferences.getString("dock_apps", "");
        if (!savedDockApps.isEmpty()) {
            selectedDockApps.addAll(Arrays.asList(savedDockApps.split(",")));
        }
        if (selectedDockApps.size() > dockAppCount) {
            List<String> trimmed = new ArrayList<>(selectedDockApps)
                    .subList(0, dockAppCount);
            selectedDockApps.clear();
            selectedDockApps.addAll(trimmed);
        }
        selectedWeatherCity = preferences.getString("weather_city", "");
        useCurrentLocation = preferences.getBoolean("weather_use_location", false);
        showWeather = preferences.getBoolean("weather_enabled", false);
        easySetupSelected = preferences.getBoolean("setup_easy_mode", true);
        selectedAvatarIndex = Math.max(0, Math.min(AVATARS.length - 1,
                preferences.getInt("profile_avatar", 0)));
        options = findViewById(R.id.setupOptions);
        title = findViewById(R.id.setupTitle);
        subtitle = findViewById(R.id.setupSubtitle);
        progress = findViewById(R.id.setupProgress);
        backButton = findViewById(R.id.setupBack);
        nextButton = findViewById(R.id.setupNext);
        pageIndicator = findViewById(R.id.setupPageIndicator);
        background = findViewById(R.id.setupBackground);
        backgroundShade = findViewById(R.id.setupBackgroundShade);
        populatePageIndicator();

        findViewById(R.id.setupSkip).setOnClickListener(view -> finishSetup());
        backButton.setOnClickListener(view -> {
            if (page > 0) {
                page--;
                renderPage();
            }
        });
        nextButton.setOnClickListener(view -> advancePage());
        renderPage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (page == 5 && options != null) renderPage();
        requestInitialFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !initialFocusSet) {
            initialFocusSet = true;
            requestInitialFocus();
        }
    }

    private void requestInitialFocus() {
        if (options == null) return;
        options.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            View first = findFirstFocusable(options);
            if (first == null) first = page == 0 ? findViewById(R.id.setupSkip) : nextButton;
            if (!first.hasFocus()) {
                first.setFocusableInTouchMode(true);
                first.requestFocus();
            }
        });
    }

    private boolean isWifiEnabled() {
        android.net.wifi.WifiManager wifi = getSystemService(android.net.wifi.WifiManager.class);
        if (wifi == null) return false;
        try {
            return wifi.isWifiEnabled();
        } catch (SecurityException exception) {
            Log.e(TAG, "Unable to inspect Wi-Fi state during setup", exception);
            return false;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("page", page);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (page > 0) {
            page--;
            renderPage();
        } else {
            finishSetup();
        }
    }

    private void populatePageIndicator() {
        pageIndicator.removeAllViews();
        for (int i = 0; i < PAGE_COUNT; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(6), dp(6));
            params.leftMargin = dp(4);
            params.rightMargin = dp(4);
            dot.setLayoutParams(params);
            dot.setBackground(createDotDrawable(i == page));
            pageIndicator.addView(dot);
        }
    }

    private GradientDrawable createDotDrawable(boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(selected ? Color.WHITE : 0x66777777);
        return drawable;
    }

    private void renderPage() {
        title.setText(PAGE_TITLES[page]);
        subtitle.setText(PAGE_SUBTITLES[page]);
        progress.setText(getString(R.string.setup_progress, page + 1, PAGE_COUNT));
        findViewById(R.id.setupActions).setVisibility(page == 0 ? View.GONE : View.VISIBLE);
        backButton.setVisibility(View.GONE);
        nextButton.setText(page == PAGE_COUNT - 1 ? R.string.setup_finish
                : page == 3 ? R.string.setup_weather_next : R.string.setup_next);
        findViewById(R.id.setupBrand).setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        background.setVisibility(page >= 2 ? View.VISIBLE : View.GONE);
        backgroundShade.setVisibility(page >= 2 ? View.VISIBLE : View.GONE);
        background.setImageResource(R.drawable.home_wallpaper);
        populatePageIndicator();
        options.removeAllViews();
        profileNameInput = null;
        avatarRow = null;

        switch (page) {
            case 0:
                renderWelcomePage();
                break;
            case 1:
                renderProfilePage();
                break;
            case 2:
                renderAppearancePage();
                break;
            case 3:
                renderWeatherPage();
                break;
            case 4:
                renderDockPage();
                break;
            case 5:
                renderSpotifyPage();
                break;
            case 6:
                renderFinishPage();
                break;
            case 7:
                renderButtonMapperPage();
                break;
            default:
                throw new IllegalStateException("Unknown setup page: " + page);
        }
        requestInitialFocus();
    }

    private View findFirstFocusable(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.isFocusable()) return child;
            if (child instanceof ViewGroup) {
                View nested = findFirstFocusable((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private void renderWelcomePage() {
        addOption(R.string.setup_get_started, 0, false, () -> {
            page = 1;
            renderPage();
        });
        String systemLanguage = Locale.getDefault().getLanguage();
        addLanguageOption(R.string.setup_language_english, R.string.setup_language_english_hint,
                "en", systemLanguage);
        addLanguageOption(R.string.setup_language_german, R.string.setup_language_german_hint,
                "de", systemLanguage);
        addLanguageOption(R.string.setup_language_french, R.string.setup_language_french_hint,
                "fr", systemLanguage);
        addLanguageOption(R.string.setup_language_spanish, R.string.setup_language_spanish_hint,
                "es", systemLanguage);
    }

    private void addLanguageOption(int titleRes, int summaryRes, String language,
                                   String systemLanguage) {
        boolean selected = language.equals(selectedLanguage)
                || selectedLanguage.isEmpty() && language.equals(systemLanguage);
        addOption(titleRes, getString(summaryRes), selected,
                () -> selectLanguage(language), false);
    }

    private void selectLanguage(String language) {
        selectedLanguage = language;
        preferences.edit().putString("language", language).apply();
        recreate();
    }

    private void renderProfilePage() {
        LinearLayout card = createCard();
        TextView label = new TextView(this);
        label.setText(R.string.setup_profile_name);
        label.setTextColor(Color.BLACK);
        label.setTextSize(16);
        card.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        profileNameInput = new EditText(this);
        profileNameInput.setSingleLine(true);
        profileNameInput.setFocusableInTouchMode(true);
        profileNameInput.setShowSoftInputOnFocus(true);
        profileNameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        profileNameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        profileNameInput.setHint(R.string.setup_profile_hint);
        profileNameInput.setText(preferences.getString("account_name", ""));
        profileNameInput.setTextColor(Color.BLACK);
        profileNameInput.setHintTextColor(0xFF77777D);
        profileNameInput.setTextSize(16);
        profileNameInput.setBackgroundResource(R.drawable.setup_input_bg);
        profileNameInput.setPadding(dp(12), dp(6), dp(12), dp(6));
        profileNameInput.setOnClickListener(view -> showProfileKeyboard());
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        inputParams.topMargin = dp(12);
        card.addView(profileNameInput, inputParams);
        addCard(card);

        avatarRow = new LinearLayout(this);
        avatarRow.setGravity(Gravity.CENTER);
        avatarRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        avatarParams.topMargin = dp(10);
        options.addView(avatarRow, avatarParams);
        for (int i = 0; i < AVATARS.length; i++) {
            final int index = i;
            TextView avatar = new TextView(this);
            avatar.setText(AVATARS[i]);
            avatar.setTextSize(22);
            avatar.setGravity(Gravity.CENTER);
            avatar.setFocusable(true);
            avatar.setClickable(true);
            avatar.setBackground(createAvatarBackground(i == selectedAvatarIndex));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(42), dp(42));
            params.leftMargin = dp(3);
            params.rightMargin = dp(3);
            avatarRow.addView(avatar, params);
            avatar.setOnClickListener(view -> {
                selectedAvatarIndex = index;
                updateAvatarSelection();
            });
        }
    }

    private GradientDrawable createAvatarBackground(boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(selected ? 0xFF3A3A3C : 0xFF242426);
        if (selected) background.setStroke(dp(2), 0xFFFFFFFF);
        return background;
    }

    private void updateAvatarSelection() {
        if (avatarRow == null) return;
        for (int i = 0; i < avatarRow.getChildCount(); i++) {
            avatarRow.getChildAt(i).setBackground(createAvatarBackground(i == selectedAvatarIndex));
        }
    }

    private void showProfileKeyboard() {
        if (profileNameInput == null) return;
        profileNameInput.requestFocusFromTouch();
        InputMethodManager inputMethodManager = getSystemService(InputMethodManager.class);
        if (inputMethodManager == null) {
            Log.e(TAG, "Input method service is unavailable for the setup name field");
            Toast.makeText(this, R.string.setup_keyboard_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        profileNameInput.post(() -> {
            if (isFinishing() || isDestroyed() || profileNameInput == null) return;
            if (!inputMethodManager.showSoftInput(profileNameInput,
                    SHOW_TV_KEYBOARD_EXPLICIT)) {
                Log.w(TAG, "The TV input method did not open for the setup name field");
                Toast.makeText(this, R.string.setup_keyboard_unavailable, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void renderAppearancePage() {
        addOption(R.string.setup_appearance_dark, R.string.setup_appearance_dark_hint,
                "dark".equals(selectedAppearance),
                () -> selectAppearance("dark"));
        addOption(R.string.setup_appearance_light, R.string.setup_appearance_light_hint,
                "light".equals(selectedAppearance),
                () -> selectAppearance("light"));
        addOption(R.string.setup_appearance_night, getString(R.string.setup_appearance_night_hint),
                !HomeWallpaperCatalog.BANNER.equals(selectedWallpaper),
                () -> selectWallpaper(HomeWallpaperCatalog.SCENIC));
        addOption(R.string.setup_appearance_classic, getString(R.string.setup_appearance_classic_hint),
                HomeWallpaperCatalog.BANNER.equals(selectedWallpaper),
                () -> selectWallpaper(HomeWallpaperCatalog.BANNER));
    }

    private void selectAppearance(String appearance) {
        selectedAppearance = appearance;
        renderPage();
    }

    private void selectWallpaper(String wallpaper) {
        selectedWallpaper = wallpaper;
        renderPage();
    }

    private void renderWeatherPage() {
        String locationSummary = hasLocationPermission()
                ? getString(R.string.setup_weather_location_ready)
                : getString(R.string.setup_weather_location_hint);
        addOption(R.string.setup_weather_use_location, locationSummary,
                showWeather && useCurrentLocation, this::useCurrentLocation);
        String citySummary = selectedWeatherCity.isEmpty()
                ? getString(R.string.setup_weather_city_empty) : selectedWeatherCity;
        addOption(R.string.setup_weather_choose_city, citySummary,
                showWeather && !useCurrentLocation && !selectedWeatherCity.isEmpty(),
                this::showWeatherCityPicker);
        addOption(R.string.setup_weather_dont_show, R.string.setup_weather_dont_show_hint,
                !showWeather, () -> {
                    showWeather = false;
                    useCurrentLocation = false;
                    selectedWeatherCity = "";
                    saveWeatherPreferences();
                    renderPage();
                });
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void useCurrentLocation() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_REQUEST);
            return;
        }
        showWeather = true;
        useCurrentLocation = true;
        selectedWeatherCity = "";
        saveWeatherPreferences();
        renderPage();
    }

    private void showWeatherCityPicker() {
        String[] cities = getResources().getStringArray(R.array.setup_weather_cities);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.setup_weather_choose_city)
                .setItems(cities, (dialog, which) -> {
                    selectedWeatherCity = cities[which];
                    useCurrentLocation = false;
                    showWeather = true;
                    saveWeatherPreferences();
                    renderPage();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveWeatherPreferences() {
        preferences.edit().putBoolean("weather_enabled", showWeather)
                .putBoolean("weather_use_location", useCurrentLocation)
                .putString("weather_city", selectedWeatherCity).apply();
    }

    private void renderDockPage() {
        addSectionLabel(R.string.setup_dock_count_label);
        dockCountStatus = new TextView(this);
        dockCountStatus.setTextColor(0xFFB8B8BE);
        dockCountStatus.setTextSize(14);
        LinearLayout.LayoutParams countStatusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countStatusParams.topMargin = dp(6);
        options.addView(dockCountStatus, countStatusParams);

        dockCountRow = new LinearLayout(this);
        dockCountRow.setOrientation(LinearLayout.HORIZONTAL);
        dockCountRow.setClipChildren(false);
        dockCountRow.setClipToPadding(false);
        LinearLayout.LayoutParams countRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        countRowParams.topMargin = dp(8);
        options.addView(dockCountRow, countRowParams);
        dockCountChoices.clear();
        for (int count = Config.MIN_DOCK_APP_COUNT; count <= Config.MAX_DOCK_APP_COUNT; count++) {
            final int choice = count;
            TextView button = new TextView(this);
            button.setText(String.valueOf(count));
            button.setGravity(Gravity.CENTER);
            button.setTextSize(16);
            button.setFocusable(true);
            button.setClickable(true);
            button.setBackgroundResource(R.drawable.setup_option_bg);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (count > Config.MIN_DOCK_APP_COUNT) params.leftMargin = dp(6);
            dockCountRow.addView(button, params);
            dockCountChoices.add(button);
            button.setOnClickListener(view -> selectDockAppCount(choice));
            button.setOnFocusChangeListener((view, hasFocus) ->
                    updateDockCountButton(button, choice, hasFocus));
            updateDockCountButton(button, choice, false);
        }

        addSectionLabel(R.string.setup_dock_apps_label);
        loadDockAppChoices();
        dockAppRows.clear();
        Set<String> available = new HashSet<>();
        for (DockAppChoice choice : dockAppChoices) available.add(choice.packageName);
        selectedDockApps.retainAll(available);
        if (dockAppChoices.isEmpty()) {
            addInfoCard(R.string.setup_dock_no_apps);
        } else {
            for (DockAppChoice choice : dockAppChoices) addDockAppRow(choice);
        }
        updateDockCountStatus();
    }

    private void addSectionLabel(int textResource) {
        TextView label = new TextView(this);
        label.setText(textResource);
        label.setTextColor(0xFFB8B8BE);
        label.setTextSize(14);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        options.addView(label, params);
    }

    private void loadDockAppChoices() {
        dockAppChoices.clear();
        PackageManager packageManager = getPackageManager();
        Config config = Config.load(this);
        Intent launchable = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, DockAppChoice> unique = new LinkedHashMap<>();
        Set<String> hiddenPackages = new HashSet<>(config.hiddenApps);
        for (ResolveInfo resolveInfo : packageManager.queryIntentActivities(launchable, 0)) {
            if (resolveInfo.activityInfo == null) continue;
            String packageName = resolveInfo.activityInfo.packageName;
            if (packageName.equals(getPackageName()) || hiddenPackages.contains(packageName)
                    || unique.containsKey(packageName)) continue;
            unique.put(packageName, new DockAppChoice(packageName,
                    String.valueOf(resolveInfo.loadLabel(packageManager)),
                    resolveInfo.loadIcon(packageManager)));
        }
        for (String packageName : config.dockApps) {
            if (hiddenPackages.contains(packageName) || unique.containsKey(packageName)) continue;
            Intent appLaunch = packageManager.getLeanbackLaunchIntentForPackage(packageName);
            if (appLaunch == null) appLaunch = packageManager.getLaunchIntentForPackage(packageName);
            if (appLaunch == null) continue;
            ResolveInfo resolveInfo = packageManager.resolveActivity(appLaunch, 0);
            if (resolveInfo == null || resolveInfo.activityInfo == null
                    || !packageName.equals(resolveInfo.activityInfo.packageName)) continue;
            String label = "com.amazon.avod".equals(packageName)
                    ? getString(R.string.prime_video) : String.valueOf(resolveInfo.loadLabel(packageManager));
            unique.put(packageName, new DockAppChoice(packageName, label,
                    resolveInfo.loadIcon(packageManager)));
        }
        dockAppChoices.addAll(unique.values());
        dockAppChoices.sort(Comparator.comparing(choice -> choice.label.toLowerCase(Locale.ROOT)));
    }

    private void addDockAppRow(DockAppChoice choice) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(8), dp(12), dp(8));
        row.setBackgroundResource(R.drawable.setup_option_bg);
        row.setFocusable(true);
        row.setClickable(true);
        row.setMinimumHeight(dp(56));
        row.setSelected(selectedDockApps.contains(choice.packageName));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(choice.icon);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));

        TextView label = new TextView(this);
        label.setText(choice.label);
        label.setTextSize(16);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.leftMargin = dp(12);
        row.addView(label, labelParams);

        TextView check = new TextView(this);
        check.setText("\u2713");
        check.setTextColor(0xFF30D158);
        check.setTextSize(20);
        check.setGravity(Gravity.CENTER);
        row.addView(check, new LinearLayout.LayoutParams(dp(28), dp(32)));
        updateDockAppRow(row, label, check, selectedDockApps.contains(choice.packageName), false);

        row.setOnFocusChangeListener((view, hasFocus) ->
                updateDockAppRow(row, label, check,
                        selectedDockApps.contains(choice.packageName), hasFocus));
        row.setOnClickListener(view -> toggleDockApp(choice.packageName));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(6);
        options.addView(row, rowParams);
        dockAppRows.put(choice.packageName, row);
    }

    private void updateDockAppRow(LinearLayout row, TextView label, TextView check,
                                  boolean selected, boolean focused) {
        row.setSelected(selected);
        label.setTextColor(selected || focused ? Color.BLACK : Color.WHITE);
        check.setVisibility(selected ? View.VISIBLE : View.GONE);
    }

    private void toggleDockApp(String packageName) {
        if (selectedDockApps.contains(packageName)) {
            selectedDockApps.remove(packageName);
        } else if (selectedDockApps.size() >= dockAppCount) {
            Toast.makeText(this, getString(R.string.setup_dock_limit, dockAppCount),
                    Toast.LENGTH_SHORT).show();
            return;
        } else {
            selectedDockApps.add(packageName);
        }
        updateDockAppRows();
        updateDockCountStatus();
    }

    private void updateDockAppRows() {
        for (DockAppChoice choice : dockAppChoices) {
            LinearLayout row = dockAppRows.get(choice.packageName);
            if (row == null) continue;
            TextView label = (TextView) row.getChildAt(1);
            TextView check = (TextView) row.getChildAt(2);
            boolean selected = selectedDockApps.contains(choice.packageName);
            updateDockAppRow(row, label, check, selected, row.hasFocus());
        }
    }

    private void selectDockAppCount(int count) {
        dockAppCount = count;
        if (selectedDockApps.size() > count) {
            Set<String> trimmed = new LinkedHashSet<>();
            for (String packageName : selectedDockApps) {
                if (trimmed.size() >= count) break;
                trimmed.add(packageName);
            }
            selectedDockApps.clear();
            selectedDockApps.addAll(trimmed);
        }
        for (int i = 0; i < dockCountChoices.size(); i++) {
            int choice = Config.MIN_DOCK_APP_COUNT + i;
            updateDockCountButton(dockCountChoices.get(i), choice,
                    dockCountChoices.get(i).hasFocus());
        }
        updateDockAppRows();
        updateDockCountStatus();
    }

    private void updateDockCountButton(TextView button, int count, boolean focused) {
        boolean selected = count == dockAppCount;
        button.setSelected(selected);
        button.setTextColor(selected || focused ? Color.BLACK : Color.WHITE);
    }

    private void updateDockCountStatus() {
        if (dockCountStatus != null) {
            dockCountStatus.setText(getString(R.string.setup_dock_count_status,
                    dockAppCount, selectedDockApps.size()));
        }
    }

    private void saveDockPreferences() {
        StringBuilder packages = new StringBuilder();
        for (String packageName : selectedDockApps) {
            if (packages.length() > 0) packages.append(',');
            packages.append(packageName);
        }
        Set<String> excluded = new HashSet<>(preferences.getStringSet(
                DockApps.EXCLUDED_APPS_KEY, new HashSet<>()));
        excluded.removeAll(selectedDockApps);
        preferences.edit().putInt("dock_app_count", dockAppCount)
                .putBoolean("fill_dock", false)
                .putString("dock_apps", packages.toString())
                .putString("dock_order", packages.toString())
                .putStringSet(DockApps.EXCLUDED_APPS_KEY, excluded)
                .putBoolean("apps_changed", true).apply();
    }

    private void renderSpotifyPage() {
        boolean connected = isSpotifyConnected();
        addInfoCard(getString(R.string.setup_spotify_status,
                getString(connected ? R.string.setup_spotify_connected
                        : R.string.setup_spotify_disconnected)));
        addOption(R.string.setup_spotify_connect, connected
                        ? R.string.setup_spotify_connected_hint : R.string.setup_spotify_hint,
                connected, () -> startActivityForResult(
                        new Intent(this, SpotifyConnectActivity.class), SPOTIFY_REQUEST));
        addInfoCard(R.string.setup_spotify_player_hint);
    }

    private boolean isSpotifyConnected() {
        String clientId = preferences.getString(SpotifyRemotePlayback.CLIENT_ID_KEY, "").trim();
        if (clientId.isEmpty()) return false;
        try {
            return new SpotifyTokenStore(this).loadToken(clientId) != null;
        } catch (GeneralSecurityException | JSONException | IOException exception) {
            Log.e(TAG, "Unable to check Spotify authorization during setup", exception);
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            showWeather = true;
            useCurrentLocation = true;
            selectedWeatherCity = "";
            saveWeatherPreferences();
            renderPage();
        } else {
            Toast.makeText(this, R.string.setup_weather_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void renderFinishPage() {
        addOption(R.string.setup_easy_setup, R.string.setup_easy_setup_hint, easySetupSelected, () -> {
            easySetupSelected = true;
            preferences.edit().putBoolean("setup_easy_mode", true).apply();
            renderPage();
            showAdbSetupInfo();
        });
        addOption(R.string.setup_manual_setup, R.string.setup_manual_setup_hint, !easySetupSelected, () -> {
            easySetupSelected = false;
            preferences.edit().putBoolean("setup_easy_mode", false).apply();
            openSystemSettings(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        addStatusRow(R.string.setup_permission_home,
                getCurrentHomePackage().equals(getPackageName()) || isHomeRedirectEnabled());
        addStatusRow(R.string.setup_permission_button_mapper,
                isAccessibilityServiceEnabled(new ComponentName(
                        "de.gabri.firetvhomehelper",
                        "de.gabri.firetvhomehelper.AmazonHomeAccessibilityService")));
        addStatusRow(R.string.setup_permission_power,
                isAccessibilityServiceEnabled(new ComponentName(this, TvPowerService.class)));
        addStatusRow(R.string.setup_permission_continue_watching,
                WatchNextRepository.hasReadPermission(this));
        addStatusRow(R.string.setup_permission_wifi, isWifiEnabled());
    }

    private void renderButtonMapperPage() {
        addOption(R.string.setup_mapper_add, R.string.setup_mapper_add_hint,
                false, () -> openSystemSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addInfoCard(getString(R.string.setup_mapper_home_status,
                isButtonMapperEnabled() ? getString(R.string.setup_status_enabled)
                        : getString(R.string.setup_status_disabled)));
        addInfoCard(R.string.setup_mapper_details);
        addOption(R.string.setup_open_accessibility, R.string.setup_mapper_accessibility_hint,
                isButtonMapperEnabled(),
                () -> openSystemSettings(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }

    private boolean isButtonMapperEnabled() {
        return isHomeKeyServiceEnabled() || isHomeRedirectEnabled();
    }

    private void addStatusRow(int titleRes, boolean enabled) {
        addInfoCard(getString(R.string.setup_permission_status, getString(titleRes),
                getString(enabled ? R.string.setup_status_enabled : R.string.setup_status_disabled)));
    }

    private void showAdbSetupInfo() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.setup_easy_setup)
                .setMessage(R.string.setup_easy_setup_adb_unavailable)
                .setPositiveButton(R.string.setup_open_accessibility,
                        (dialog, which) -> openSystemSettings(
                                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addOption(int titleRes, int summaryRes, boolean selected, Runnable action) {
        addOption(titleRes, summaryRes == 0 ? null : getString(summaryRes), selected, action);
    }

    private void addOption(int titleRes, String summary, boolean selected, Runnable action) {
        addOption(titleRes, summary, selected, action, true);
    }

    private void addOption(int titleRes, String summary, boolean selected, Runnable action,
                           boolean selectedBackground) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(12));
        card.setBackgroundResource(R.drawable.setup_option_bg);
        card.setFocusable(true);
        card.setClickable(true);
        card.setMinimumHeight(dp(54));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(titleRes);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(19);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textBlock.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView summaryView = new TextView(this);
        if (summary == null || summary.isEmpty()) {
            summaryView.setVisibility(View.GONE);
        } else {
            summaryView.setText(summary);
        }
        summaryView.setTextColor(0xFFB8B8BE);
        summaryView.setTextSize(14);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(4);
        textBlock.addView(summaryView, summaryParams);
        card.addView(textBlock, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView check = new TextView(this);
        check.setText("\u2713");
        check.setTextColor(0xFF30D158);
        check.setTextSize(20);
        check.setGravity(Gravity.CENTER);
        check.setVisibility(selected ? View.VISIBLE : View.GONE);
        card.addView(check, new LinearLayout.LayoutParams(dp(28), dp(32)));
        card.setSelected(selected && selectedBackground);
        boolean selectedHighlight = selected && selectedBackground;
        titleView.setTextColor(selectedHighlight ? Color.BLACK : Color.WHITE);
        summaryView.setTextColor(selectedHighlight ? 0xFF303036 : 0xFFB8B8BE);
        card.setOnFocusChangeListener((view, hasFocus) -> {
            boolean highlighted = hasFocus || selectedHighlight;
            titleView.setTextColor(highlighted ? Color.BLACK : Color.WHITE);
            summaryView.setTextColor(highlighted ? 0xFF303036 : 0xFFB8B8BE);
        });
        card.setOnClickListener(view -> action.run());
        addCard(card);
    }

    private void addInfoCard(int messageRes) {
        addInfoCard(getString(messageRes));
    }

    private void addInfoCard(String message) {
        TextView info = new TextView(this);
        info.setText(message);
        info.setTextColor(0xFFE0E0E5);
        info.setTextSize(16);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(18), dp(14), dp(18), dp(14));
        info.setBackgroundResource(R.drawable.quick_glass_panel);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        options.addView(info, params);
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(12), dp(18), dp(12));
        card.setBackgroundResource(R.drawable.quick_control_button_bg);
        card.setFocusable(false);
        card.setClickable(false);
        card.setMinimumHeight(dp(70));
        return card;
    }

    private void addCard(View card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        options.addView(card, params);
    }

    private void advancePage() {
        if (page == PAGE_COUNT - 1) {
            finishSetup();
            return;
        }
        saveCurrentPage();
        page++;
        renderPage();
    }

    private void saveCurrentPage() {
        if (page == 1 && profileNameInput != null) {
            String name = profileNameInput.getText().toString().trim();
            preferences.edit().putString("account_name", name)
                    .putInt("profile_avatar", selectedAvatarIndex).apply();
        } else if (page == 2) {
            preferences.edit().putString("home_wallpaper_id", selectedWallpaper)
                    .putBoolean("home_wallpaper_enabled",
                            !HomeWallpaperCatalog.BANNER.equals(selectedWallpaper))
                    .putString("appearance", selectedAppearance).apply();
        } else if (page == 3) {
            saveWeatherPreferences();
        } else if (page == 4) {
            saveDockPreferences();
        }
    }

    private void finishSetup() {
        saveCurrentPage();
        preferences.edit().putBoolean("setup_wizard_complete", true).apply();
        setResult(RESULT_OK);
        finish();
    }

    private String getCurrentHomePackage() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolved == null || resolved.activityInfo == null) return getString(R.string.app_info_unknown);
        return resolved.activityInfo.packageName;
    }

    private boolean isHomeKeyServiceEnabled() {
        return isAccessibilityServiceEnabled(new ComponentName(this, HomeKeyService.class));
    }

    private boolean isHomeRedirectEnabled() {
        return isAccessibilityServiceEnabled(new ComponentName(
                "de.gabri.firetvhomehelper",
                "de.gabri.firetvhomehelper.AmazonHomeAccessibilityService"));
    }

    private boolean isAccessibilityServiceEnabled(ComponentName service) {
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        for (String flattened : enabledServices.split(":")) {
            if (service.equals(ComponentName.unflattenFromString(flattened))) return true;
        }
        return false;
    }

    private void openSystemSettings(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.e(TAG, "Unable to open the requested Fire TV settings page", exception);
            Toast.makeText(this, R.string.setup_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

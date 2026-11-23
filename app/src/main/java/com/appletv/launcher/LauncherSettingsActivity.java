package com.appletv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends LauncherActivity {
    private static final int APPS_MANAGEMENT_REQUEST = 43;
    private static final int SETUP_WIZARD_REQUEST = 44;
    private static final int WEATHER_LOCATION_REQUEST = 45;
    private static final int EQUALIZER_AUDIO_PERMISSION_REQUEST = 46;
    private static final String TAG = "LauncherSettings";
    private static final int[] CATEGORY_IDS = {
            R.id.settingsCategoryGeneral, R.id.settingsCategoryNetwork,
            R.id.settingsCategoryApps, R.id.settingsCategoryAccounts,
            R.id.settingsCategoryVideoAudio, R.id.settingsCategoryRemotes,
            R.id.settingsCategoryAirPlay, R.id.settingsCategoryLanguage,
            R.id.settingsCategoryAccessibility, R.id.settingsCategorySystem,
            R.id.settingsCategorySleep
    };
    private static final int[] PAGE_IDS = {
            R.id.settingsPageGeneral, R.id.settingsPageNetwork,
            R.id.settingsPageApps, R.id.settingsPageAccounts,
            R.id.settingsPageVideoAudio, R.id.settingsPageRemotes,
            R.id.settingsPageAirPlay, R.id.settingsPageLanguage,
            R.id.settingsPageAccessibility, R.id.settingsPageSystem,
            R.id.settingsPageSleep
    };
    static final String EXTRA_OPEN_WALLPAPER_PICKER =
            "com.appletv.launcher.extra.OPEN_WALLPAPER_PICKER";
    static final String EXTRA_OPEN_LYRICS_SETTINGS =
            "com.appletv.launcher.extra.OPEN_LYRICS_SETTINGS";
    private static final String REDIRECT_PACKAGE = "de.gabri.firetvhomehelper";
    private static final String REDIRECT_SERVICE = "de.gabri.firetvhomehelper.AmazonHomeAccessibilityService";
    private static final String DISABLE_REDIRECT_ACTION =
            "de.gabri.firetvhomehelper.DISABLE_REDIRECT";
    private SharedPreferences choices;
    private FocusGlassController focusGlass;
    private final TextView[] dockAppChoices = new TextView[5];
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView liquidGlassChoice;
    private TextView externalGlassChoice;
    private TextView followThemeChoice;
    private TextView appearanceChoice;
    private TextView languageChoice;
    private TextView homeWallpaperChoice;
    private TextView nowPlayingChoice;
    private TextView glassReflectionsChoice;
    private TextView glassStrengthLabel;
    private SeekBar glassStrength;
    private TextView networkChoice;
    private TextView accountNameChoice;
    private TextView lyricsProviderChoice;
    private TextView appsManagementChoice;
    private TextView homeRedirectChoice;
    private TextView weatherEnabledChoice;
    private TextView weatherLocationChoice;
    private TextView weatherCityChoice;
    private TextView bluetoothChoice;
    private TextView quickSettingsCustomizeChoice;
    private TextView homeLookNight;
    private TextView homeLookClassic;
    private TextView homeSceneNight;
    private TextView homeSceneGoldenHour;
    private TextView homeSceneWaterline;
    private int dockAppCount;
    private int selectedCategoryIndex;
    private boolean animations;
    private boolean advancedEffects;
    private boolean liquidGlassEnabled;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        focusGlass = new FocusGlassController(this);
        Config config = Config.load(this);
        dockAppCount = config.dockAppCount;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.dockApps3, R.id.dockApps4, R.id.dockApps5, R.id.dockApps6, R.id.dockApps7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + Config.MIN_DOCK_APP_COUNT;
            dockAppChoices[i] = findViewById(ids[i]);
            dockAppChoices[i].setOnClickListener(v -> {
                if (dockAppCount == value) return;
                dockAppCount = value;
                List<String> dockApps = new ArrayList<>(Config.load(this).dockApps);
                if (dockApps.size() > value) dockApps.subList(value, dockApps.size()).clear();
                String savedDockApps = joinPackages(dockApps);
                choices.edit().putInt("dock_app_count", value)
                        .putString("dock_apps", savedDockApps)
                        .putString("dock_order", savedDockApps)
                        .putBoolean("apps_changed", true).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            LinearLayout row = (LinearLayout) dockAppChoices[0].getParent();
            row.removeAllViews();
            for (int i = dockAppChoices.length - 1; i >= 0; i--) row.addView(dockAppChoices[i]);
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        liquidGlassChoice = findViewById(R.id.liquidGlassChoice);
        liquidGlassEnabled = choices.getBoolean("liquid_glass_enabled", false);
        liquidGlassChoice.setOnClickListener(v -> {
            liquidGlassEnabled = !liquidGlassEnabled;
            choices.edit().putBoolean("liquid_glass_enabled", liquidGlassEnabled).apply();
            setResult(RESULT_OK);
            refresh();
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("dock_app_count").remove("dock_apps")
                    .remove("dock_order").remove("animations").remove("advanced_effects")
                    .remove("home_wallpaper_enabled").remove("home_wallpaper_id")
                    .remove("liquid_glass_enabled").remove("dock_visual_size")
                    .remove("quick_panel_scale").remove("player_background_zoom")
                    .remove("player_background_x").remove("player_background_y")
                    .remove("fill_dock").commit();
            Config current = Config.load(this);
            dockAppCount = current.dockAppCount;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            liquidGlassEnabled = false;
            setResult(RESULT_OK);
            refresh();
        });
        appearanceChoice = findViewById(R.id.appearanceChoice);
        appearanceChoice.setOnClickListener(v -> {
            String current = choices.getString("appearance", "auto");
            String next = "auto".equals(current) ? "dark" : "dark".equals(current) ? "light" : "auto";
            choices.edit().putString("appearance", next).apply();
            setResult(RESULT_OK);
            refresh();
        });
        languageChoice = findViewById(R.id.languageChoice);
        languageChoice.setOnClickListener(v -> cycleLanguage());
        homeWallpaperChoice = findViewById(R.id.homeWallpaperChoice);
        homeWallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        homeLookNight = findViewById(R.id.homeLookNight);
        homeLookClassic = findViewById(R.id.homeLookClassic);
        homeLookNight.setOnClickListener(v -> selectHomeScene(HomeWallpaperCatalog.SCENIC));
        homeLookClassic.setOnClickListener(v -> selectHomeScene(HomeWallpaperCatalog.BANNER));
        homeSceneNight = findViewById(R.id.homeSceneNight);
        homeSceneGoldenHour = findViewById(R.id.homeSceneGoldenHour);
        homeSceneWaterline = findViewById(R.id.homeSceneWaterline);
        homeSceneNight.setOnClickListener(v -> selectHomeScene(HomeWallpaperCatalog.SCENIC));
        homeSceneGoldenHour.setOnClickListener(v -> selectHomeScene("sunset"));
        homeSceneWaterline.setOnClickListener(v -> selectHomeScene("ocean"));
        findViewById(R.id.setupWizardChoice).setOnClickListener(v ->
                startActivityForResult(new Intent(this, SetupWizardActivity.class), SETUP_WIZARD_REQUEST));
        weatherEnabledChoice = findViewById(R.id.weatherEnabledChoice);
        weatherEnabledChoice.setOnClickListener(v -> {
            boolean enabled = !choices.getBoolean("weather_enabled", false);
            choices.edit().putBoolean("weather_enabled", enabled).apply();
            setResult(RESULT_OK);
            refresh();
        });
        weatherLocationChoice = findViewById(R.id.weatherLocationChoice);
        weatherLocationChoice.setOnClickListener(v -> requestWeatherLocation());
        weatherCityChoice = findViewById(R.id.weatherCityChoice);
        weatherCityChoice.setOnClickListener(v -> showWeatherCityPicker());
        bluetoothChoice = findViewById(R.id.bluetoothChoice);
        bluetoothChoice.setOnClickListener(v -> open(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        quickSettingsCustomizeChoice = findViewById(R.id.quickSettingsCustomizeChoice);
        quickSettingsCustomizeChoice.setOnClickListener(v ->
                startActivity(new Intent(this, QuickSettingsActivity.class)));
        nowPlayingChoice = findViewById(R.id.nowPlayingChoice);
        nowPlayingChoice.setOnClickListener(v -> requestNowPlayingAccess());
        glassStrengthLabel = findViewById(R.id.glassStrengthLabel);
        glassStrength = findViewById(R.id.glassStrength);
        glassStrength.setProgress(choices.getInt("glass_strength", 65));
        glassStrength.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                glassStrengthLabel.setText(getString(R.string.glass_strength_value, progress));
                if (fromUser) {
                    choices.edit().putInt("glass_strength", progress).apply();
                    setResult(RESULT_OK);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        bindPreferenceSlider(R.id.dockVisualScale, R.id.dockVisualScaleLabel,
                "dock_visual_size", 75, 120, 100, R.string.settings_dock_visual_scale_format);
        bindPreferenceSlider(R.id.quickPanelScale, R.id.quickPanelScaleLabel,
                "quick_panel_scale", 80, 120, 100, R.string.settings_quick_panel_scale_format);
        bindPreferenceSlider(R.id.playerBackgroundZoom, R.id.playerBackgroundZoomLabel,
                "player_background_zoom", 100, 160, 100,
                R.string.settings_player_background_zoom_format);
        bindPreferenceSlider(R.id.playerBackgroundX, R.id.playerBackgroundXLabel,
                "player_background_x", -100, 100, 0,
                R.string.settings_player_background_x_format);
        bindPreferenceSlider(R.id.playerBackgroundY, R.id.playerBackgroundYLabel,
                "player_background_y", -100, 100, 0,
                R.string.settings_player_background_y_format);
        bindPreferenceSlider(R.id.playerTitleScrollSpeed, R.id.playerTitleScrollSpeedLabel,
                NowPlayingTitleRow.SCROLL_SPEED_PREFERENCE,
                NowPlayingTitleRow.MIN_SCROLL_SPEED_PERCENT,
                NowPlayingTitleRow.MAX_SCROLL_SPEED_PERCENT,
                NowPlayingTitleRow.DEFAULT_SCROLL_SPEED_PERCENT,
                R.string.settings_title_scroll_speed_format);
        glassReflectionsChoice = findViewById(R.id.glassReflectionsChoice);
        glassReflectionsChoice.setOnClickListener(v -> {
            boolean enabled = !choices.getBoolean("glass_reflections", true);
            choices.edit().putBoolean("glass_reflections", enabled).apply();
            setResult(RESULT_OK);
            refresh();
        });
        externalGlassChoice = findViewById(R.id.externalGlassChoice);
        externalGlassChoice.setOnClickListener(v -> {
            Intent accessibilitySettings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            if (accessibilitySettings.resolveActivity(getPackageManager()) != null) {
                open(accessibilitySettings);
            } else {
                openTvSettings();
            }
        });
        findViewById(R.id.equalizerAudioPermissionChoice).setOnClickListener(
                v -> requestEqualizerAudioPermission());
        findViewById(R.id.settingsPowerOffChoice).setOnClickListener(v -> requestTvPowerDialog());
        networkChoice = findViewById(R.id.networkChoice);
        networkChoice.setOnClickListener(v -> openWifiSettings());
        findViewById(R.id.settingsHelpChoice).setOnClickListener(v ->
                showInfoDialog(R.string.settings_help, R.string.settings_help_message));
        findViewById(R.id.settingsLegalChoice).setOnClickListener(v ->
                showInfoDialog(R.string.settings_legal_information, R.string.settings_legal_message));
        findViewById(R.id.settingsNewFeaturesChoice).setOnClickListener(v ->
                showInfoDialog(R.string.settings_new_features, R.string.settings_new_features_message));
        findViewById(R.id.settingsSoftwareUpdatesChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)));
        findViewById(R.id.settingsLauncherUpdateChoice).setOnClickListener(v ->
                startActivity(new Intent(this, LauncherUpdateActivity.class)));
        findViewById(R.id.airPlayTvSettingsChoice).setOnClickListener(v -> openTvSettings());
        findViewById(R.id.audioOutputChoice).setOnClickListener(v ->
                startActivity(new Intent(this, AudioOutputActivity.class)));
        findViewById(R.id.spotifyConnectChoice).setOnClickListener(v ->
                startActivity(new Intent(this, SpotifyConnectActivity.class)));
        lyricsProviderChoice = findViewById(R.id.lyricsProviderChoice);
        lyricsProviderChoice.setOnClickListener(v -> chooseLyricsProvider());
        TextView musixmatchApiKeyChoice = findViewById(R.id.musixmatchApiKeyChoice);
        musixmatchApiKeyChoice.setOnClickListener(v -> editMusixmatchApiKey());
        accountNameChoice = findViewById(R.id.accountNameChoice);
        accountNameChoice.setOnClickListener(v -> editAccountName());
        appsManagementChoice = findViewById(R.id.appsManagementChoice);
        appsManagementChoice.setOnClickListener(v ->
                startActivityForResult(new Intent(this, AppsManagementActivity.class), APPS_MANAGEMENT_REQUEST));
        homeRedirectChoice = findViewById(R.id.homeRedirectChoice);
        homeRedirectChoice.setOnClickListener(v -> toggleHomeRedirect());
        findViewById(R.id.settingsFactoryResetChoice).setOnClickListener(v -> confirmFactoryReset());
        findViewById(R.id.settingsRestartChoice).setOnClickListener(v -> restartLauncher());
        findViewById(R.id.settingsSleepChoice).setOnClickListener(v ->
                startActivity(new Intent(this, ClockPreviewActivity.class)));
        for (int i = 0; i < CATEGORY_IDS.length; i++) {
            final int categoryIndex = i;
            View category = findViewById(CATEGORY_IDS[i]);
            category.setOnFocusChangeListener((view, hasFocus) -> {
                if (hasFocus) selectSettingsCategory(categoryIndex);
            });
            category.setOnClickListener(view -> selectSettingsCategory(categoryIndex));
        }
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        refresh();
        if (getIntent().getBooleanExtra(EXTRA_OPEN_WALLPAPER_PICKER, false)) {
            selectSettingsCategory(0);
            homeWallpaperChoice.requestFocus();
            homeWallpaperChoice.post(this::showWallpaperPicker);
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_LYRICS_SETTINGS, false)) {
            selectSettingsCategory(3);
            lyricsProviderChoice.requestFocus();
        } else {
            int initialCategory = state == null ? 0
                    : Math.max(0, Math.min(CATEGORY_IDS.length - 1,
                    state.getInt("settings_category", 0)));
            selectSettingsCategory(initialCategory);
            findViewById(CATEGORY_IDS[initialCategory]).requestFocus();
        }
        TextView version = findViewById(R.id.settingsVersionInfo);
        String versionName = getString(R.string.app_info_unknown);
        String versionUpdatedAt = getString(R.string.app_info_unknown);
        try {
            android.content.pm.PackageInfo packageInfo = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            String installedVersion = packageInfo.versionName;
            if (installedVersion != null && !installedVersion.isEmpty()) {
                versionName = installedVersion;
            }

            if (packageInfo.lastUpdateTime > 0) {
                versionUpdatedAt = new SimpleDateFormat("ddMMyyyy-HHmm", Locale.ROOT)
                        .format(new Date(packageInfo.lastUpdateTime));
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
            Log.e(TAG, "Could not read the launcher version", exception);
        }
        version.setText(getString(R.string.settings_version_format,
                getString(R.string.app_name), versionName, versionUpdatedAt));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("settings_category", selectedCategoryIndex);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHomeRedirectChoice();
        updateExternalGlassChoice();
        updateAccessibilityPermissionChoices();
        refreshNowPlayingChoice();
    }

    @Override
    protected void onDestroy() {
        if (focusGlass != null) focusGlass.close();
        super.onDestroy();
    }

    private void toggleHomeRedirect() {
        if (isHomeRedirectEnabled()) {
            sendBroadcast(new Intent(DISABLE_REDIRECT_ACTION).setPackage(REDIRECT_PACKAGE));
            homeRedirectChoice.setText(getString(R.string.home_redirect_state, getString(R.string.off)));
            Toast.makeText(this, R.string.home_redirect_disabled, Toast.LENGTH_SHORT).show();
            homeRedirectChoice.postDelayed(this::updateHomeRedirectChoice, 600);
            return;
        }

        try {
            getPackageManager().getPackageInfo(REDIRECT_PACKAGE, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException exception) {
            Toast.makeText(this, R.string.home_redirect_app_missing, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.home_redirect_enable_hint, Toast.LENGTH_LONG).show();
        Intent accessibilitySettings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (accessibilitySettings.resolveActivity(getPackageManager()) != null) {
            open(accessibilitySettings);
            return;
        }
        try {
            startActivity(new Intent().setClassName(REDIRECT_PACKAGE,
                    REDIRECT_PACKAGE + ".MainActivity"));
        } catch (ActivityNotFoundException | SecurityException exception) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private void updateHomeRedirectChoice() {
        if (homeRedirectChoice == null) return;
        homeRedirectChoice.setText(getString(R.string.home_redirect_state,
                getString(isHomeRedirectEnabled() ? R.string.on : R.string.off)));
    }

    private boolean isHomeRedirectEnabled() {
        return isAccessibilityServiceEnabled(
                new ComponentName(REDIRECT_PACKAGE, REDIRECT_SERVICE));
    }

    private void updateAccessibilityPermissionChoices() {
        bindAccessibilityServiceChoice(R.id.homeKeyServicePermissionChoice,
                R.string.accessibility_service_home_key,
                new ComponentName(this, HomeKeyService.class), true);
        bindAccessibilityServiceChoice(R.id.inputKeyServicePermissionChoice,
                R.string.accessibility_service_input_key,
                new ComponentName(this, InputKeyService.class), true);
        bindAccessibilityServiceChoice(R.id.tvPowerServicePermissionChoice,
                R.string.tv_power_service_label,
                new ComponentName(this, TvPowerService.class), true);
        bindAccessibilityServiceChoice(R.id.liquidGlassServicePermissionChoice,
                R.string.liquid_glass_service_name,
                new ComponentName(this, LiquidGlassOverlayService.class), true);
        boolean redirectInstalled = isPackageInstalled(REDIRECT_PACKAGE);
        bindAccessibilityServiceChoice(R.id.homeRedirectServicePermissionChoice,
                R.string.accessibility_service_home_redirect,
                new ComponentName(REDIRECT_PACKAGE, REDIRECT_SERVICE), redirectInstalled);

        TextView audioPermission = findViewById(R.id.equalizerAudioPermissionChoice);
        boolean audioPermissionGranted = checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        audioPermission.setText(getString(R.string.accessibility_audio_permission_state,
                getString(audioPermissionGranted ? R.string.on : R.string.off)));
        audioPermission.setAlpha(audioPermissionGranted ? 1f : 0.75f);
    }

    private void bindAccessibilityServiceChoice(
            int viewId, int labelId, ComponentName component, boolean installed) {
        TextView choice = findViewById(viewId);
        boolean enabled = installed && isAccessibilityServiceEnabled(component);
        int state = !installed ? R.string.not_available
                : enabled ? R.string.on : R.string.off;
        choice.setText(getString(R.string.accessibility_service_state,
                getString(labelId), getString(state)));
        choice.setAlpha(installed && enabled ? 1f : 0.75f);
        choice.setOnClickListener(v -> {
            if (!installed) {
                Toast.makeText(this, R.string.home_redirect_app_missing, Toast.LENGTH_LONG).show();
                return;
            }
            Intent accessibilitySettings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            if (accessibilitySettings.resolveActivity(getPackageManager()) != null) {
                open(accessibilitySettings);
            } else {
                openTvSettings();
            }
        });
    }

    private boolean isPackageInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    private boolean isAccessibilityServiceEnabled(ComponentName service) {
        if (Settings.Secure.getInt(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false;
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        for (String flattened : enabledServices.split(":")) {
            if (service.equals(ComponentName.unflattenFromString(flattened))) return true;
        }
        return false;
    }

    private void requestEqualizerAudioPermission() {
        String permission = android.Manifest.permission.RECORD_AUDIO;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return;
        if (choices.getBoolean("equalizer_audio_permission_prompted", false)
                && !shouldShowRequestPermissionRationale(permission)) {
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", getPackageName(), null)));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.equalizer_audio_permission_title)
                .setMessage(R.string.equalizer_audio_permission_message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    choices.edit().putBoolean("equalizer_audio_permission_prompted", true).apply();
                    requestPermissions(new String[]{permission},
                            EQUALIZER_AUDIO_PERMISSION_REQUEST);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateExternalGlassChoice() {
        if (externalGlassChoice == null) return;
        boolean enabled = liquidGlassEnabled && isLiquidGlassOverlayServiceEnabled();
        externalGlassChoice.setText(getString(R.string.external_glass_state,
                getString(enabled ? R.string.on : R.string.off)));
    }

    private boolean isLiquidGlassOverlayServiceEnabled() {
        if (Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false;
        }
        String enabledServices = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        ComponentName service = new ComponentName(this, LiquidGlassOverlayService.class);
        for (String flattened : enabledServices.split(":")) {
            if (service.equals(ComponentName.unflattenFromString(flattened))) return true;
        }
        return false;
    }

    private void cycleLanguage() {
        String[] languages = {"", "en", "de", "fr", "es"};
        String current = choices.getString("language", "");
        int index = 0;
        for (int i = 0; i < languages.length; i++) {
            if (languages[i].equals(current)) {
                index = i;
                break;
            }
        }
        String next = languages[(index + 1) % languages.length];
        choices.edit().putString("language", next).apply();
        setResult(RESULT_OK);
        recreate();
    }

    private void updateWeatherLocation() {
        choices.edit().putBoolean("weather_use_location", true)
                .putBoolean("weather_enabled", true).putString("weather_city", "").apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void selectHomeScene(String sceneId) {
        choices.edit().putString("home_wallpaper_id", sceneId)
                .putBoolean("home_wallpaper_enabled",
                        !HomeWallpaperCatalog.BANNER.equals(sceneId)).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void setSceneCheck(TextView row, boolean checked) {
        row.setCompoundDrawablePadding(dp(8));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                checked ? R.drawable.ic_check : 0, 0);
    }

    private void setSceneRow(TextView row, boolean checked, String sceneId) {
        Drawable thumbnail = getDrawable(HomeWallpaperCatalog.previewDrawable(sceneId));
        if (thumbnail != null) {
            thumbnail = thumbnail.mutate();
            thumbnail.setBounds(0, 0, dp(36), dp(24));
        }
        Drawable check = checked ? getDrawable(R.drawable.ic_check) : null;
        if (check != null) {
            check = check.mutate();
            check.setBounds(0, 0, dp(18), dp(18));
        }
        row.setCompoundDrawablePadding(dp(8));
        row.setCompoundDrawablesRelative(thumbnail, null, check, null);
    }

    private void requestWeatherLocation() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                    WEATHER_LOCATION_REQUEST);
            return;
        }
        updateWeatherLocation();
    }

    private void showWeatherCityPicker() {
        String[] cities = getResources().getStringArray(R.array.setup_weather_cities);
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_weather_choose_city)
                .setItems(cities, (dialog, which) -> {
                    choices.edit().putString("weather_city", cities[which])
                            .putBoolean("weather_use_location", false)
                            .putBoolean("weather_enabled", true).apply();
                    setResult(RESULT_OK);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == EQUALIZER_AUDIO_PERMISSION_REQUEST) {
            updateAccessibilityPermissionChoices();
            if (grantResults.length == 0
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.equalizer_audio_permission_denied,
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != WEATHER_LOCATION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            updateWeatherLocation();
        } else {
            Toast.makeText(this, R.string.setup_weather_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    private void openWifiSettings() {
        startActivity(new Intent(this, NetworkSettingsActivity.class));
    }

    private void editAccountName() {
        String currentName = choices.getString("account_name", "");
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint(R.string.account_name_hint);
        input.setText(currentName);
        input.setSelection(input.length());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.account_name)
                .setMessage(R.string.account_name_description)
                .setView(input)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = input.getText().toString().trim();
                    choices.edit().putString("account_name", name).apply();
                    refresh();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private void openTvSettings() {
        sendBroadcast(new Intent("de.gabri.firetvhomehelper.SYSTEM_SETTINGS_HANDOFF")
                .setPackage("de.gabri.firetvhomehelper"));
        open(new Intent(Settings.ACTION_SETTINGS));
    }

    private void requestTvPowerDialog() {
        TvPowerDialogHelper.request(this,
                () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                this::openTvSettings);
    }

    private void bindPreferenceSlider(int seekBarId, int labelId, String preferenceKey,
                                     int minimum, int maximum, int defaultValue,
                                     int labelFormat) {
        SeekBar seekBar = findViewById(seekBarId);
        TextView label = findViewById(labelId);
        int value = Math.max(minimum, Math.min(maximum,
                choices.getInt(preferenceKey, defaultValue)));
        seekBar.setMax(maximum - minimum);
        seekBar.setProgress(value - minimum);
        label.setText(getString(labelFormat, value));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int selected = minimum + progress;
                label.setText(getString(labelFormat, selected));
                if (fromUser) {
                    choices.edit().putInt(preferenceKey, selected).apply();
                    setResult(RESULT_OK);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == APPS_MANAGEMENT_REQUEST || requestCode == SETUP_WIZARD_REQUEST)
                && resultCode == RESULT_OK) {
            if (requestCode == SETUP_WIZARD_REQUEST) refresh();
            setResult(RESULT_OK);
        }
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < dockAppChoices.length; i++) {
            dockAppChoices[i].setSelected(dockAppCount == i + Config.MIN_DOCK_APP_COUNT);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        int liquidGlassState = !liquidGlassEnabled ? R.string.off
                : Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ? R.string.on_fallback : R.string.on;
        liquidGlassChoice.setText(getString(R.string.liquid_glass_state,
                getString(liquidGlassState)));
        followThemeChoice.setSelected(!hasOnTvChoices());
        String appearance = choices.getString("appearance", "auto");
        int appearanceLabel = "light".equals(appearance) ? R.string.appearance_light
                : "dark".equals(appearance) ? R.string.appearance_dark : R.string.appearance_auto;
        appearanceChoice.setText(getString(R.string.appearance_mode,
                getString(appearanceLabel)));
        languageChoice.setText(getString(R.string.language_mode, languageName(choices.getString("language", ""))));
        homeWallpaperChoice.setText(getString(R.string.home_wallpaper_state,
                getString(HomeWallpaperCatalog.label(HomeWallpaperCatalog.selectedId(this)))));
        String wallpaperId = HomeWallpaperCatalog.selectedId(this);
        setSceneCheck(homeLookNight, !HomeWallpaperCatalog.BANNER.equals(wallpaperId));
        setSceneCheck(homeLookClassic, HomeWallpaperCatalog.BANNER.equals(wallpaperId));
        setSceneRow(homeSceneNight, HomeWallpaperCatalog.SCENIC.equals(wallpaperId),
                HomeWallpaperCatalog.SCENIC);
        setSceneRow(homeSceneGoldenHour, "sunset".equals(wallpaperId), "sunset");
        setSceneRow(homeSceneWaterline, "ocean".equals(wallpaperId), "ocean");
        weatherEnabledChoice.setText(getString(R.string.settings_weather_enabled,
                getString(choices.getBoolean("weather_enabled", false) ? R.string.on : R.string.off)));
        weatherLocationChoice.setText(getString(R.string.settings_weather_location_state,
                getString(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                        ? R.string.setup_weather_location_ready : R.string.setup_weather_location_hint)));
        String weatherCity = choices.getString("weather_city", "").trim();
        weatherCityChoice.setText(getString(R.string.settings_weather_city,
                weatherCity.isEmpty() ? getString(R.string.setup_weather_city_empty) : weatherCity));
        updateExternalGlassChoice();
        updateAccessibilityPermissionChoices();
        glassStrengthLabel.setText(getString(R.string.glass_strength_value, glassStrength.getProgress()));
        glassReflectionsChoice.setText(getString(R.string.glass_reflections_state,
                getString(choices.getBoolean("glass_reflections", true) ? R.string.on : R.string.off)));
        networkChoice.setText(R.string.network_settings);
        String accountName = choices.getString("account_name", "").trim();
        accountNameChoice.setText(getString(R.string.account_name_value,
                accountName.isEmpty() ? getString(R.string.account_name_not_set) : accountName));
        String provider = LyricsProvider.selected(choices);
        int providerLabel;
        if (LyricsProvider.LRCLIB.equals(provider)) {
            providerLabel = R.string.lyrics_provider_lrclib;
        } else if (LyricsProvider.GENIUS.equals(provider)) {
            providerLabel = R.string.lyrics_provider_genius;
        } else if (LyricsProvider.PLAYER.equals(provider)) {
            providerLabel = R.string.lyrics_provider_player;
        } else {
            providerLabel = R.string.lyrics_provider_musixmatch;
        }
        lyricsProviderChoice.setText(getString(R.string.lyrics_provider_current,
                getString(providerLabel)));
        appsManagementChoice.setText(R.string.apps_management);
        applyAppearance();
    }

    private void selectSettingsCategory(int selectedIndex) {
        selectedCategoryIndex = selectedIndex;
        for (int i = 0; i < CATEGORY_IDS.length; i++) {
            findViewById(CATEGORY_IDS[i]).setSelected(i == selectedIndex);
            findViewById(PAGE_IDS[i]).setVisibility(
                    i == selectedIndex ? View.VISIBLE : View.GONE);
        }
    }

    private void applyAppearance() {
        boolean light = LauncherUiSettings.isLight(this);
        View content = findViewById(android.R.id.content);
        ViewGroup root = (ViewGroup) ((ViewGroup) content).getChildAt(0);
        content.setBackgroundColor(Color.TRANSPARENT);
        root.setBackgroundResource(light ? R.drawable.settings_backdrop_light
                : R.drawable.settings_backdrop);
        if (light) {
            glassStrength.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF007AFF));
            glassStrength.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFF007AFF));
        } else {
            android.content.res.ColorStateList tint =
                    android.content.res.ColorStateList.valueOf(0xFF80CBC4);
            glassStrength.setProgressTintList(tint);
            glassStrength.setThumbTintList(tint);
        }
        applyThemeRows(root, light);
    }

    private void confirmFactoryReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.launcher_reset_title)
                .setMessage(R.string.launcher_reset_message)
                .setPositiveButton(R.string.launcher_reset_confirm,
                        (dialog, which) -> resetLauncher())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showInfoDialog(int title, int message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void chooseLyricsProvider() {
        String[] providers = {
                LyricsProvider.LRCLIB, LyricsProvider.MUSIXMATCH,
                LyricsProvider.GENIUS, LyricsProvider.PLAYER
        };
        String[] labels = {
                getString(R.string.lyrics_provider_lrclib),
                getString(R.string.lyrics_provider_musixmatch),
                getString(R.string.lyrics_provider_genius),
                getString(R.string.lyrics_provider_player)
        };
        String selected = LyricsProvider.selected(choices);
        int selectedIndex = LyricsProvider.LRCLIB.equals(selected) ? 0
                : LyricsProvider.GENIUS.equals(selected) ? 2
                : LyricsProvider.PLAYER.equals(selected) ? 3 : 1;
        new AlertDialog.Builder(this)
                .setTitle(R.string.lyrics_provider_title)
                .setSingleChoiceItems(labels, selectedIndex, (dialog, which) -> {
                    if (which < 0 || which >= providers.length) return;
                    choices.edit().putString(LyricsProvider.PREFERENCE_KEY, providers[which]).apply();
                    setResult(RESULT_OK);
                    refresh();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editMusixmatchApiKey() {
        MusixmatchApiKeyStore store = new MusixmatchApiKeyStore(this);
        String savedApiKey;
        try {
            savedApiKey = store.readApiKey();
        } catch (java.security.GeneralSecurityException | org.json.JSONException
                 | java.io.IOException exception) {
            Log.e(TAG, "Could not read the Musixmatch API key", exception);
            Toast.makeText(this, R.string.musixmatch_api_key_store_error, Toast.LENGTH_LONG).show();
            return;
        }

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.musixmatch_api_key_hint);
        input.setSelectAllOnFocus(true);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, padding / 2, padding, padding / 2);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.musixmatch_api_key_dialog_title)
                .setMessage(R.string.musixmatch_api_key_description)
                .setView(input)
                .setPositiveButton(R.string.musixmatch_api_key_save, null)
                .setNegativeButton(android.R.string.cancel, null);
        if (!savedApiKey.isEmpty()) {
            builder.setNeutralButton(R.string.musixmatch_api_key_remove, (dialog, which) -> {
                if (!store.clear()) {
                    Log.e(TAG, "Could not remove the Musixmatch API key");
                    Toast.makeText(this, R.string.musixmatch_api_key_store_error,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(this, R.string.musixmatch_api_key_removed, Toast.LENGTH_SHORT).show();
            });
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String apiKey = input.getText().toString().trim();
                    if (apiKey.isEmpty()) {
                        input.setError(getString(R.string.musixmatch_api_key_empty));
                        return;
                    }
                    try {
                        store.saveApiKey(apiKey);
                        setResult(RESULT_OK);
                        Toast.makeText(this, R.string.musixmatch_api_key_saved,
                                Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } catch (java.security.GeneralSecurityException | org.json.JSONException
                             | java.io.IOException exception) {
                        Log.e(TAG, "Could not save the Musixmatch API key", exception);
                        input.setError(getString(R.string.musixmatch_api_key_store_error));
                    } catch (IllegalArgumentException exception) {
                        input.setError(getString(R.string.musixmatch_api_key_empty));
                    }
                }));
        dialog.show();
    }

    private void resetLauncher() {
        boolean reset = clearPreferences(choices, "launcher settings");
        boolean spotifyReset = new SpotifyTokenStore(this).reset();
        if (!spotifyReset) Log.e(TAG, "Could not clear Spotify authorization data");
        reset &= spotifyReset;
        boolean musixmatchReset = new MusixmatchApiKeyStore(this).clear();
        if (!musixmatchReset) Log.e(TAG, "Could not clear the Musixmatch API key");
        reset &= musixmatchReset;
        reset &= clearPreferences(getSharedPreferences(
                RecentAppsActivity.PREFERENCES_NAME, MODE_PRIVATE), "recent app history");
        reset &= deleteIfPresent(new File(getFilesDir(), Cards.STORE_FILE));

        File externalFiles = Config.dir(this);
        if (externalFiles != null) {
            reset &= deleteIfPresent(new File(externalFiles, Config.FILE_NAME));
            reset &= deleteIfPresent(new File(externalFiles, Config.WALLPAPER_NAME));
            reset &= deleteIfPresent(new File(externalFiles, Config.THEME_REVISION_NAME));
        }

        if (!reset) {
            Toast.makeText(this, R.string.launcher_reset_failed, Toast.LENGTH_LONG).show();
            recreate();
            return;
        }
        Config.load(this);
        boolean setupStarted = choices.edit()
                .putBoolean("setup_wizard_complete", false)
                .putBoolean("fill_dock", false)
                .putInt("dock_app_count", Config.MIN_DOCK_APP_COUNT)
                .putString("dock_apps", "")
                .putString("dock_order", "")
                .putString("appearance", "dark")
                .commit();
        if (!setupStarted) {
            Log.e(TAG, "Could not persist setup and empty dock state after reset");
            Toast.makeText(this, R.string.launcher_reset_failed, Toast.LENGTH_LONG).show();
            recreate();
            return;
        }
        Toast.makeText(this, R.string.launcher_reset_success, Toast.LENGTH_SHORT).show();
        restartLauncher();
    }

    private boolean clearPreferences(SharedPreferences preferences, String description) {
        boolean cleared = preferences.edit().clear().commit();
        if (!cleared) Log.e(TAG, "Could not clear " + description);
        return cleared;
    }

    private boolean deleteIfPresent(File file) {
        try {
            if (!file.exists()) return true;
            boolean deleted = file.delete();
            if (!deleted) Log.e(TAG, "Could not delete launcher data file " + file.getName());
            return deleted;
        } catch (SecurityException exception) {
            Log.e(TAG, "Could not delete launcher data file " + file.getName(), exception);
            return false;
        }
    }

    private void restartLauncher() {
        Intent home = new Intent(this, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(home);
        finish();
    }

    private void showWallpaperPicker() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_picker_title)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(4), dp(4), dp(4), dp(4));
        String selectedId = HomeWallpaperCatalog.selectedId(this);
        String[] ids = HomeWallpaperCatalog.ids();
        boolean light = LauncherUiSettings.isLight(this);
        for (int i = 0; i < ids.length; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            options.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(122)));
            addWallpaperChoice(row, ids[i], selectedId, light, dialog);
            if (i + 1 < ids.length) {
                addWallpaperChoice(row, ids[i + 1], selectedId, light, dialog);
            } else {
                row.addView(new View(this), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
        }
        dialog.setView(options);
        dialog.setOnShowListener(ignored -> {
            for (int i = 0; i < options.getChildCount(); i++) {
                View row = options.getChildAt(i);
                if (!(row instanceof ViewGroup)) continue;
                ViewGroup choicesRow = (ViewGroup) row;
                for (int j = 0; j < choicesRow.getChildCount(); j++) {
                    View choice = choicesRow.getChildAt(j);
                    if (choice.isSelected()) {
                        choice.requestFocus();
                        i = options.getChildCount();
                        break;
                    }
                }
            }
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(56), dp(700));
                window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private void addWallpaperChoice(LinearLayout row, String id, String selectedId,
                                    boolean light, AlertDialog dialog) {
        FrameLayout card = new FrameLayout(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        cardParams.setMargins(dp(5), dp(5), dp(5), dp(5));
        row.addView(card, cardParams);
        card.setPadding(dp(3), dp(3), dp(3), dp(3));
        card.setFocusable(true);
        card.setClickable(true);
        card.setSelected(id.equals(selectedId));
        card.setBackgroundResource(light ? R.drawable.settings_choice_bg_light
                : R.drawable.settings_choice_bg);

        ImageView preview = new ImageView(this);
        preview.setImageResource(HomeWallpaperCatalog.previewDrawable(id));
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundResource(R.drawable.tile_bg);
        preview.setClipToOutline(true);
        preview.setContentDescription(getString(HomeWallpaperCatalog.label(id)));
        card.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View shade = new View(this);
        shade.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams shadeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30), Gravity.BOTTOM);
        card.addView(shade, shadeParams);

        TextView label = new TextView(this);
        label.setText(HomeWallpaperCatalog.label(id));
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30), Gravity.BOTTOM);
        card.addView(label, labelParams);

        card.setOnClickListener(view -> {
            choices.edit().putString("home_wallpaper_id", id)
                    .putBoolean("home_wallpaper_enabled", !HomeWallpaperCatalog.isBanner(id))
                    .apply();
            setResult(RESULT_OK);
            refresh();
            dialog.dismiss();
        });
    }

    private void requestNowPlayingAccess() {
        if (NowPlayingListener.hasAccess(this)) {
            startActivity(new Intent(this, NowPlayingActivity.class));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.now_playing_title)
                .setMessage(R.string.now_playing_permission_explanation)
                .setPositiveButton(R.string.now_playing_enable_access, (dialog, which) ->
                        NowPlayingAccessSettings.open(this))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshNowPlayingChoice() {
        if (nowPlayingChoice == null) return;
        nowPlayingChoice.setText(getString(R.string.now_playing_access_state,
                getString(NowPlayingListener.hasAccess(this) ? R.string.on : R.string.off)));
    }

    private void applyThemeRows(View view, boolean light) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.isFocusable() && text.isClickable()) {
                boolean category = isSettingsCategory(text.getId());
                text.setBackgroundResource(category && !light
                        ? R.drawable.settings_category_bg
                        : light ? R.drawable.settings_choice_bg_light : R.drawable.settings_choice_bg);
                text.setTextColor(getResources().getColorStateList(category
                        ? light ? R.color.settings_category_text_light : R.color.settings_category_text_dark
                        : light ? R.color.settings_choice_text_light : R.color.settings_choice_text_dark));
                boolean sceneSelection = text.getId() == R.id.homeLookNight
                        || text.getId() == R.id.homeLookClassic
                        || text.getId() == R.id.homeSceneNight
                        || text.getId() == R.id.homeSceneGoldenHour
                        || text.getId() == R.id.homeSceneWaterline;
                if (!sceneSelection && text.getCompoundDrawablesRelative()[2] != null) {
                    text.setCompoundDrawableTintList(getResources().getColorStateList(
                            light ? R.color.settings_chevron_tint_light
                                    : R.color.settings_chevron_tint_dark));
                }
            } else if (text.getTextSize() < dp(22)) {
                text.setTextColor(light ? 0xFF6E6E73 : getColor(R.color.tvos_label_secondary));
            } else {
                text.setTextColor(light ? 0xFF1C1C1E : getColor(R.color.tvos_label_primary));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyThemeRows(group.getChildAt(i), light);
        }
    }

    private boolean isSettingsCategory(int viewId) {
        for (int categoryId : CATEGORY_IDS) {
            if (categoryId == viewId) return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String languageName(String language) {
        if ("de".equals(language)) return getString(R.string.language_german);
        if ("fr".equals(language)) return getString(R.string.language_french);
        if ("es".equals(language)) return getString(R.string.language_spanish);
        if ("en".equals(language)) return getString(R.string.language_english);
        return getString(R.string.language_system);
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("dock_app_count")
                || choices.contains("dock_apps") || choices.contains("fill_dock")
                || choices.contains("animations")
                || choices.contains("advanced_effects") || choices.contains("home_wallpaper_enabled")
                || choices.contains("home_wallpaper_id") || choices.contains("liquid_glass_enabled");
    }

    private String joinPackages(List<String> packages) {
        StringBuilder joined = new StringBuilder();
        for (String pkg : packages) {
            if (joined.length() > 0) joined.append(',');
            joined.append(pkg);
        }
        return joined.toString();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}

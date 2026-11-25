package com.appletv.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.SoundEffectConstants;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.lang.ref.WeakReference;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.URISyntaxException;

/**
 * tvOS style home: black, a pinned dock row, an app grid, and one glass highlight that springs between tiles.
 */
public class MainActivity extends LauncherActivity implements AppAdapter.TileListener {
    private static volatile MainActivity activeInstance;
    private static volatile WeakReference<MainActivity> screensaverActivity =
            new WeakReference<>(null);
    private static volatile boolean homePlayerExpandedForDream;
    private static volatile boolean launcherWasResumedForDream;
    private static volatile long launcherPausedAtUptimeMs;
    private static final ExecutorService WATCH_NEXT_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Launcher-WatchNext");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService LYRICS_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Launcher-Lyrics");
        thread.setDaemon(true);
        return thread;
    });

    private static final class SpotifyPlaylistTrackEntry {
        final SpotifyConnectClient.Playlist playlist;
        final String trackUri;
        final TextView row;
        final TextView status;
        final PopupWindow popup;
        final boolean addedByLauncher;

        SpotifyPlaylistTrackEntry(SpotifyConnectClient.Playlist playlist, String trackUri,
                                  TextView row, TextView status, PopupWindow popup,
                                  boolean addedByLauncher) {
            this.playlist = playlist;
            this.trackUri = trackUri;
            this.row = row;
            this.status = status;
            this.popup = popup;
            this.addedByLauncher = addedByLauncher;
        }

    }

    private static final int LAUNCHER_SETTINGS_REQUEST = 42;
    private static final int SETUP_WIZARD_REQUEST = 43;
    private static final int EQUALIZER_AUDIO_PERMISSION_REQUEST = 44;
    private static final long SPOTIFY_REMOTE_REFRESH_MS = 10_000L;
    private static final long SPOTIFY_DEVICE_ROUTE_CACHE_MS = 60_000L;
    private static final long HOME_PLAYER_SEEK_STEP_MS = 15_000L;
    private static final long HOME_PLAYER_SEEK_DEBOUNCE_MS = 180L;
    private static final long HOME_PLAYER_UP_DOUBLE_PRESS_MS = 400L;
    private static final long HOME_PLAYER_SESSION_LOSS_GRACE_MS = 3_000L;
    private static final long DEFAULT_SCREENSAVER_TIMEOUT_MS = 300_000L;
    private static final long SCREENSAVER_MOVE_MS = 60_000L;
    private static final int[] SCREENSAVER_GLOW_COLORS = {
            0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C
    };
    private final Runnable backgroundUpdateCardTick = new Runnable() {
        @Override
        public void run() {
            refreshBackgroundUpdateCard();
        }
    };
    private static final String[] SCREENSAVER_LANDSCAPES = {
            HomeWallpaperCatalog.SCENIC, HomeWallpaperCatalog.SCENIC,
            "ocean", "sunset", "aurora"
    };
    private static final String HOME_PLAYER_END_PENDING_KEY = "home_player_end_pending";
    private static final String HOME_PLAYER_END_KEEP_BACKGROUND_KEY =
            "home_player_end_keep_background";
    /** Sent by QuickSettingsActivity: open Launcher settings from here so Home rebuilds when they close. */
    static final String ACTION_LAUNCHER_SETTINGS = "com.appletv.launcher.action.LAUNCHER_SETTINGS";
    static final String ACTION_SHOW_WELCOME = "com.appletv.launcher.action.SHOW_WELCOME";
    static final String ACTION_POST_UPDATE = "com.appletv.launcher.action.POST_UPDATE";
    static final String ACTION_HOME_EDIT_MODE = "com.appletv.launcher.action.HOME_EDIT_MODE";
    /** Home row width: configured app slots plus the fixed Apps and Settings tiles. */
    static int COLUMNS = 4;
    private boolean rtlAppGrid;
    private static final long MINUTE_MS = 60_000L;
    private static final long LAUNCHER_RELEASE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private String liveTvPkg;
    private String liveTvAction;
    private String inputAction;
    private String sourcePanelService;
    private String sourcePanelAction;

    private AppAdapter adapter;
    private RecyclerView grid;
    private GridLayoutManager gridLayoutManager;
    private DockDecoration dockDecoration;
    private FrameLayout root;
    private View homeContent;
    private View homeHeader;
    private View clockPanel;
    private ImageView homeWallpaper;
    private ImageView homeWallpaperForeground;
    private FrameLayout homePlayerBackdrop;
    private ImageView homePlayerBackgroundArt;
    private ImageView homePlayerArtwork;
    private View homePlayerPanel;
    private View homeScreensaverOverlay;
    private View homeScreensaverBackgroundShade;
    private View homeScreensaverAmbient;
    private ImageView homeScreensaverBackground;
    private TextView homeScreensaverGreeting;
    private GradientDrawable homeScreensaverGlow;
    private ValueAnimator homeScreensaverGlowAnimator;
    private final Random homeScreensaverRandom = new Random();
    private int homeScreensaverGlowIndex;
    private int homeScreensaverGlowColor;
    private int homeScreensaverLandscapeIndex = -1;
    private boolean homeScreensaverActive;
    private boolean homeScreensaverPlayerMode;
    private boolean homeScreensaverContinueShelfWasVisible;
    private float homeScreensaverHeaderStartX;
    private View homePlayerText;
    private NowPlayingTitleRow homePlayerTitleRow;
    private TextView homePlayerArtist;
    private TextView homePlayerAlbum;
    private TextView homePlayerSource;
    private LinearLayout homePlayerLyricsPanel;
    private RecyclerView homePlayerLyricsList;
    private SyncedLyricsAdapter homePlayerLyricsAdapter;
    private TextView homePlayerElapsed;
    private TextView homePlayerDuration;
    private ApplePlaybackProgressView homePlayerProgress;
    private int renderedLyricsLineIndex = -1;
    private String renderedLyricsTrackKey = "";
    private long homePlayerLyricsVisibilityGeneration;
    private boolean homePlayerLyricsVisibilityTransitionActive;
    private NowPlayingListener.Snapshot homePlayerSnapshot;
    private final HomePlayerSeekState homePlayerSeekState = new HomePlayerSeekState();
    private volatile long homePlayerSeekGeneration;
    private Runnable pendingSpotifySeek;
    private String pendingSpotifySeekTrackKey;
    private String fetchedHomeLyricsTrackKey = "";
    private String fetchedHomeLyrics;
    private String fetchedHomeLyricsProvider;
    private LrclibLyricsClient.Lyrics fetchedLrclibLyrics;
    private boolean fetchedHomeLyricsRetryable;
    private boolean lrclibRequestRunning;
    private String lrclibRequestTrackKey = "";
    private long lrclibRetryUntilUptimeMs;
    private AlertDialog geniusLyricsDialog;
    private ExecutorService spotifyPlaybackExecutor;
    private boolean spotifyPlaybackRequestRunning;
    private boolean homeResumed;
    private boolean releaseCheckStarted;
    private boolean updateTermsDialogShowing;
    private boolean updateAvailableDialogShowing;
    private LauncherReleaseChecker.Release pendingLauncherRelease;
    private long spotifyPlaybackRequestGeneration;
    private volatile long lyricsRequestGeneration;
    private String spotifyPlaybackClientId;
    private String spotifyArtworkUrl;
    private Bitmap spotifyArtwork;
    private String spotifyOutputRouteDeviceId;
    private boolean spotifyOutputRouteIsCurrentTv;
    private long spotifyOutputRouteResolvedAtMs;
    private Bitmap homePlayerArtworkBitmap;
    private String renderedPlayerTrackKey = "";
    private long homePlayerRenderGeneration;
    private long homePlayerVisibilityGeneration;
    private boolean homePlayerExpanded;
    private long lastExpandedPlayerUpAt;
    private boolean screensaverTransitionActive;
    private boolean homeScreensaverDismissing;
    private int homeHeaderVisibilityBeforeOverlay = -1;
    private boolean expandHomePlayerOnResume;
    private boolean homeNowPlayingDismissed;
    private NowPlayingListener.Snapshot audioOutputResumeSnapshot;
    private long audioOutputPopupGeneration;
    private long spotifyPlaylistPopupGeneration;
    private SpotifyPlaylistTrackEntry focusedAddedSpotifyTrack;
    private volatile boolean nowPlayingTileFocused;
    private boolean homePlayerSessionLossGraceScheduled;
    private boolean collapsingHomePlayerAfterSessionLoss;
    private boolean nowPlayingReceiverRegistered;
    private GlassHighlightView glass;
    private View ambient;
    private TextView heroName;
    private TopShelfView topShelfArtwork;
    private LinearLayout continueShelf;
    private LinearLayout continueShelfCards;
    private HorizontalScrollView continueShelfScroll;
    private String continueShelfPackage = "";
    private long continueShelfRequest;
    private int ambientColor = -1;
    private ValueAnimator ambientAnimator;
    private boolean heroShown;
    private boolean animations;
    private boolean advancedEffects;
    private TextView greeting;
    private View btnInputs;
    private TextView btnWifi;
    private TextView btnRecentApps;
    private TextView btnSettings;
    private TextView btnPower;
    private LinearLayout homeUpdateCard;
    private View homeUpdateCardDot;
    private TextView homeUpdateCardStatus;
    private TextView homeUpdateCardCountdown;
    private CharSequence wifiDisplayLabel;
    private TextView shamsiDate;
    private boolean shamsi;
    /** Optional time-of-day backdrop (config.xml, sky_intensity); 0 keeps it hidden. */
    private SkyView sky;
    private String vpnApp;
    private SpringAnimation springX, springY;
    private float glassTargetX, glassTargetY;
    // How far the grid has scrolled the glass. springY works without it, in grid content space, so a
    // scroll moves the glass in lockstep with its tile instead of becoming a moving spring target.
    private float glassScrollShift;
    private boolean glassShown;
    private boolean homeEditMode;
    private int lastShelfPosition = RecyclerView.NO_POSITION;
    // Static so they survive the activity being destroyed while another app is open (low-memory TVs,
    // or Developer options → "Don't keep activities"); they only reset when the process dies.
    private static int lastFocused = 0;
    private static long lastHomePressTime = 0;
    static final String EXTRA_HOME_ACCESSIBILITY_REDIRECT =
            "com.appletv.launcher.extra.HOME_ACCESSIBILITY_REDIRECT";
    static final String EXTRA_EXPAND_HOME_PLAYER =
            "com.appletv.launcher.extra.EXPAND_HOME_PLAYER";
    /** App of the last focused tile, so focus follows it when the app list changes. */
    private static String lastFocusedKey;
    private float density;
    /** Module cards (res/values/config.xml, module_cards): discovered on resume, shown only after the owner's Show. */
    private boolean moduleCards;
    /** Calmer motion for weak GPUs and pre-API-31 software glass rendering. */
    private boolean reducedMotion;
    private boolean scenicHomeWallpaper;
    private String selectedHomeWallpaperId;
    private int baseGridColumns;
    /** Festive skin from the theme (see Festival); applied while it is active today. */
    private Festival festival = Festival.NONE;
    private boolean festive;
    private DiyaDrawable diya;
    private BellDrawable bell;
    private MarigoldDrawable marigold;
    private int greetingColor;
    /** The day the petal shower last played, so it greets the first Home of each day only. */
    private static LocalDate lastPetalShower;
    /** hidden_apps as the adapter sees them (config.xml or a theme's config.json): a hidden app gets no card either. */
    private Set<String> hiddenApps = Collections.emptySet();
    private List<Cards.Card> cards = Collections.emptyList();
    /** The grid has been laid out and focused since the last resume; the card prompt waits for this. */
    private boolean tilesReady;
    private Dialog cardPrompt;
    /** Set when a decision could not be saved: no more prompts until the next resume, instead of asking in a loop. */
    private boolean promptsPaused;
    /** Rebuild on first launch; package changes refresh the grid through packageReceiver. */
    private boolean rebuildOnResume = true;
    /** The Inputs or Module cards dropdown while one is open, so onPause can close it. */
    private PopupWindow openPopup;
    private View welcomeOverlay;
    private final List<ValueAnimator> welcomeDotAnimations = new ArrayList<>();
    private long welcomeShownAt;
    private boolean welcomeTilesReady;
    private boolean welcomeDrawn;
    private final Runnable welcomeDismiss = this::hideWelcomeOverlay;
    private TextView welcomeTitleView;
    private TextView welcomeSubtitleView;
    private boolean postUpdateCalibrationRequested;
    private long homeStartupStartedAtUptimeMs;
    private static final String HOME_TUTORIAL_VERSION_KEY = "home_tutorial_version_code";
    private static final String PLAYER_TUTORIAL_VERSION_KEY = "player_tutorial_version_code";
    private FrameLayout tutorialOverlay;
    private FrameLayout tutorialMenuOverlay;
    private TextView tutorialTitle;
    private TextView tutorialBody;
    private TextView tutorialProgress;
    private TextView tutorialNext;
    private Runnable pendingTutorialStart;
    private boolean tutorialIsPlayer;
    private boolean tutorialStartScheduled;
    private int tutorialStep;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable launcherReleaseCheckTick = new Runnable() {
        @Override
        public void run() {
            if (!homeResumed || isFinishing() || isDestroyed()) return;
            releaseCheckStarted = false;
            checkForLauncherUpdate();
            handler.postDelayed(this, LAUNCHER_RELEASE_CHECK_INTERVAL_MS);
        }
    };
    private final Runnable homeScreensaverIdle = this::showHomeScreensaver;
    private final Runnable homeScreensaverTick = new Runnable() {
        @Override
        public void run() {
            if (!homeScreensaverActive) return;
            if (!homeScreensaverPlayerMode) {
                shiftHomeScreensaverGlow();
                cycleHomeScreensaverLandscape();
            }
            handler.postDelayed(this, SCREENSAVER_MOVE_MS);
        }
    };
    private final Runnable refreshDockFocus = () -> {
        if (grid == null || adapter == null) return;
        adapter.resetDockTranslations(grid);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            int position = grid.getChildAdapterPosition(child);
            View card = child.findViewById(R.id.card);
            if (position >= 0 && card != null && card.isFocused()) {
                onTileFocused(position, card);
                break;
            }
        }
    };
    private final Runnable positionDockAndRefreshFocus = () -> {
        positionDockAtBottom();
        refreshDockFocus.run();
    };
    /** Refreshes the network pill when the connection changes: at boot Wi-Fi often connects after Home is shown. */
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    /** Shows the Inputs pill once the TV registers an input: at boot HDMI inputs can appear after Home is shown. */
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            updateInputsButton();
        }

        @Override
        public void onInputRemoved(String inputId) {
            updateInputsButton();
        }
    };
    /** Listens for any VPN coming up or going down, even one this app's traffic is excluded from. */
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }

        @Override
        public void onLost(@NonNull Network network) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final IntentFilter packageFilter = new IntentFilter();
    private boolean packageReceiverRegistered;
    private final Runnable packageReload = () -> reloadApps(false);
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED followed by ADDED; keep the tile until the new APK is ready.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            handler.removeCallbacks(packageReload);
            handler.postDelayed(packageReload, 350);
        }
    };
    private final BroadcastReceiver nowPlayingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshHomeNowPlaying();
        }
    };
    private final Runnable homePlayerProgressTick = new Runnable() {
        @Override
        public void run() {
            updateHomePlayerIndicator(homePlayerSnapshot);
            renderHomePlayerProgress(true);
            if (homePlayerExpanded && homePlayerSnapshot != null && homePlayerSnapshot.playing) {
                handler.postDelayed(this, 1000);
            }
        }
    };
    private final Runnable homePlayerSessionLossTimeout = () -> {
        homePlayerSessionLossGraceScheduled = false;
        if (!homeResumed) return;
        NowPlayingListener.Snapshot current = NowPlayingListener.snapshot();
        if (current != null && current.active) {
            refreshHomeNowPlaying();
            return;
        }
        collapsingHomePlayerAfterSessionLoss = true;
        try {
            refreshHomeNowPlaying();
        } finally {
            collapsingHomePlayerAfterSessionLoss = false;
        }
    };
    private final Runnable audioOutputSwitchGraceTimeout = new Runnable() {
        @Override
        public void run() {
            if (NowPlayingListener.expireAudioOutputSwitchGrace(MainActivity.this)) {
                collapsingHomePlayerAfterSessionLoss = true;
                try {
                    refreshHomeNowPlaying();
                } finally {
                    collapsingHomePlayerAfterSessionLoss = false;
                }
                return;
            }
            long remainingMs = NowPlayingListener.audioOutputSwitchGraceRemainingMs();
            if (remainingMs > 0) handler.postDelayed(this, remainingMs);
        }
    };
    private final Runnable spotifyPlaybackTick = new Runnable() {
        @Override
        public void run() {
            if (!homeResumed) return;
            refreshSpotifyRemotePlayback();
            handler.postDelayed(this, SPOTIFY_REMOTE_REFRESH_MS);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateWifiLabel();
            sky.update();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        homeStartupStartedAtUptimeMs = android.os.SystemClock.uptimeMillis();
        Intent launchIntent = getIntent();
        postUpdateCalibrationRequested = launchIntent != null
                && ACTION_POST_UPDATE.equals(launchIntent.getAction());
        spotifyPlaybackExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Launcher-SpotifyPlayback");
            thread.setDaemon(true);
            return thread;
        });
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        // config.xml, overlaid by config.json if present (see Config).
        Config cfg = Config.load(this);
        COLUMNS = cfg.dockAppCount + 2;
        baseGridColumns = COLUMNS;
        boolean lowApiPerformanceProfile = Build.VERSION.SDK_INT < Build.VERSION_CODES.S;
        animations = cfg.animations;
        reducedMotion = cfg.reducedMotion;
        boolean performanceLimited = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("launcher_performance_limited", false);
        advancedEffects = animations && cfg.advancedEffects && !reducedMotion
                && !lowApiPerformanceProfile && !performanceLimited;
        festival = cfg.festival;
        festive = festival.active(LocalDate.now());
        liveTvPkg = cfg.liveTvPackage;
        liveTvAction = cfg.liveTvAction;
        inputAction = cfg.inputAction;
        sourcePanelService = cfg.sourcePanelService;
        sourcePanelAction = cfg.sourcePanelAction;

        root = findViewById(R.id.root);
        homeContent = findViewById(R.id.homeContent);
        homeHeader = findViewById(R.id.homeHeader);
        clockPanel = findViewById(R.id.clockPanel);
        homeWallpaper = findViewById(R.id.homeWallpaper);
        homeWallpaperForeground = findViewById(R.id.homeWallpaperForeground);
        homePlayerBackdrop = findViewById(R.id.homePlayerBackdrop);
        homePlayerBackgroundArt = findViewById(R.id.homePlayerBackgroundArt);
        homePlayerArtwork = findViewById(R.id.homePlayerArtwork);
        homePlayerPanel = findViewById(R.id.homePlayerPanel);
        homePlayerText = findViewById(R.id.homePlayerText);
        homePlayerTitleRow = findViewById(R.id.homePlayerTitleRow);
        homePlayerTitleRow.setScrollSpeedPercent(getSharedPreferences(
                Config.SETTINGS_NAME, MODE_PRIVATE).getInt(
                NowPlayingTitleRow.SCROLL_SPEED_PREFERENCE,
                NowPlayingTitleRow.DEFAULT_SCROLL_SPEED_PERCENT));
        homePlayerTitleRow.setOnCaptureUnavailableListener(() ->
                Toast.makeText(this, R.string.equalizer_audio_capture_unavailable,
                        Toast.LENGTH_LONG).show());
        homePlayerArtist = findViewById(R.id.homePlayerArtist);
        homePlayerAlbum = findViewById(R.id.homePlayerAlbum);
        homePlayerSource = findViewById(R.id.homePlayerSource);
        homePlayerLyricsPanel = findViewById(R.id.homePlayerLyricsPanel);
        homePlayerLyricsList = findViewById(R.id.homePlayerLyricsList);
        homePlayerLyricsAdapter =
                new SyncedLyricsAdapter(getResources().getDisplayMetrics().density);
        homePlayerLyricsList.setLayoutManager(new LinearLayoutManager(this));
        homePlayerLyricsList.setAdapter(homePlayerLyricsAdapter);
        homePlayerElapsed = findViewById(R.id.homePlayerElapsed);
        homePlayerDuration = findViewById(R.id.homePlayerDuration);
        homePlayerProgress = findViewById(R.id.homePlayerProgress);
        homePlayerProgress.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    homePlayerSeekState.releaseRewind();
                    return true;
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
            }
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                seekHomePlayer(-1, event.getRepeatCount() + 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                seekHomePlayer(1, event.getRepeatCount() + 1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                btnRecentApps.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusTile(lastFocused);
                return true;
            }
            return false;
        });
        homePlayerProgress.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) cancelPendingHomePlayerSeek();
        });
        homePlayerProgress.setContentDescription(getString(R.string.now_playing_progress_description));
        String wallpaperId = HomeWallpaperCatalog.selectedId(this);
        selectedHomeWallpaperId = wallpaperId;
        boolean wallpaperEnabled = !HomeWallpaperCatalog.isBanner(wallpaperId);
        scenicHomeWallpaper = cfg.wallpaper == null && wallpaperEnabled;
        boolean systemLightAppearance = LauncherUiSettings.isLight(this);
        boolean lightAppearance = systemLightAppearance && !scenicHomeWallpaper;
        root.setBackgroundColor(systemLightAppearance && cfg.wallpaper == null && !wallpaperEnabled
                && cfg.backgroundColor == Color.BLACK ? Color.rgb(242, 243, 247) : cfg.backgroundColor);
        if (cfg.wallpaper != null) {
            homeWallpaper.setVisibility(View.GONE);
            homeWallpaperForeground.setVisibility(View.GONE);
            showWallpaper(cfg.wallpaper);
        } else if (!wallpaperEnabled) {
            homeWallpaper.setVisibility(View.GONE);
            homeWallpaperForeground.setVisibility(View.GONE);
            findViewById(R.id.wallpaperShade).setVisibility(View.GONE);
        } else {
            homeWallpaper.setImageResource(HomeWallpaperCatalog.drawable(wallpaperId));
            if (HomeWallpaperCatalog.usesForeground(wallpaperId)) {
                ColorMatrix foregroundTint = new ColorMatrix();
                foregroundTint.setScale(0.86f, 0.86f, 0.86f, 1f);
                homeWallpaperForeground.setColorFilter(new ColorMatrixColorFilter(foregroundTint));
                homeWallpaperForeground.post(() -> {
                    if (homeWallpaperForeground.getWidth() == 0 || clockPanel.getHeight() == 0) return;
                    Rect bounds = new Rect(0, 0, clockPanel.getWidth(), clockPanel.getHeight());
                    root.offsetDescendantRectToMyCoords(clockPanel, bounds);
                    int overlap = Math.round(8 * density);
                    homeWallpaperForeground.setClipBounds(new Rect(0,
                            Math.max(0, bounds.top - overlap), root.getWidth(),
                            Math.min(root.getHeight(), bounds.bottom + overlap)));
                });
            } else {
                homeWallpaperForeground.setVisibility(View.GONE);
            }
        }
        sky = findViewById(R.id.sky);
        sky.setIntensity(cfg.skyIntensity);
        glass = findViewById(R.id.glass);
        SharedPreferences glassPreferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        int glassStrength = glassPreferences.getInt("glass_strength", 65);
        boolean reflectionsEnabled = glassPreferences.getBoolean("glass_reflections", true);
        boolean liquidGlassEnabled = glassPreferences.getBoolean("liquid_glass_enabled", false);
        glass.setEffectStrength(glassStrength);
        glass.setReflectionsEnabled(reflectionsEnabled);
        if (festive) glass.setAccent(festival.accentColor());
        glass.setLiquidGlassEnabled(liquidGlassEnabled, root);
        ambient = findViewById(R.id.ambient);
        // The glow is one bitmap, so its alpha can be applied while drawing it. Otherwise every frame of
        // a fade renders it into a full-screen offscreen buffer first.
        ambient.forceHasOverlappingRendering(false);
        if (scenicHomeWallpaper) ambient.setVisibility(View.GONE);
        heroName = findViewById(R.id.heroName);
        topShelfArtwork = findViewById(R.id.topShelfArtwork);
        continueShelf = findViewById(R.id.continueShelf);
        continueShelfCards = findViewById(R.id.continueShelfCards);
        continueShelfScroll = findViewById(R.id.continueShelfScroll);
        if (lightAppearance) {
            ((TextView) findViewById(R.id.continueShelfTitle)).setTextColor(Color.rgb(28, 28, 30));
        }
        if (lightAppearance) heroName.setTextColor(Color.rgb(28, 28, 30));
        greeting = findViewById(R.id.greeting);
        if (lightAppearance) {
            int headerTextColor = Color.rgb(90, 90, 95);
            greeting.setTextColor(headerTextColor);
            ((TextView) findViewById(R.id.date)).setTextColor(headerTextColor);
        }
        greetingColor = greeting.getCurrentTextColor();
        btnWifi = findViewById(R.id.btnWifi);
        shamsiDate = findViewById(R.id.shamsiDate);
        shamsi = cfg.shamsiDate;
        if (shamsi) {
            shamsiDate.setVisibility(View.VISIBLE);
            TextView date = findViewById(R.id.date);
            date.setTextSize(11f);
            date.setPadding(0, (int) (4 * density), 0, 0);
        }
        vpnApp = cfg.vpnApp;
        moduleCards = cfg.moduleCards;
        hiddenApps = new HashSet<>(cfg.hiddenApps);

        grid = findViewById(R.id.grid);
        rtlAppGrid = getResources().getBoolean(R.bool.rtl_app_grid);
        // Mirror only the app grid; the header and other locales keep their own layouts.
        gridLayoutManager = new GridLayoutManager(this, COLUMNS) {
            @Override
            protected boolean isLayoutRTL() {
                return rtlAppGrid;
            }

            /**
             * Every "bring this tile on screen" scroll lands here, both RecyclerView's own on focus and
             * onTileFocused's. The stock version treats the grid's top padding as the visible top, but that
             * padding only pushes the dock down the screen: grid rows scroll up into it. With the stock rule,
             * Up into a row above the padding dragged that row down to the padding line, cut it off at the
             * bottom of the screen and could scroll the focused tile out entirely, dropping focus into the
             * top bar. Grid tiles now scroll just enough to show the rect between the grid's top edge and its
             * bottom; the dock row stays anchored at the bottom by the grid's top padding.
             */
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                if (getPosition(child) < COLUMNS) return false;
                int top = child.getTop() + rect.top;
                int bottom = top + rect.height();
                int limitBottom = getHeight() - getPaddingBottom();
                int dy;
                if (top < 0) dy = top;
                else if (bottom > limitBottom) dy = Math.min(top, bottom - limitBottom);
                else dy = 0;
                if (dy == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(0, dy);
                else parent.smoothScrollBy(0, dy);
                return true;
            }
        };
        grid.setLayoutManager(gridLayoutManager);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        dockDecoration = new DockDecoration(COLUMNS, density);
        grid.addItemDecoration(dockDecoration);
        grid.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {
                adapter.resetDockTranslations(grid);
                grid.removeCallbacks(positionDockAndRefreshFocus);
                grid.post(positionDockAndRefreshFocus);
            }

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
            }
        });
        if (animations) {
            int enterAnim = reducedMotion ? R.anim.tile_enter_reduced : R.anim.tile_enter;
            float enterDelay = reducedMotion ? 0f : 0.06f;
            grid.setLayoutAnimation(new LayoutAnimationController(
                    AnimationUtils.loadAnimation(this, enterAnim), enterDelay));
        }
        adapter = new AppAdapter(this, this, COLUMNS, cfg, animations, reducedMotion);
        grid.setAdapter(adapter);

        // Keep the single dock row at the bottom edge of Home.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            positionDockAtBottom();
            adapter.resetDockTranslations(grid);
            grid.removeCallbacks(refreshDockFocus);
            grid.post(refreshDockFocus);
        });

        // Keep the glass glued to its tile while the grid scrolls.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!glassShown) return;
                glassTargetY -= dy;
                glassScrollShift -= dy;
                glass.setTranslationY(glass.getTranslationY() - dy);
            }
        });

        // Liquid feel: soft springs on the highlight's position.
        springX = new SpringAnimation(glass, DynamicAnimation.TRANSLATION_X);
        springY = new SpringAnimation(glass, new FloatPropertyCompat<View>("glassContentY") {
            @Override
            public float getValue(View v) {
                return v.getTranslationY() - glassScrollShift;
            }

            @Override
            public void setValue(View v, float value) {
                v.setTranslationY(value + glassScrollShift);
            }
        });
        for (SpringAnimation s : new SpringAnimation[]{springX, springY}) {
            if (reducedMotion) {
                // Stiff and almost critically damped: settles in a few frames instead of oscillating,
                // and stops once the highlight is within 1.5 px of its tile.
                s.setSpring(new SpringForce().setStiffness(1400f).setDampingRatio(0.95f));
                s.setMinimumVisibleChange(1.5f);
            } else {
                s.setSpring(new SpringForce().setStiffness(advancedEffects ? 760f : 620f)
                        .setDampingRatio(0.9f));
            }
        }

        btnInputs = findViewById(R.id.btnInputs);
        btnRecentApps = findViewById(R.id.btnRecentApps);
        btnSettings = findViewById(R.id.btnSettings);
        btnPower = findViewById(R.id.btnPower);
        btnInputs.setOnClickListener(v -> showInputs());
        btnRecentApps.setOnClickListener(v -> openRecentApps());
        btnWifi.setOnClickListener(v -> openNetworkPill());
        btnWifi.setOnLongClickListener(v -> {
            startActivity(new Intent(this, NetworkSettingsActivity.class));
            return true;
        });
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, QuickSettingsActivity.class)));
        btnSettings.setOnLongClickListener(v -> {
            showSettingsMenu();
            return true;
        });
        btnPower.setOnClickListener(v -> TvPowerDialogHelper.request(this,
                () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                () -> open(new Intent(Settings.ACTION_SETTINGS))));

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        boolean rtlMenuBar = getResources().getBoolean(R.bool.rtl_menu_bar);
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Back to the dock tile Up came from, not wherever the focus finder lands.
                focusTile(lastFocused % COLUMNS);
                return true;
            }
            int firstButton = R.id.btnRecentApps;
            int leftButton = rtlMenuBar ? R.id.btnPower : firstButton;
            int rightButton = rtlMenuBar ? firstButton : R.id.btnPower;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == leftButton;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == rightButton;
        };
        btnInputs.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnRecentApps.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);
        btnPower.setOnKeyListener(pillKeys);
        for (View button : new View[]{
                btnInputs, btnWifi, btnRecentApps, btnSettings, btnPower}) {
            button.setOnFocusChangeListener((view, focused) -> {
                view.animate().translationY(focused ? -3f * density : 0f)
                        .alpha(focused ? 1f : 0.92f)
                        .setDuration(!animations ? 0 : reducedMotion ? 100 : 180)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                        .start();
                if (focused && !isTopBarButton(view)) showGlassForView(view);
            });
        }
        if (festive) applyFestiveSkin((TextView) btnInputs, (TextView) btnSettings);

        // Hide the glass when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            boolean onContinueItem = isDescendantOf(newF, continueShelfCards);
            boolean onTopBar = isTopBarButton(newF);

            if (onTopBar) showGlassAfterLayout(newF);
            if (!onTile && !onContinueItem && !onTopBar) hideGlass();
        });

        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packageFilter.addDataScheme("package");
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.registerCallback(inputCallback, handler);
        if (cm != null) {
            cm.registerDefaultNetworkCallback(networkCallback);
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(), vpnCallback);
        }
        // Not after recreate(): the launch intent survives it and would open the settings again.
        boolean openedRequestedScreen = false;
        if (savedInstanceState == null) {
            openedRequestedScreen = openLauncherSettingsIfAsked(getIntent());
            if (!openedRequestedScreen) openedRequestedScreen = openHomeEditModeIfAsked(getIntent());
        }
        boolean welcomeRequested = getIntent() != null
                && (ACTION_SHOW_WELCOME.equals(getIntent().getAction())
                || ACTION_POST_UPDATE.equals(getIntent().getAction()));
        if (!openedRequestedScreen && welcomeRequested) showWelcomeOverlay();
    }

    private void showWelcomeOverlay() {
        if (root == null || isFinishing()) return;
        if (welcomeOverlay != null) {
            welcomeOverlay.animate().cancel();
            welcomeOverlay.setAlpha(1f);
            welcomeTilesReady = tilesReady;
            if (welcomeDrawn) welcomeShownAt = android.os.SystemClock.uptimeMillis();
            scheduleWelcomeDismiss();
            return;
        }

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xD9000000);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnKeyListener((view, keyCode, event) ->
                event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DPAD_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER));

        LinearLayout message = new LinearLayout(this);
        message.setGravity(Gravity.CENTER);
        message.setOrientation(LinearLayout.VERTICAL);

        String name = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString("account_name", "").trim();
        TextView title = new TextView(this);
        title.setText(postUpdateCalibrationRequested
                ? getString(R.string.update_calibration_title)
                : name.isEmpty() ? getString(R.string.welcome_back)
                : getString(R.string.welcome_back_named, name));
        welcomeTitleView = title;
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        message.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText(postUpdateCalibrationRequested
                ? R.string.update_calibration_message : R.string.loading_please_wait);
        welcomeSubtitleView = subtitle;
        subtitle.setTextColor(0xCCFFFFFF);
        subtitle.setTextSize(19);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = (int) (18 * density);
        message.addView(subtitle, subtitleParams);

        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsParams.topMargin = (int) (10 * density);
        message.addView(dots, dotsParams);
        for (int i = 0; i < 3; i++) {
            TextView dot = new TextView(this);
            dot.setText("•");
            dot.setTextColor(Color.WHITE);
            dot.setTextSize(22);
            dot.setAlpha(0.25f);
            dot.setPadding((int) (5 * density), 0, (int) (5 * density), 0);
            dots.addView(dot);
            ValueAnimator pulse = ValueAnimator.ofFloat(0.25f, 1f);
            pulse.setDuration(420);
            pulse.setStartDelay(i * 190L);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.addUpdateListener(animation -> dot.setAlpha((float) animation.getAnimatedValue()));
            pulse.start();
            welcomeDotAnimations.add(pulse);
        }

        overlay.addView(message, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        overlay.requestFocus();
        welcomeOverlay = overlay;
        welcomeDrawn = false;
        welcomeTilesReady = tilesReady;
        overlay.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (overlay.getViewTreeObserver().isAlive()) {
                            overlay.getViewTreeObserver().removeOnPreDrawListener(this);
                        }
                        welcomeDrawn = true;
                        welcomeShownAt = android.os.SystemClock.uptimeMillis();
                        scheduleWelcomeDismiss();
                        return true;
                    }
                });
        scheduleWelcomeDismiss();
    }

    private void scheduleWelcomeDismiss() {
        if (welcomeOverlay == null || !welcomeTilesReady || !welcomeDrawn) return;
        long remaining = Math.max(0, 1500L
                - (android.os.SystemClock.uptimeMillis() - welcomeShownAt));
        handler.removeCallbacks(welcomeDismiss);
        handler.postDelayed(welcomeDismiss, remaining);
    }

    private void markTilesReady() {
        tilesReady = true;
        completePostUpdateCalibration();
        if (welcomeOverlay != null) {
            welcomeTilesReady = true;
            scheduleWelcomeDismiss();
        }
        maybeStartHomeTutorial();
    }

    private void hideWelcomeOverlay() {
        if (welcomeOverlay == null || !welcomeTilesReady) return;
        handler.removeCallbacks(welcomeDismiss);
        View overlay = welcomeOverlay;
        welcomeOverlay = null;
        welcomeDrawn = false;
        overlay.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (overlay.getParent() == root) root.removeView(overlay);
            welcomeTitleView = null;
            welcomeSubtitleView = null;
            restoreFocus();
            maybeShowLauncherUpdateDisclosure();
            maybeStartHomeTutorial();
        }).start();
        for (ValueAnimator animation : welcomeDotAnimations) animation.cancel();
        welcomeDotAnimations.clear();
    }

    private void completePostUpdateCalibration() {
        if (!postUpdateCalibrationRequested) return;
        long startupMs = Math.max(0L,
                android.os.SystemClock.uptimeMillis() - homeStartupStartedAtUptimeMs);
        boolean performanceLimited = startupMs >= 1600L;
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                .putLong("launcher_startup_calibration_ms", startupMs)
                .putBoolean("launcher_performance_limited", performanceLimited)
                .apply();
        postUpdateCalibrationRequested = false;

        String name = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString("account_name", "").trim();
        if (welcomeTitleView != null) {
            welcomeTitleView.setText(name.isEmpty() ? getString(R.string.welcome_back)
                    : getString(R.string.welcome_back_named, name));
        }
        if (welcomeSubtitleView != null) {
            int profile = performanceLimited
                    ? R.string.update_calibration_profile_light
                    : R.string.update_calibration_profile_full;
            welcomeSubtitleView.setText(getString(R.string.update_calibration_complete,
                    startupMs, getString(profile)));
        }
        welcomeShownAt = android.os.SystemClock.uptimeMillis();
    }

    private void maybeStartHomeTutorial() {
        if (!homeResumed || !tilesReady || tutorialOverlay != null
                || welcomeOverlay != null || tutorialStartScheduled
                || updateTermsDialogShowing || updateAvailableDialogShowing
                || !LauncherUpdateDialogs.areCurrentTermsAccepted(this)) return;
        SharedPreferences preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        int currentVersion = (int) LauncherAppVersion.code(this);
        if (!preferences.getBoolean("setup_wizard_complete", true)
                || preferences.getInt(HOME_TUTORIAL_VERSION_KEY, 0) >= currentVersion) return;
        tutorialStartScheduled = true;
        pendingTutorialStart = () -> {
            tutorialStartScheduled = false;
            pendingTutorialStart = null;
            if (!homeResumed || isFinishing() || isDestroyed() || tutorialOverlay != null
                    || welcomeOverlay != null || updateTermsDialogShowing
                    || updateAvailableDialogShowing
                    || !LauncherUpdateDialogs.areCurrentTermsAccepted(this)) return;
            showFeatureTutorial(false);
        };
        handler.postDelayed(pendingTutorialStart, 450L);
    }

    private void maybeStartPlayerTutorial() {
        if (!homeResumed || tutorialOverlay != null || welcomeOverlay != null
                || updateTermsDialogShowing || updateAvailableDialogShowing
                || !LauncherUpdateDialogs.areCurrentTermsAccepted(this)
                || !NowPlayingListener.hasActiveSpotifyRemoteSnapshot()) return;
        SharedPreferences preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        int currentVersion = (int) LauncherAppVersion.code(this);
        if (preferences.getInt(HOME_TUTORIAL_VERSION_KEY, 0) < currentVersion
                || preferences.getInt(PLAYER_TUTORIAL_VERSION_KEY, 0) >= currentVersion) return;
        showFeatureTutorial(true);
    }

    private void showFeatureTutorial(boolean playerTutorial) {
        if (root == null || tutorialOverlay != null || isFinishing() || isDestroyed()) return;
        tutorialIsPlayer = playerTutorial;
        tutorialStep = 0;
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xB8000000);
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);
        overlay.setClickable(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(30), dp(26), dp(30), dp(24));
        panel.setBackgroundResource(R.drawable.glass_panel);
        panel.setElevation(dp(22));

        tutorialTitle = new TextView(this);
        tutorialTitle.setTextColor(Color.WHITE);
        tutorialTitle.setTextSize(25f);
        tutorialTitle.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        panel.addView(tutorialTitle);

        tutorialBody = new TextView(this);
        tutorialBody.setTextColor(0xE6FFFFFF);
        tutorialBody.setTextSize(17f);
        tutorialBody.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                dp(620), ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(14);
        panel.addView(tutorialBody, bodyParams);

        tutorialProgress = new TextView(this);
        tutorialProgress.setTextColor(0xBFFFFFFF);
        tutorialProgress.setTextSize(13f);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(18);
        panel.addView(tutorialProgress, progressParams);

        tutorialNext = new TextView(this);
        tutorialNext.setFocusable(true);
        tutorialNext.setClickable(true);
        tutorialNext.setGravity(Gravity.CENTER);
        tutorialNext.setTextColor(Color.WHITE);
        tutorialNext.setTextSize(15f);
        tutorialNext.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        tutorialNext.setPadding(dp(22), dp(10), dp(22), dp(10));
        tutorialNext.setBackgroundResource(R.drawable.home_shortcut_pill_bg);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        nextParams.topMargin = dp(16);
        nextParams.gravity = Gravity.END;
        panel.addView(tutorialNext, nextParams);
        tutorialNext.setOnClickListener(view -> advanceFeatureTutorial());
        overlay.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tutorialOverlay = overlay;
        updateTutorialContent();
        overlay.bringToFront();
        tutorialNext.requestFocus();
    }

    private void updateTutorialContent() {
        if (tutorialTitle == null || tutorialBody == null
                || tutorialProgress == null || tutorialNext == null) return;
        int[] steps = tutorialIsPlayer
                ? new int[]{R.string.tutorial_player_step_1, R.string.tutorial_player_step_2,
                R.string.tutorial_player_step_3}
                : new int[]{R.string.tutorial_home_step_1, R.string.tutorial_home_step_2,
                R.string.tutorial_home_step_3};
        tutorialTitle.setText(tutorialIsPlayer
                ? R.string.tutorial_player_title : R.string.tutorial_home_title);
        tutorialBody.setText(steps[tutorialStep]);
        tutorialProgress.setText(getString(R.string.tutorial_step_format,
                tutorialStep + 1, steps.length));
        tutorialNext.setText(tutorialStep == steps.length - 1
                ? R.string.tutorial_finish : R.string.tutorial_next);
    }

    private void advanceFeatureTutorial() {
        if (tutorialStep < 2) {
            tutorialStep++;
            updateTutorialContent();
        } else {
            finishFeatureTutorial();
        }
    }

    private void finishFeatureTutorial() {
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                .putInt(tutorialIsPlayer ? PLAYER_TUTORIAL_VERSION_KEY
                        : HOME_TUTORIAL_VERSION_KEY, (int) LauncherAppVersion.code(this))
                .apply();
        dismissTutorialLayers();
        showPendingLauncherUpdate();
        maybeStartPlayerTutorial();
    }

    private void dismissTutorialLayers() {
        if (pendingTutorialStart != null) handler.removeCallbacks(pendingTutorialStart);
        pendingTutorialStart = null;
        tutorialStartScheduled = false;
        if (tutorialMenuOverlay != null && root != null) root.removeView(tutorialMenuOverlay);
        if (tutorialOverlay != null && root != null) root.removeView(tutorialOverlay);
        tutorialMenuOverlay = null;
        tutorialOverlay = null;
        tutorialTitle = null;
        tutorialBody = null;
        tutorialProgress = null;
        tutorialNext = null;
        restoreFocus();
    }

    private void showTutorialOptions() {
        if (tutorialOverlay == null || tutorialMenuOverlay != null) return;
        FrameLayout menu = new FrameLayout(this);
        menu.setBackgroundColor(0x99000000);
        menu.setFocusable(true);
        menu.setFocusableInTouchMode(true);
        menu.setClickable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(20), dp(24), dp(20));
        panel.setBackgroundResource(R.drawable.glass_panel);

        TextView title = new TextView(this);
        title.setText(R.string.tutorial_menu_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setTypeface(android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD);
        panel.addView(title);

        TextView skip = createTutorialMenuButton(R.string.tutorial_skip);
        TextView cancel = createTutorialMenuButton(R.string.tutorial_cancel);
        LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(dp(250), dp(48));
        skipParams.topMargin = dp(14);
        panel.addView(skip, skipParams);
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(dp(250), dp(48));
        cancelParams.topMargin = dp(8);
        panel.addView(cancel, cancelParams);
        skip.setOnClickListener(view -> finishFeatureTutorial());
        cancel.setOnClickListener(view -> closeTutorialOptions());
        menu.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(menu, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tutorialMenuOverlay = menu;
        menu.bringToFront();
        skip.requestFocus();
    }

    private TextView createTutorialMenuButton(int textResource) {
        TextView button = new TextView(this);
        button.setFocusable(true);
        button.setClickable(true);
        button.setGravity(Gravity.CENTER);
        button.setText(textResource);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15f);
        button.setBackgroundResource(R.drawable.home_shortcut_pill_bg);
        return button;
    }

    private void closeTutorialOptions() {
        if (tutorialMenuOverlay != null && root != null) root.removeView(tutorialMenuOverlay);
        tutorialMenuOverlay = null;
        if (tutorialNext != null) tutorialNext.requestFocus();
    }

    private boolean openLauncherSettingsIfAsked(Intent intent) {
        if (intent == null || !ACTION_LAUNCHER_SETTINGS.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        startActivityForResult(new Intent(this, LauncherSettingsActivity.class), LAUNCHER_SETTINGS_REQUEST);
        return true;
    }

    private boolean openHomeEditModeIfAsked(Intent intent) {
        if (intent == null || !ACTION_HOME_EDIT_MODE.equals(intent.getAction())) return false;
        setIntent(new Intent(Intent.ACTION_MAIN));
        setHomeEditMode(true);
        return true;
    }

    /**
     * A theme's full-screen picture behind everything. Decoded once at the size CENTER_CROP shows, so it
     * costs a screen-sized bitmap (about 4 MB at 1080p), which is the price of a wallpaper on a 1 GB TV.
     * A picture over 4K is refused rather than decoded; the plain colour stays.
     */
    private void showWallpaper(File file) {
        try {
            android.graphics.Bitmap bmp = WallpaperSize.decode(
                    file, getResources().getDisplayMetrics());
            if (bmp == null) return;
            android.widget.ImageView wall = new android.widget.ImageView(this);
            wall.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            wall.setImageBitmap(bmp);
            root.addView(wall, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Exception | OutOfMemoryError e) {
            // A bad or huge picture must never take the home screen down; the plain colour stays.
        }
    }

    /**
     * Home pressed while Home is already in front: singleTask delivers it here, then onResume runs.
     * Back to the top with the first dock tile focused, like the stock launcher. Back from an app
     * never comes this way, so returning to the app you left from is unchanged.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (openLauncherSettingsIfAsked(intent)) return;
        if (openHomeEditModeIfAsked(intent)) return;
        if (intent != null && ACTION_POST_UPDATE.equals(intent.getAction())) {
            postUpdateCalibrationRequested = true;
            homeStartupStartedAtUptimeMs = android.os.SystemClock.uptimeMillis();
            setIntent(new Intent(Intent.ACTION_MAIN));
            showWelcomeOverlay();
            return;
        }
        if (intent != null && ACTION_SHOW_WELCOME.equals(intent.getAction())) {
            setIntent(new Intent(Intent.ACTION_MAIN));
            showWelcomeOverlay();
            return;
        }
        if (intent != null && intent.getBooleanExtra(EXTRA_EXPAND_HOME_PLAYER, false)) {
            expandHomePlayerOnResume = true;
            return;
        }
        if (adapter != null && adapter.getItemCount() == 0) {
            reloadApps(true);
            rebuildOnResume = false;
        }
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        if (intent.getBooleanExtra(EXTRA_HOME_ACCESSIBILITY_REDIRECT, false)) {
            grid.scrollToPosition(0);
            lastFocused = 0;
            lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastHomePressTime < 1200) {
            lastHomePressTime = 0;
            try {
                startActivity(new Intent(this, RecentAppsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException exception) {
                android.util.Log.e("AppleTv.HomeKey", "Unable to open recent apps", exception);
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        }
        lastHomePressTime = now;
        grid.scrollToPosition(0);
        lastFocused = 0;
        lastFocusedKey = adapter.getItemCount() > 0 ? adapter.keyAt(0) : null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!packageReceiverRegistered) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(packageReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(packageReceiver, packageFilter);
            }
            packageReceiverRegistered = true;
        }
        if (!nowPlayingReceiverRegistered) {
            IntentFilter filter = new IntentFilter(NowPlayingListener.ACTION_UPDATED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(nowPlayingReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(nowPlayingReceiver, filter);
            }
            nowPlayingReceiverRegistered = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (shouldLaunchInitialSetupWizard()) {
            startActivityForResult(new Intent(this, SetupWizardActivity.class), SETUP_WIZARD_REQUEST);
            return;
        }
        restoreHomeHeaderAfterOverlay();
        lastHomePressTime = android.os.SystemClock.uptimeMillis();
        // A festival that started or ended while Home was in the background: rebuild with or without it.
        if (festive != festival.active(LocalDate.now())) {
            recreate();
            return;
        }
        String currentWallpaperId = HomeWallpaperCatalog.selectedId(this);
        if (selectedHomeWallpaperId != null && !selectedHomeWallpaperId.equals(currentWallpaperId)) {
            recreate();
            return;
        }
        selectedHomeWallpaperId = currentWallpaperId;
        if (getIntent().getBooleanExtra(EXTRA_EXPAND_HOME_PLAYER, false)) {
            expandHomePlayerOnResume = true;
            getIntent().removeExtra(EXTRA_EXPAND_HOME_PLAYER);
        }
        homeResumed = true;
        homePlayerExpandedForDream = homePlayerExpanded;
        launcherWasResumedForDream = true;
        launcherPausedAtUptimeMs = 0L;
        screensaverActivity = new WeakReference<>(this);
        activeInstance = this;
        refreshBackgroundUpdateCard();
        NowPlayingListener.Snapshot resumeAfterAudioSettings = audioOutputResumeSnapshot;
        audioOutputResumeSnapshot = null;
        if (resumeAfterAudioSettings != null) {
            NowPlayingListener.beginAudioOutputSwitchGrace(resumeAfterAudioSettings);
        }
        resumeAudioOutputSwitchGrace();
        promptsPaused = false;
        boolean appsChanged = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("apps_changed", false);
        if (rebuildOnResume || appsChanged || adapter.getItemCount() == 0) {
            if (appsChanged) {
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                        .putBoolean("apps_changed", false).apply();
            }
            tilesReady = false;
            reloadApps(true);
        }
        rebuildOnResume = false;
        updateGreeting();
        updateWifiLabel();
        updateInputsButton();
        sky.update();
        refreshHomeNowPlaying();
        updateHomePlayerIndicator(homePlayerSnapshot);
        if (resumeAfterAudioSettings != null) {
            handler.postDelayed(() -> PlaybackResumeHelper.resumeIfPreviouslyPlaying(
                    this, resumeAfterAudioSettings), 800);
        }
        if (expandHomePlayerOnResume) {
            expandHomePlayerOnResume = false;
            setHomePlayerExpanded(true);
        }
        handler.removeCallbacks(spotifyPlaybackTick);
        handler.post(spotifyPlaybackTick);
        handler.postDelayed(minuteTick, MINUTE_MS);
        if (festive && diya != null) {
            diya.start();
            LocalDate today = LocalDate.now();
            if (!reducedMotion && !today.equals(lastPetalShower)) {
                lastPetalShower = today;
                handler.postDelayed(this::playPetalShower, 450);
            }
        }
        scheduleHomeScreensaver();
        maybeShowLauncherUpdateDisclosure();
        maybeStartHomeTutorial();
        maybeStartPlayerTutorial();
        handler.removeCallbacks(launcherReleaseCheckTick);
        handler.postDelayed(launcherReleaseCheckTick, LAUNCHER_RELEASE_CHECK_INTERVAL_MS);
    }

    private void maybeShowLauncherUpdateDisclosure() {
        if (!homeResumed || isFinishing() || isDestroyed() || welcomeOverlay != null) return;
        if (!LauncherUpdateDialogs.areCurrentTermsAccepted(this)) {
            if (updateTermsDialogShowing) return;
            updateTermsDialogShowing = true;
            LauncherUpdateDialogs.showVersionNotice(this,
                    () -> {
                        updateTermsDialogShowing = false;
                        checkForLauncherUpdate();
                        showPendingLauncherUpdate();
                        maybeStartHomeTutorial();
                    },
                    () -> updateTermsDialogShowing = false);
            return;
        }
        checkForLauncherUpdate();
        showPendingLauncherUpdate();
        maybeStartHomeTutorial();
    }

    static void notifyBackgroundUpdateChanged() {
        MainActivity current = activeInstance;
        if (current != null) {
            current.handler.post(current::refreshBackgroundUpdateCard);
        }
    }

    private void refreshBackgroundUpdateCard() {
        if (root == null || isFinishing() || isDestroyed()) return;
        if (!LauncherUpdateActivity.hasBackgroundSession()) {
            handler.removeCallbacks(backgroundUpdateCardTick);
            if (homeUpdateCard != null) homeUpdateCard.setVisibility(View.GONE);
            return;
        }
        ensureBackgroundUpdateCard();
        boolean connected = LauncherUpdateActivity.isBackgroundSessionConnected();
        boolean installPending = LauncherUpdateActivity.isBackgroundInstallPending();
        homeUpdateCard.setVisibility(View.VISIBLE);
        homeUpdateCardStatus.setText(installPending
                ? R.string.launcher_update_home_install_pending
                : connected ? R.string.launcher_update_home_connected
                : R.string.launcher_update_home_waiting);
        long remaining = LauncherUpdateActivity.backgroundSessionRemainingMillis();
        if (remaining < 0) {
            homeUpdateCardCountdown.setText(R.string.launcher_update_home_processing);
        } else {
            long seconds = Math.max(0L, (remaining + 999L) / 1000L);
            String formatted = String.format(Locale.getDefault(), "%02d:%02d",
                    seconds / 60L, seconds % 60L);
            homeUpdateCardCountdown.setText(
                    getString(R.string.launcher_update_home_countdown, formatted));
        }
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(installPending ? 0xFF8F7CFF : connected ? 0xFF52D6BA : 0xFFFFC45C);
        homeUpdateCardDot.setBackground(dot);
        handler.removeCallbacks(backgroundUpdateCardTick);
        if (homeResumed) handler.postDelayed(backgroundUpdateCardTick, 1000L);
    }

    private void ensureBackgroundUpdateCard() {
        if (homeUpdateCard != null) return;
        homeUpdateCard = new LinearLayout(this);
        homeUpdateCard.setOrientation(LinearLayout.HORIZONTAL);
        homeUpdateCard.setGravity(Gravity.CENTER_VERTICAL);
        homeUpdateCard.setPadding(dp(16), dp(12), dp(18), dp(12));
        homeUpdateCard.setBackgroundResource(R.drawable.glass_panel);
        homeUpdateCard.setElevation(dp(18));
        homeUpdateCard.setFocusable(true);
        homeUpdateCard.setClickable(true);
        homeUpdateCard.setVisibility(View.GONE);
        homeUpdateCard.setOnClickListener(view ->
                LauncherUpdateActivity.openBackgroundSession(MainActivity.this));
        homeUpdateCard.setOnFocusChangeListener((view, focused) -> {
            float scale = focused ? 1.04f : 1f;
            view.animate().scaleX(scale).scaleY(scale).setDuration(140L).start();
        });

        homeUpdateCardDot = new View(this);
        LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(dp(10), dp(10));
        dotParams.setMarginEnd(dp(12));
        homeUpdateCard.addView(homeUpdateCardDot, dotParams);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        homeUpdateCard.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = new TextView(this);
        title.setText(R.string.launcher_update_home_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15f);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        text.addView(title);
        homeUpdateCardStatus = new TextView(this);
        homeUpdateCardStatus.setTextColor(0xFFE6E9F1);
        homeUpdateCardStatus.setTextSize(12f);
        text.addView(homeUpdateCardStatus);
        homeUpdateCardCountdown = new TextView(this);
        homeUpdateCardCountdown.setTextColor(0xFFC9CEDA);
        homeUpdateCardCountdown.setTextSize(11f);
        text.addView(homeUpdateCardCountdown);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        params.setMargins(dp(40), dp(30), dp(20), 0);
        root.addView(homeUpdateCard, params);
    }

    private void checkForLauncherUpdate() {
        if (!homeResumed || isFinishing() || isDestroyed() || releaseCheckStarted
                || !LauncherUpdateDialogs.areCurrentTermsAccepted(this)) return;
        releaseCheckStarted = true;
        LauncherReleaseChecker.checkIfDue(this, release -> {
            if (release != null) pendingLauncherRelease = release;
            showPendingLauncherUpdate();
        });
    }

    private void showPendingLauncherUpdate() {
        if (!homeResumed || isFinishing() || isDestroyed() || tutorialOverlay != null
                || updateAvailableDialogShowing
                || pendingLauncherRelease == null
                || !LauncherUpdateDialogs.areCurrentTermsAccepted(this)) return;
        LauncherReleaseChecker.Release release = pendingLauncherRelease;
        pendingLauncherRelease = null;
        updateAvailableDialogShowing = true;
        boolean shown = LauncherUpdateDialogs.showAvailableUpdate(this, release,
                () -> {
                    if (homeResumed && !isFinishing() && !isDestroyed()) {
                        startActivity(LauncherUpdateActivity.createIntent(this));
                    }
                },
                () -> {
                    updateAvailableDialogShowing = false;
                    maybeStartHomeTutorial();
                    maybeStartPlayerTutorial();
                });
        if (!shown) updateAvailableDialogShowing = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == LAUNCHER_SETTINGS_REQUEST || requestCode == SETUP_WIZARD_REQUEST)
                && resultCode == RESULT_OK) recreate();
    }

    private boolean shouldLaunchInitialSetupWizard() {
        SharedPreferences preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        if (preferences.contains("setup_wizard_complete")) {
            return !preferences.getBoolean("setup_wizard_complete", true);
        }
        try {
            android.content.pm.PackageInfo packageInfo = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            boolean freshInstall = Math.abs(packageInfo.lastUpdateTime - packageInfo.firstInstallTime)
                    < 120_000L;
            preferences.edit().putBoolean("setup_wizard_complete", !freshInstall).apply();
            return freshInstall;
        } catch (PackageManager.NameNotFoundException exception) {
            android.util.Log.e("AppleTv.Main", "Unable to determine whether setup is needed", exception);
            preferences.edit().putBoolean("setup_wizard_complete", true).apply();
            return false;
        }
    }

    private void refreshHomeNowPlaying() {
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        if (snapshot != null && !snapshot.active) snapshot = null;
        if (snapshot == null && homePlayerExpanded && homePlayerSnapshot != null
                && !collapsingHomePlayerAfterSessionLoss) {
            if (homeResumed && !homePlayerSessionLossGraceScheduled) {
                homePlayerSessionLossGraceScheduled = true;
                handler.postDelayed(homePlayerSessionLossTimeout,
                        HOME_PLAYER_SESSION_LOSS_GRACE_MS);
            }
            snapshot = homePlayerSnapshot;
        } else {
            cancelHomePlayerSessionLossGrace();
        }
        String seekTrackKey = snapshot == null ? null : homePlayerSeekTrackKey(snapshot);
        if (pendingSpotifySeekTrackKey != null
                && !pendingSpotifySeekTrackKey.equals(seekTrackKey)) {
            cancelPendingSpotifySeek();
        }
        if (snapshot == null) {
            homePlayerSeekState.reset();
        } else {
            homePlayerSeekState.updateFromSnapshot(seekTrackKey,
                    currentHomePlayerPosition(snapshot), snapshot.positionUpdatedAtMs,
                    android.os.SystemClock.elapsedRealtime());
        }
        if (snapshot == null) homeNowPlayingDismissed = false;
        if (adapter == null) {
            homePlayerSnapshot = snapshot;
            return;
        }

        boolean gridHadFocus = grid.hasFocus();
        String focusKey = lastFocusedKey;
        int previousPosition = lastFocused;
        NowPlayingListener.Snapshot previousSnapshot = homePlayerSnapshot;
        boolean playerContentChanged =
                !NowPlayingListener.sameVisibleContent(previousSnapshot, snapshot);
        String previousLyricsTrackKey = previousSnapshot == null
                ? null : homePlayerLyricsTrackKey(previousSnapshot);
        boolean showDockMedia = snapshot != null && !homeNowPlayingDismissed;
        int newColumns = baseGridColumns + (showDockMedia ? 1 : 0);
        boolean columnsChanged = newColumns != COLUMNS;
        homePlayerSnapshot = snapshot;
        adapter.setNowPlaying(showDockMedia ? snapshot : null);

        if (columnsChanged) {
            COLUMNS = newColumns;
            gridLayoutManager.setSpanCount(COLUMNS);
            dockDecoration.setDockSize(COLUMNS);
            grid.invalidateItemDecorations();

            int position = adapter.positionOf(focusKey);
            if (position >= 0) lastFocused = position;
            else if (adapter.getItemCount() > 0) {
                lastFocused = Math.min(Math.max(0, previousPosition), adapter.getItemCount() - 1);
            }
            lastFocusedKey = adapter.keyAt(lastFocused);
            grid.post(() -> {
                positionDockAtBottom();
                adapter.resetDockTranslations(grid);
                if (gridHadFocus) focusTile(lastFocused);
                else refreshDockFocus.run();
            });
        }

        if (snapshot == null) setHomePlayerExpanded(false);
        if (playerContentChanged || snapshot == null) renderHomePlayer();
        else if (homePlayerExpanded) renderHomePlayerProgress(true);
        if (homePlayerExpanded && snapshot != null
                && !homePlayerLyricsTrackKey(snapshot).equals(previousLyricsTrackKey)
                && isHomePlayerLyricsEnabled()
                && LyricsProvider.LRCLIB.equals(LyricsProvider.selected(
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)))) {
            showLrclibLyrics(snapshot);
        }
        handler.removeCallbacks(homePlayerProgressTick);
        if (homePlayerExpanded && snapshot != null && snapshot.playing) {
            handler.postDelayed(homePlayerProgressTick, 1000);
        }
        maybeStartPlayerTutorial();
    }

    private void cancelHomePlayerSessionLossGrace() {
        if (!homePlayerSessionLossGraceScheduled) return;
        handler.removeCallbacks(homePlayerSessionLossTimeout);
        homePlayerSessionLossGraceScheduled = false;
    }

    private void refreshSpotifyRemotePlayback() {
        if (!homeResumed || spotifyPlaybackExecutor == null
                || spotifyPlaybackExecutor.isShutdown()) return;
        SpotifyRemotePlayback client = new SpotifyRemotePlayback(getApplicationContext());
        String clientId = client.clientId();
        if (!clientId.equals(spotifyPlaybackClientId)) {
            spotifyPlaybackClientId = clientId;
            spotifyPlaybackRequestGeneration++;
            spotifyPlaybackRequestRunning = false;
            spotifyArtworkUrl = null;
            spotifyArtwork = null;
            spotifyOutputRouteDeviceId = null;
            spotifyOutputRouteIsCurrentTv = false;
            spotifyOutputRouteResolvedAtMs = 0L;
            NowPlayingListener.setSpotifyRemoteSnapshot(this, null);
        }
        if (clientId.isEmpty() || spotifyPlaybackRequestRunning) return;

        spotifyPlaybackRequestRunning = true;
        long requestGeneration = ++spotifyPlaybackRequestGeneration;
        String cachedArtworkUrl = spotifyArtworkUrl;
        Bitmap cachedArtwork = spotifyArtwork;
        String cachedRouteDeviceId = spotifyOutputRouteDeviceId;
        boolean cachedRouteIsCurrentTv = spotifyOutputRouteIsCurrentTv;
        long cachedRouteResolvedAtMs = spotifyOutputRouteResolvedAtMs;
        Context applicationContext = getApplicationContext();
        spotifyPlaybackExecutor.execute(() -> {
            boolean succeeded = false;
            String artworkUrl = null;
            Bitmap artwork = null;
            String outputDeviceId = null;
            boolean outputDeviceIsCurrentTv = false;
            NowPlayingListener.Snapshot snapshot = null;
            try {
                SpotifyConnectClient.Playback playback = client.currentPlayback();
                succeeded = true;
                if (playback != null) {
                    outputDeviceId = playback.deviceId;
                    if (outputDeviceId != null) {
                        long nowMs = android.os.SystemClock.elapsedRealtime();
                        boolean routeCacheValid = outputDeviceId.equals(cachedRouteDeviceId)
                                && nowMs - cachedRouteResolvedAtMs
                                < SPOTIFY_DEVICE_ROUTE_CACHE_MS;
                        outputDeviceIsCurrentTv = routeCacheValid
                                ? cachedRouteIsCurrentTv
                                : SpotifyConnectDiscovery.isCurrentTvDevice(
                                applicationContext, outputDeviceId);
                    }
                    artworkUrl = playback.artworkUrl;
                    if (artworkUrl != null) {
                        if (artworkUrl.equals(cachedArtworkUrl) && cachedArtwork != null) {
                            artwork = cachedArtwork;
                        } else {
                            try {
                                artwork = SpotifyConnectClient.downloadArtwork(artworkUrl);
                            } catch (java.io.IOException exception) {
                                android.util.Log.w("AppleTv.SpotifyPlayback",
                                        "Could not load Spotify album artwork", exception);
                            }
                        }
                    }
                    snapshot = SpotifyRemotePlayback.snapshot(playback, artwork);
                }
            } catch (GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                android.util.Log.w("AppleTv.SpotifyPlayback",
                        "Could not refresh Spotify playback state", exception);
            }
            boolean requestSucceeded = succeeded;
            String resultArtworkUrl = artworkUrl;
            Bitmap resultArtwork = artwork;
            String resultOutputDeviceId = outputDeviceId;
            boolean resultOutputDeviceIsCurrentTv = outputDeviceIsCurrentTv;
            NowPlayingListener.Snapshot resultSnapshot = snapshot;
            handler.post(() -> {
                if (requestGeneration != spotifyPlaybackRequestGeneration) return;
                spotifyPlaybackRequestRunning = false;
                if (!requestSucceeded || !homeResumed || isFinishing() || isDestroyed()) return;
                handler.removeCallbacks(audioOutputSwitchGraceTimeout);
                spotifyArtworkUrl = resultArtworkUrl;
                spotifyArtwork = resultArtwork;
                spotifyOutputRouteDeviceId = resultOutputDeviceId;
                spotifyOutputRouteIsCurrentTv = resultOutputDeviceIsCurrentTv;
                spotifyOutputRouteResolvedAtMs = resultOutputDeviceId == null
                        ? 0L : android.os.SystemClock.elapsedRealtime();
                NowPlayingListener.setSpotifyRemoteSnapshot(this, resultSnapshot);
            });
        });
    }

    private void renderHomePlayer() {
        NowPlayingListener.Snapshot snapshot = homePlayerSnapshot;
        updateHomePlayerIndicator(snapshot);
        applyHomePlayerBackgroundTransform();
        if (snapshot == null) {
            homePlayerRenderGeneration++;
            homePlayerText.animate().cancel();
            homePlayerText.setAlpha(1f);
            homePlayerText.setTranslationY(0f);
            homePlayerArtwork.setImageDrawable(null);
            homePlayerBackgroundArt.setImageDrawable(null);
            homePlayerArtworkBitmap = null;
            renderedPlayerTrackKey = "";
            fetchedHomeLyricsTrackKey = "";
            fetchedHomeLyrics = null;
            fetchedHomeLyricsProvider = null;
            fetchedLrclibLyrics = null;
            fetchedHomeLyricsRetryable = false;
            hideHomePlayerLyrics();
            return;
        }

        String title = nonEmptyMediaText(snapshot.title, R.string.now_playing_unknown_title);
        String artist = nonEmptyMediaText(snapshot.artist, R.string.now_playing_unknown_artist);
        String trackKey = homePlayerTrackKey(snapshot);
        String lyricsTrackKey = homePlayerLyricsTrackKey(snapshot);
        if (!lyricsTrackKey.equals(fetchedHomeLyricsTrackKey)) {
            fetchedHomeLyricsTrackKey = "";
            fetchedHomeLyrics = null;
            fetchedHomeLyricsProvider = null;
            fetchedLrclibLyrics = null;
            fetchedHomeLyricsRetryable = false;
        }
        boolean trackChanged = !trackKey.equals(renderedPlayerTrackKey);
        long renderGeneration = ++homePlayerRenderGeneration;
        Runnable bindTrack = () -> {
            if (renderGeneration != homePlayerRenderGeneration) return;
            homePlayerTitleRow.setTitle(title);
            homePlayerArtist.setText(artist);

            if (snapshot.album == null || snapshot.album.trim().isEmpty()) {
                homePlayerAlbum.setVisibility(View.GONE);
            } else {
                homePlayerAlbum.setText(getString(R.string.now_playing_album_format, snapshot.album));
                homePlayerAlbum.setVisibility(View.VISIBLE);
            }

            String source;
            if (snapshot.spotifyRemote) {
                source = getString(R.string.spotify_connect_current_device);
            } else {
                source = snapshot.packageName;
                try {
                    source = String.valueOf(getPackageManager().getApplicationLabel(
                            getPackageManager().getApplicationInfo(snapshot.packageName, 0)));
                } catch (PackageManager.NameNotFoundException exception) {
                    android.util.Log.w("AppleTv.NowPlaying",
                            "Media app is no longer installed", exception);
                }
            }
            homePlayerSource.setText(getString(R.string.now_playing_source_format, source));

            if (snapshot.artwork != homePlayerArtworkBitmap) {
                homePlayerArtwork.animate().cancel();
                homePlayerBackgroundArt.animate().cancel();
                homePlayerArtworkBitmap = snapshot.artwork;
                if (snapshot.artwork == null) {
                    homePlayerArtwork.setImageResource(R.drawable.ic_music_note);
                    homePlayerBackgroundArt.setImageDrawable(null);
                } else {
                    homePlayerArtwork.setAlpha(homePlayerExpanded && animations ? 0f : 1f);
                    homePlayerBackgroundArt.setAlpha(homePlayerExpanded && animations ? 0f : 1f);
                    homePlayerArtwork.setImageBitmap(snapshot.artwork);
                    homePlayerBackgroundArt.setImageBitmap(createSoftPlayerBackdrop(snapshot.artwork));
                    if (homePlayerExpanded && animations) {
                        homePlayerArtwork.animate().alpha(1f).setDuration(220).start();
                        homePlayerBackgroundArt.animate().alpha(1f).setDuration(280).start();
                    }
                }
            }
            renderedPlayerTrackKey = trackKey;
            renderHomePlayerProgress();
            if (trackChanged && homePlayerExpanded && animations) {
                homePlayerText.animate().alpha(1f).translationY(0f)
                        .setDuration(reducedMotion ? 140 : 220).start();
            }
        };

        if (trackChanged && !renderedPlayerTrackKey.isEmpty()
                && homePlayerExpanded && animations) {
            homePlayerText.animate().cancel();
            homePlayerText.animate().alpha(0.18f).translationY(6f * density)
                    .setDuration(110).withEndAction(bindTrack).start();
        } else {
            homePlayerText.animate().cancel();
            homePlayerText.setAlpha(1f);
            homePlayerText.setTranslationY(0f);
            bindTrack.run();
        }
    }

    private void updateHomePlayerIndicator(NowPlayingListener.Snapshot snapshot) {
        boolean spotifyOutputIsCurrentTv = snapshot != null && snapshot.spotifyRemote
                && snapshot.spotifyDeviceId != null
                && snapshot.spotifyDeviceId.equals(spotifyOutputRouteDeviceId)
                && spotifyOutputRouteIsCurrentTv;
        boolean playing = homePlayerExpanded && snapshot != null && snapshot.playing
                && isCurrentTvAudioOutput(snapshot, spotifyOutputIsCurrentTv);
        boolean motionEnabled = animations && !reducedMotion;
        homePlayerTitleRow.setIndicatorPlaying(playing, motionEnabled);
        homePlayerTitleRow.setMarqueeEnabled(motionEnabled && homeResumed);
        boolean audioPermissionGranted = checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        homePlayerTitleRow.setAudioCaptureAllowed(audioPermissionGranted);
        if (playing && motionEnabled && !audioPermissionGranted) {
            requestEqualizerAudioPermission();
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isCurrentTvAudioOutput(
            NowPlayingListener.Snapshot snapshot, boolean spotifyOutputIsCurrentTv) {
        AudioManager audioManager = getSystemService(AudioManager.class);
        return AudioOutputRoute.isCurrentTvOutput(
                snapshot.spotifyRemote,
                spotifyOutputIsCurrentTv,
                audioManager != null && audioManager.isBluetoothA2dpOn(),
                audioManager != null && audioManager.isBluetoothScoOn(),
                audioManager != null && audioManager.isWiredHeadsetOn());
    }

    private void beginAudioOutputSwitchGrace(NowPlayingListener.Snapshot snapshot) {
        NowPlayingListener.beginAudioOutputSwitchGrace(snapshot);
        long remainingMs = NowPlayingListener.audioOutputSwitchGraceRemainingMs();
        if (remainingMs <= 0) return;
        handler.removeCallbacks(audioOutputSwitchGraceTimeout);
        handler.postDelayed(audioOutputSwitchGraceTimeout, remainingMs);
        refreshSpotifyRemotePlayback();
    }

    private void resumeAudioOutputSwitchGrace() {
        handler.removeCallbacks(audioOutputSwitchGraceTimeout);
        long remainingMs = NowPlayingListener.audioOutputSwitchGraceRemainingMs();
        if (remainingMs > 0) {
            handler.postDelayed(audioOutputSwitchGraceTimeout, remainingMs);
        } else {
            audioOutputSwitchGraceTimeout.run();
        }
    }

    private void requestEqualizerAudioPermission() {
        SharedPreferences preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        String promptKey = "equalizer_audio_permission_prompted";
        if (preferences.getBoolean(promptKey, false)) return;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.equalizer_audio_permission_title)
                .setMessage(R.string.equalizer_audio_permission_message)
                .setPositiveButton(android.R.string.ok, (ignored, which) -> {
                    preferences.edit().putBoolean(promptKey, true).apply();
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},
                            EQUALIZER_AUDIO_PERMISSION_REQUEST);
                })
                .setNegativeButton(android.R.string.cancel,
                        (ignored, which) -> preferences.edit().putBoolean(promptKey, true).apply())
                .create();
        dialog.setOnCancelListener(ignored ->
                preferences.edit().putBoolean(promptKey, true).apply());
        dialog.show();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != EQUALIZER_AUDIO_PERMISSION_REQUEST) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        homePlayerTitleRow.setAudioCaptureAllowed(granted);
        if (granted) {
            updateHomePlayerIndicator(homePlayerSnapshot);
        } else {
            Toast.makeText(this, R.string.equalizer_audio_permission_denied,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void applyHomePlayerBackgroundTransform() {
        android.content.SharedPreferences preferences =
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        int zoom = Math.max(100, Math.min(160,
                preferences.getInt("player_background_zoom", 100)));
        int horizontalOffset = Math.max(-100, Math.min(100,
                preferences.getInt("player_background_x", 0)));
        int verticalOffset = Math.max(-100, Math.min(100,
                preferences.getInt("player_background_y", 0)));
        homePlayerBackgroundArt.setScaleX(zoom / 100f);
        homePlayerBackgroundArt.setScaleY(zoom / 100f);
        homePlayerBackgroundArt.setTranslationX(horizontalOffset * density);
        homePlayerBackgroundArt.setTranslationY(verticalOffset * density);
    }

    private Bitmap createSoftPlayerBackdrop(Bitmap artwork) {
        int maxDimension = 720;
        int sourceMax = Math.max(artwork.getWidth(), artwork.getHeight());
        float scale = Math.min(1f, (float) maxDimension / sourceMax);
        int width = Math.max(1, Math.round(artwork.getWidth() * scale));
        int height = Math.max(1, Math.round(artwork.getHeight() * scale));
        return Bitmap.createScaledBitmap(artwork, width, height, true);
    }

    private String nonEmptyMediaText(String value, int fallback) {
        return value == null || value.trim().isEmpty() ? getString(fallback) : value;
    }

    private String localizedPlayerLyrics(String lyrics) {
        if (lyrics == null) return null;
        String normalized = lyrics.trim();
        if ("Dazu gibt es noch keine Songtexte".equalsIgnoreCase(normalized)
                || "Dazu gibt es noch keine Songtexte.".equalsIgnoreCase(normalized)) {
            return getString(R.string.now_playing_lyrics_unavailable);
        }
        return lyrics;
    }

    private void renderHomePlayerLyrics(NowPlayingListener.Snapshot snapshot, long positionMs) {
        if (snapshot == null || !isHomePlayerLyricsEnabled()) {
            hideHomePlayerLyrics();
            return;
        }

        String provider = LyricsProvider.selected(
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE));
        String trackKey = homePlayerLyricsTrackKey(snapshot);
        if (LyricsProvider.PLAYER.equals(provider)) {
            String playerLyrics = localizedPlayerLyrics(snapshot.lyrics);
            List<LrclibLyricsClient.LyricLine> synced =
                    LrclibLyricsClient.parseSyncedLyrics(playerLyrics);
            if (!synced.isEmpty()) {
                renderTimedLyrics(synced, positionMs, trackKey,
                        snapshot.playing, snapshot.playbackSpeed);
            } else {
                renderStaticLyrics(playerLyrics, trackKey);
            }
            return;
        } else if (trackKey.equals(fetchedHomeLyricsTrackKey)
                && provider.equals(fetchedHomeLyricsProvider)) {
            if (LyricsProvider.LRCLIB.equals(provider) && fetchedLrclibLyrics != null) {
                LrclibLyricsClient.Lyrics result = fetchedLrclibLyrics;
                if (result.instrumental) {
                    renderStaticLyrics(getString(R.string.lrclib_instrumental), trackKey);
                } else if (!result.syncedLines.isEmpty()) {
                    renderTimedLyrics(result.syncedLines, result.syncedTextLines,
                            positionMs, trackKey, snapshot.playing, snapshot.playbackSpeed);
                } else {
                    renderStaticLyrics(getString(R.string.lrclib_waiting_for_line), trackKey);
                }
            } else {
                renderStaticLyrics(fetchedHomeLyrics, trackKey);
            }
        } else {
            hideHomePlayerLyrics();
        }
    }

    private void renderTimedLyrics(List<LrclibLyricsClient.LyricLine> lines, long positionMs,
                                   String trackKey, boolean playing, float playbackSpeed) {
        List<String> textLines = new ArrayList<>(lines.size());
        for (LrclibLyricsClient.LyricLine line : lines) textLines.add(line.text);
        renderTimedLyrics(lines, textLines, positionMs, trackKey, playing, playbackSpeed);
    }

    private void renderTimedLyrics(List<LrclibLyricsClient.LyricLine> lines,
                                   List<String> textLines, long positionMs, String trackKey,
                                   boolean playing, float playbackSpeed) {
        int lineIndex = LrclibLyricsClient.lineIndexAt(lines, positionMs);
        if (lineIndex < 0) lineIndex = 0;
        float waitProgress = 0f;
        long waitRemainingMs = 0L;
        boolean animateGap = false;
        if (lineIndex < textLines.size() && textLines.get(lineIndex).trim().isEmpty()) {
            int nextLyricIndex =
                    SyncedLyricsAdapter.nextNonEmptyLineIndex(textLines, lineIndex + 1);
            if (nextLyricIndex >= 0) {
                long gapStartMs = lines.get(lineIndex).positionMs;
                long nextLyricMs = lines.get(nextLyricIndex).positionMs;
                if (nextLyricMs > positionMs && nextLyricMs > gapStartMs) {
                    waitProgress = SyncedLyricsAdapter.gapProgressAt(
                            gapStartMs, nextLyricMs, positionMs);
                    long mediaRemainingMs = nextLyricMs - positionMs;
                    double speed = playbackSpeed > 0f ? playbackSpeed : 1d;
                    waitRemainingMs = Math.max(1L,
                            (long) Math.ceil(mediaRemainingMs / speed));
                    animateGap = playing && animations && !reducedMotion;
                }
            }
        }
        renderLyricsWindow(textLines, lineIndex, trackKey, true,
                waitProgress, waitRemainingMs, animateGap);
    }

    private void renderStaticLyrics(String lyrics, String trackKey) {
        if (lyrics == null || lyrics.trim().isEmpty()) {
            hideHomePlayerLyrics();
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String line : lyrics.split("\\r?\\n")) {
            String text = line.trim();
            if (!text.isEmpty()) lines.add(text);
        }
        if (lines.isEmpty()) {
            hideHomePlayerLyrics();
            return;
        }
        renderLyricsWindow(lines, Math.min(1, lines.size() - 1), trackKey, false);
    }

    private void renderLyricsWindow(List<String> lines, int activeIndex, String trackKey,
                                    boolean animate) {
        renderLyricsWindow(lines, activeIndex, trackKey, animate, 0f, 0L, false);
    }

    private void renderLyricsWindow(List<String> lines, int activeIndex, String trackKey,
                                    boolean animate, float waitProgress, long waitRemainingMs,
                                    boolean animateGap) {
        if (lines.isEmpty()) {
            hideHomePlayerLyrics();
            return;
        }
        int safeIndex = Math.max(0, Math.min(activeIndex, lines.size() - 1));
        boolean trackChanged = !trackKey.equals(renderedLyricsTrackKey);
        int previousIndex = renderedLyricsLineIndex;
        boolean lineChanged = safeIndex != previousIndex;
        homePlayerLyricsAdapter.setLyrics(
                lines, safeIndex, waitProgress, waitRemainingMs, animateGap);
        homePlayerLyricsPanel.setVisibility(View.VISIBLE);
        homePlayerLyricsList.setContentDescription(lines.get(safeIndex));
        homePlayerLyricsPanel.animate().cancel();
        if (animate && !homePlayerLyricsVisibilityTransitionActive
                && !trackChanged && lineChanged && previousIndex >= 0
                && Math.abs(safeIndex - previousIndex) == 1 && !reducedMotion) {
            float direction = safeIndex > previousIndex ? 18f : -18f;
            homePlayerLyricsPanel.setAlpha(0.3f);
            homePlayerLyricsPanel.setTranslationY(direction * density);
            homePlayerLyricsPanel.animate().alpha(1f).translationY(0f)
                    .setDuration(320)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        } else {
            if (!homePlayerLyricsVisibilityTransitionActive) {
                homePlayerLyricsPanel.setAlpha(1f);
            }
            homePlayerLyricsPanel.setTranslationY(0f);
        }
        renderedLyricsTrackKey = trackKey;
        renderedLyricsLineIndex = safeIndex;
    }

    private void hideHomePlayerLyrics() {
        if (!homePlayerLyricsVisibilityTransitionActive && homePlayerLyricsPanel != null) {
            homePlayerLyricsPanel.setVisibility(View.GONE);
            homePlayerLyricsPanel.setAlpha(1f);
        }
        if (homePlayerLyricsAdapter != null && !homePlayerLyricsVisibilityTransitionActive) {
            homePlayerLyricsAdapter.setLyrics(java.util.Collections.emptyList(), -1);
        }
        renderedLyricsTrackKey = "";
        renderedLyricsLineIndex = -1;
    }

    private boolean isHomePlayerLyricsEnabled() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean("player_lyrics_enabled", true);
    }

    private boolean isHomePlayerLyricsVisible() {
        return homePlayerExpanded && homePlayerLyricsPanel != null
                && homePlayerLyricsPanel.getVisibility() == View.VISIBLE;
    }

    private void toggleHomePlayerLyricsVisibility() {
        SharedPreferences preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        if (isHomePlayerLyricsEnabled() && isHomePlayerLyricsVisible()) {
            preferences.edit().putBoolean("player_lyrics_enabled", false).apply();
            if (lrclibRequestRunning
                    && LyricsProvider.LRCLIB.equals(LyricsProvider.selected(preferences))) {
                fetchedHomeLyricsRetryable = true;
            }
            lyricsRequestGeneration++;
            lrclibRequestRunning = false;
            lrclibRequestTrackKey = "";
            animateHomePlayerLyricsVisibility(false, null);
        } else {
            preferences.edit().putBoolean("player_lyrics_enabled", true).apply();
            animateHomePlayerLyricsVisibility(true, () -> {
                showNowPlayingLyrics();
                if (homePlayerExpanded) renderHomePlayer();
            });
        }
    }

    private void animateHomePlayerLyricsVisibility(boolean visible, Runnable showLyrics) {
        long generation = ++homePlayerLyricsVisibilityGeneration;
        homePlayerProgress.animate().cancel();
        if (!animations || reducedMotion || !homePlayerExpanded || !homePlayerPanel.isLaidOut()) {
            homePlayerLyricsVisibilityTransitionActive = false;
            homePlayerProgress.setAlpha(1f);
            if (visible) showLyrics.run();
            else hideHomePlayerLyrics();
            return;
        }

        TransitionManager.endTransitions((ViewGroup) homePlayerPanel);
        homePlayerLyricsPanel.animate().cancel();
        homePlayerLyricsPanel.setAlpha(1f);
        homePlayerLyricsPanel.setTranslationY(0f);
        homePlayerLyricsVisibilityTransitionActive = true;
        homePlayerProgress.animate().alpha(0f).setDuration(100)
                .withEndAction(() -> {
                    if (generation != homePlayerLyricsVisibilityGeneration
                            || isFinishing() || isDestroyed()) {
                        homePlayerProgress.setAlpha(1f);
                        return;
                    }

                    ChangeBounds bounds = new ChangeBounds();
                    bounds.setDuration(260);
                    Fade lyricsFade = new Fade(visible ? Fade.IN : Fade.OUT);
                    lyricsFade.addTarget(homePlayerLyricsPanel);
                    lyricsFade.setDuration(220);
                    TransitionSet transition = new TransitionSet();
                    transition.setOrdering(TransitionSet.ORDERING_TOGETHER);
                    transition.addTransition(bounds);
                    transition.addTransition(lyricsFade);

                    homePlayerLyricsVisibilityTransitionActive = true;
                    TransitionManager.beginDelayedTransition(
                            (ViewGroup) homePlayerPanel, transition);
                    if (visible) {
                        showLyrics.run();
                    } else {
                        homePlayerLyricsPanel.setVisibility(View.GONE);
                        renderedLyricsTrackKey = "";
                        renderedLyricsLineIndex = -1;
                    }
                    homePlayerProgress.animate().alpha(1f).setStartDelay(80)
                            .setDuration(180).start();
                    handler.postDelayed(() -> {
                        if (generation != homePlayerLyricsVisibilityGeneration) return;
                        homePlayerLyricsVisibilityTransitionActive = false;
                        homePlayerLyricsPanel.setAlpha(1f);
                        if (homePlayerLyricsPanel.getVisibility() != View.VISIBLE) {
                            homePlayerLyricsAdapter.setLyrics(
                                    java.util.Collections.emptyList(), -1);
                        }
                    }, 300);
                }).start();
    }

    private void renderHomePlayerProgress() {
        renderHomePlayerProgress(false);
    }

    private void renderHomePlayerProgress(boolean smooth) {
        NowPlayingListener.Snapshot snapshot = homePlayerSnapshot;
        if (snapshot == null) return;
        long duration = Math.max(0, snapshot.durationMs);
        long position = Math.max(0, snapshot.positionMs);
        if (snapshot.playing && snapshot.positionUpdatedAtMs > 0) {
            long elapsed = Math.max(0,
                    android.os.SystemClock.elapsedRealtime() - snapshot.positionUpdatedAtMs);
            position += Math.round(elapsed * Math.max(0f, snapshot.playbackSpeed));
        }
        if (duration > 0) position = Math.min(position, duration);

        updateHomePlayerProgress(position, duration, smooth);
    }

    private void updateHomePlayerProgress(long position, long duration) {
        updateHomePlayerProgress(position, duration, false);
    }

    private void updateHomePlayerProgress(long position, long duration, boolean smooth) {
        position = Math.max(0, position);
        duration = Math.max(0, duration);
        if (duration > 0) position = Math.min(position, duration);
        homePlayerElapsed.setText(formatMediaTime(position));
        homePlayerDuration.setText(duration > 0
                ? getString(R.string.now_playing_remaining_time,
                        formatMediaTime(Math.max(0, duration - position)))
                : "");
        homePlayerProgress.setProgress(duration == 0 ? 0
                : (int) Math.min(1000, position * 1000L / duration), smooth);
        homePlayerProgress.setContentDescription(getString(
                R.string.now_playing_progress_description) + " "
                + formatMediaTime(position) + " / "
                + (duration > 0
                ? getString(R.string.now_playing_remaining_time,
                        formatMediaTime(Math.max(0, duration - position)))
                : formatMediaTime(duration)));
        renderHomePlayerLyrics(homePlayerSnapshot, position);
    }

    private void seekHomePlayer(int direction, int repeatCount) {
        NowPlayingListener.Snapshot snapshot = homePlayerSnapshot;
        if (snapshot == null || snapshot.durationMs <= 0) {
            Toast.makeText(this, R.string.now_playing_seek_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String trackKey = homePlayerSeekTrackKey(snapshot);
        long target = homePlayerSeekState.nextTarget(trackKey,
                currentHomePlayerPosition(snapshot), snapshot.durationMs, direction,
                repeatCount, android.os.SystemClock.elapsedRealtime(),
                HOME_PLAYER_SEEK_STEP_MS);
        if (target == HomePlayerSeekState.NO_SEEK) return;

        if (snapshot.spotifyRemote) {
            if (spotifyPlaybackExecutor == null || spotifyPlaybackExecutor.isShutdown()) {
                homePlayerSeekState.clearPendingTarget(trackKey);
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
            scheduleSpotifyHomePlayerSeek(snapshot, target, trackKey);
            return;
        }

        if (snapshot.controller == null) {
            homePlayerSeekState.clearPendingTarget(trackKey);
            Toast.makeText(this, R.string.now_playing_seek_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        android.media.session.PlaybackState state = snapshot.controller.getPlaybackState();
        if (state != null && (state.getActions()
                & android.media.session.PlaybackState.ACTION_SEEK_TO) == 0) {
            homePlayerSeekState.clearPendingTarget(trackKey);
            Toast.makeText(this, R.string.now_playing_seek_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        snapshot.controller.getTransportControls().seekTo(target);
        updateHomePlayerProgress(target, snapshot.durationMs);
    }

    private long currentHomePlayerPosition(NowPlayingListener.Snapshot snapshot) {
        long position = Math.max(0, snapshot.positionMs);
        if (snapshot.playing && snapshot.positionUpdatedAtMs > 0) {
            long elapsed = Math.max(0,
                    android.os.SystemClock.elapsedRealtime() - snapshot.positionUpdatedAtMs);
            position += Math.round(elapsed * Math.max(0f, snapshot.playbackSpeed));
        }
        return snapshot.durationMs > 0 ? Math.min(position, snapshot.durationMs) : position;
    }

    private String homePlayerSeekTrackKey(NowPlayingListener.Snapshot snapshot) {
        return homePlayerTrackKey(snapshot) + "\n" + snapshot.album + "\n"
                + snapshot.spotifyTrackUri + "\n" + snapshot.spotifyArtworkUrl;
    }

    private void scheduleSpotifyHomePlayerSeek(
            NowPlayingListener.Snapshot snapshot, long target, String trackKey) {
        if (pendingSpotifySeek != null) handler.removeCallbacks(pendingSpotifySeek);
        long requestGeneration = ++homePlayerSeekGeneration;
        pendingSpotifySeekTrackKey = trackKey;
        pendingSpotifySeek = () -> {
            pendingSpotifySeek = null;
            if (!isCurrentHomePlayerSeek(requestGeneration, trackKey)) {
                if (requestGeneration == homePlayerSeekGeneration) {
                    pendingSpotifySeekTrackKey = null;
                    homePlayerSeekState.clearPendingTarget(trackKey);
                }
                return;
            }
            spotifyPlaybackExecutor.execute(() -> {
                if (requestGeneration != homePlayerSeekGeneration) return;
                try {
                    new SpotifyRemotePlayback(getApplicationContext())
                            .seekTo(target, snapshot.spotifyDeviceId);
                    handler.post(() -> {
                        if (!isCurrentHomePlayerSeek(requestGeneration, trackKey)) return;
                        pendingSpotifySeekTrackKey = null;
                        updateHomePlayerProgress(target, snapshot.durationMs);
                        handler.postDelayed(this::refreshSpotifyRemotePlayback, 400L);
                    });
                } catch (GeneralSecurityException | org.json.JSONException
                         | java.io.IOException exception) {
                    android.util.Log.w("AppleTv.SpotifyPlayback",
                            "Spotify seek failed", exception);
                    handler.post(() -> {
                        if (!isCurrentHomePlayerSeek(requestGeneration, trackKey)) return;
                        pendingSpotifySeekTrackKey = null;
                        homePlayerSeekState.clearPendingTarget(trackKey);
                        Toast.makeText(this, R.string.spotify_remote_control_error,
                                Toast.LENGTH_SHORT).show();
                    });
                }
            });
        };
        handler.postDelayed(pendingSpotifySeek, HOME_PLAYER_SEEK_DEBOUNCE_MS);
    }

    private boolean isCurrentHomePlayerSeek(long generation, String trackKey) {
        return generation == homePlayerSeekGeneration && homeResumed && homePlayerExpanded
                && homePlayerProgress != null && homePlayerProgress.hasFocus()
                && homePlayerSnapshot != null
                && trackKey.equals(homePlayerSeekTrackKey(homePlayerSnapshot))
                && !isFinishing() && !isDestroyed();
    }

    private void cancelPendingSpotifySeek() {
        homePlayerSeekGeneration++;
        if (pendingSpotifySeek != null) handler.removeCallbacks(pendingSpotifySeek);
        pendingSpotifySeek = null;
        pendingSpotifySeekTrackKey = null;
    }

    private void cancelPendingHomePlayerSeek() {
        cancelPendingSpotifySeek();
        homePlayerSeekState.reset();
    }

    private String formatMediaTime(long milliseconds) {
        long totalSeconds = Math.max(0, milliseconds) / 1000;
        return String.format(Locale.getDefault(), "%d:%02d",
                totalSeconds / 60, totalSeconds % 60);
    }

    static boolean isHomePlayerExpandedForScreensaver() {
        return homePlayerExpandedForDream;
    }

    static boolean transitionHomeForScreensaver(boolean entering) {
        MainActivity activity = screensaverActivity.get();
        if (activity == null) return false;
        if (entering) {
            long pausedAgo = android.os.SystemClock.uptimeMillis() - launcherPausedAtUptimeMs;
            if (!launcherWasResumedForDream && (launcherPausedAtUptimeMs == 0L
                    || pausedAgo > 10_000L)) return false;
        } else if (!activity.screensaverTransitionActive) {
            return false;
        }
        activity.runOnUiThread(() -> activity.animateHomeForScreensaver(entering));
        return true;
    }

    static void hideHomeHeaderForQuickPanel() {
        MainActivity activity = screensaverActivity.get();
        if (activity != null) {
            activity.runOnUiThread(activity::hideHomeHeaderForOverlay);
        }
    }

    static float screensaverHeaderStartOffset(View header) {
        MainActivity activity = screensaverActivity.get();
        if (activity == null || activity.clockPanel == null || header == null) return 0f;
        int[] sourceLocation = new int[2];
        int[] headerLocation = new int[2];
        activity.clockPanel.getLocationOnScreen(sourceLocation);
        header.getLocationOnScreen(headerLocation);
        return Math.max(0f, sourceLocation[0] + activity.clockPanel.getWidth()
                - headerLocation[0] - header.getWidth());
    }

    private void animateHomeForScreensaver(boolean entering) {
        if (screensaverTransitionActive == entering || homeHeader == null || grid == null) return;
        screensaverTransitionActive = entering;
        homeHeader.animate().cancel();
        grid.animate().cancel();

        if (entering) {
            hideContinueShelfForScreensaver();
        } else if (!homeScreensaverDismissing) {
            restoreContinueShelfAfterScreensaver();
        }

        int extra = Math.round(32 * density);
        long duration = animations && !reducedMotion ? (entering ? 700 : 520) : 0;
        android.view.animation.Interpolator interpolator =
                new android.view.animation.DecelerateInterpolator(1.5f);
        if (entering) {
            homeHeader.animate()
                    .translationY(-homeHeader.getBottom() - extra)
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start();
            grid.animate()
                    .translationY(root.getHeight() + extra)
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start();
        } else {
            homeHeader.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start();
            grid.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(interpolator)
                    .start();
        }
    }

    private void scheduleHomeScreensaver() {
        handler.removeCallbacks(homeScreensaverIdle);
        if (!homeResumed || homeScreensaverActive || homeScreensaverDismissing
                || !hasWindowFocus()) return;
        long timeout = Settings.System.getLong(getContentResolver(),
                Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREENSAVER_TIMEOUT_MS);
        if (timeout <= 0L) timeout = DEFAULT_SCREENSAVER_TIMEOUT_MS;
        long lead = Math.min(90_000L, Math.max(1_000L, timeout / 4L));
        long delay = Math.max(1_000L, timeout - lead);
        handler.postDelayed(homeScreensaverIdle, delay);
    }

    private void showHomeScreensaver() {
        if (!homeResumed || homeScreensaverActive || homeScreensaverDismissing
                || !hasWindowFocus()) return;
        if ((openPopup != null && openPopup.isShowing())
                || (cardPrompt != null && cardPrompt.isShowing())) {
            scheduleHomeScreensaver();
            return;
        }

        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        homeScreensaverPlayerMode = NowPlayingListener.shouldShowPlayerBannerInScreensaver(
                homePlayerExpanded, snapshot)
                && homePlayerPanel.getVisibility() == View.VISIBLE;
        homeScreensaverOverlay = getLayoutInflater().inflate(
                R.layout.dream_screensaver, root, false);
        homeScreensaverOverlay.setBackgroundColor(
                homeScreensaverPlayerMode ? Color.TRANSPARENT : Color.BLACK);
        root.addView(homeScreensaverOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (homeScreensaverPlayerMode) homePlayerPanel.bringToFront();
        root.setKeepScreenOn(true);
        homeScreensaverActive = true;
        homeScreensaverBackground = homeScreensaverOverlay.findViewById(R.id.dreamBackground);
        homeScreensaverBackgroundShade =
                homeScreensaverOverlay.findViewById(R.id.dreamBackgroundShade);
        homeScreensaverAmbient = homeScreensaverOverlay.findViewById(R.id.dreamAmbient);
        homeScreensaverGreeting = homeScreensaverOverlay.findViewById(R.id.dreamGreeting);
        View screensaverHeader = homeScreensaverOverlay.findViewById(R.id.dreamHeader);
        screensaverHeader.setAlpha(0f);
        screensaverHeader.post(() -> {
            if (!homeScreensaverActive || homeScreensaverOverlay == null
                    || screensaverHeader.getParent() != homeScreensaverOverlay) return;
            homeScreensaverHeaderStartX = screensaverHeaderStartOffset(screensaverHeader);
            screensaverHeader.setTranslationX(homeScreensaverHeaderStartX);
            screensaverHeader.animate().cancel();
            screensaverHeader.animate().translationX(0f).alpha(1f)
                    .setDuration(animations && !reducedMotion ? 700L : 0L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        });
        homeScreensaverGreeting.setText(homeScreensaverGreeting());
        homeScreensaverBackground.setAlpha(homeScreensaverPlayerMode ? 0f : 1f);
        homeScreensaverBackground.setScaleX(1.04f);
        homeScreensaverBackground.setScaleY(1.04f);
        homeScreensaverBackgroundShade.setAlpha(0f);
        homeScreensaverAmbient.setAlpha(0f);
        homeScreensaverGlow = new GradientDrawable();
        homeScreensaverGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        homeScreensaverGlow.setGradientCenter(0.5f, 0.5f);
        homeScreensaverGlow.setGradientRadius(Math.max(1f,
                Math.max(root.getWidth(), root.getHeight()) * 0.55f));
        homeScreensaverAmbient.setBackground(homeScreensaverGlow);
        homeScreensaverGlowIndex = homeScreensaverRandom.nextInt(SCREENSAVER_GLOW_COLORS.length);
        setHomeScreensaverGlow(SCREENSAVER_GLOW_COLORS[homeScreensaverGlowIndex]);

        if (homeScreensaverPlayerMode) {
            homeScreensaverBackground.setVisibility(View.GONE);
            homeScreensaverBackgroundShade.setVisibility(View.GONE);
            homeScreensaverAmbient.setVisibility(View.GONE);
        } else {
            homeScreensaverBackground.setVisibility(View.VISIBLE);
            homeScreensaverBackgroundShade.setVisibility(View.VISIBLE);
            homeScreensaverAmbient.setVisibility(View.VISIBLE);
            setHomeScreensaverLandscape(false);
        }

        homeScreensaverOverlay.requestFocus();
        animateHomeForScreensaver(true);
        if (!homeScreensaverPlayerMode) {
            homeScreensaverBackground.animate()
                    .scaleX(1.12f).scaleY(1.12f)
                    .setDuration(1400L).start();
            homeScreensaverBackgroundShade.animate().alpha(0.3f)
                    .setDuration(1000L).start();
            homeScreensaverAmbient.animate().alpha(0.58f)
                    .setDuration(1200L).start();
        }
        handler.postDelayed(homeScreensaverTick, SCREENSAVER_MOVE_MS);
    }

    private void dismissHomeScreensaver() {
        if (!homeScreensaverActive || homeScreensaverOverlay == null) return;
        homeScreensaverActive = false;
        homeScreensaverDismissing = true;
        handler.removeCallbacks(homeScreensaverIdle);
        handler.removeCallbacks(homeScreensaverTick);
        if (homeScreensaverGlowAnimator != null) {
            homeScreensaverGlowAnimator.cancel();
            homeScreensaverGlowAnimator = null;
        }
        root.setKeepScreenOn(false);
        animateHomeForScreensaver(false);
        View overlay = homeScreensaverOverlay;
        long duration = animations && !reducedMotion ? 520L : 0L;
        View screensaverHeader = overlay.findViewById(R.id.dreamHeader);
        screensaverHeader.animate().cancel();
        screensaverHeader.animate()
                .translationX(homeScreensaverHeaderStartX)
                .alpha(0f)
                .setDuration(duration)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .start();
        homeScreensaverBackground.animate().cancel();
        homeScreensaverBackground.animate().alpha(0f).setDuration(duration).start();
        homeScreensaverBackgroundShade.animate().alpha(0f).setDuration(duration).start();
        homeScreensaverAmbient.animate().alpha(0f).setDuration(duration).start();
        overlay.animate().alpha(0f).setDuration(duration).withEndAction(() -> {
            if (root != null && overlay.getParent() == root) root.removeView(overlay);
            restoreHomeScreensaverZOrder();
            restoreContinueShelfAfterScreensaver();
            clearHomeScreensaverViews();
            homeScreensaverDismissing = false;
            if (homeResumed && grid != null && adapter != null) restoreFocus();
            scheduleHomeScreensaver();
        }).start();
    }
    private void removeHomeScreensaverImmediately() {
        handler.removeCallbacks(homeScreensaverTick);
        handler.removeCallbacks(homeScreensaverTick);
        if (homeScreensaverGlowAnimator != null) {
            homeScreensaverGlowAnimator.cancel();
            homeScreensaverGlowAnimator = null;
        }
        if (root != null) {
            root.setKeepScreenOn(false);
            if (homeScreensaverOverlay != null
                    && homeScreensaverOverlay.getParent() == root) {
                root.removeView(homeScreensaverOverlay);
            }
            restoreHomeScreensaverZOrder();
        }
        restoreContinueShelfAfterScreensaver();
        homeScreensaverActive = false;
        homeScreensaverDismissing = false;
        if (homeHeader != null) {
            homeHeader.animate().cancel();
            homeHeader.setTranslationY(0f);
            homeHeader.setAlpha(1f);
        }
        if (grid != null) {
            grid.animate().cancel();
            grid.setTranslationY(0f);
            grid.setAlpha(1f);
        }
        screensaverTransitionActive = false;
        clearHomeScreensaverViews();
    }

    private void restoreHomeScreensaverZOrder() {
        if (homeWallpaperForeground != null) homeWallpaperForeground.bringToFront();
        if (homePlayerPanel != null) homePlayerPanel.bringToFront();
        View glassHighlight = root == null ? null : root.findViewById(R.id.glass);
        if (glassHighlight != null) glassHighlight.bringToFront();
    }

    private void clearHomeScreensaverViews() {
        homeScreensaverOverlay = null;
        homeScreensaverBackgroundShade = null;
        homeScreensaverAmbient = null;
        homeScreensaverBackground = null;
        homeScreensaverGreeting = null;
        homeScreensaverGlow = null;
        homeScreensaverPlayerMode = false;
        homeScreensaverHeaderStartX = 0f;
        homeScreensaverLandscapeIndex = -1;
    }

    private void hideContinueShelfForScreensaver() {
        if (continueShelf == null) return;
        homeScreensaverContinueShelfWasVisible =
                continueShelf.getVisibility() == View.VISIBLE;
        if (homeScreensaverContinueShelfWasVisible) continueShelf.setVisibility(View.GONE);
    }

    private void restoreContinueShelfAfterScreensaver() {
        if (homeScreensaverContinueShelfWasVisible && continueShelf != null
                && continueShelfCards != null && continueShelfCards.getChildCount() > 0) {
            continueShelf.setVisibility(View.VISIBLE);
        }
        homeScreensaverContinueShelfWasVisible = false;
    }

    private void setHomeScreensaverLandscape(boolean chooseDifferent) {
        if (homeScreensaverBackground == null) return;
        int nextIndex = homeScreensaverLandscapeIndex < 0 ? 0
                : homeScreensaverRandom.nextInt(SCREENSAVER_LANDSCAPES.length);
        if (chooseDifferent && SCREENSAVER_LANDSCAPES.length > 1) {
            while (nextIndex == homeScreensaverLandscapeIndex) {
                nextIndex = homeScreensaverRandom.nextInt(SCREENSAVER_LANDSCAPES.length);
            }
        }
        homeScreensaverLandscapeIndex = nextIndex;
        homeScreensaverBackground.setImageResource(HomeWallpaperCatalog.drawable(
                SCREENSAVER_LANDSCAPES[homeScreensaverLandscapeIndex]));
    }

    private void cycleHomeScreensaverLandscape() {
        if (homeScreensaverBackground == null) return;
        homeScreensaverBackground.animate().cancel();
        homeScreensaverBackground.animate().alpha(0.15f).setDuration(700L)
                .withEndAction(() -> {
                    if (!homeScreensaverActive || homeScreensaverPlayerMode) return;
                    setHomeScreensaverLandscape(true);
                    homeScreensaverBackground.setScaleX(1.04f);
                    homeScreensaverBackground.setScaleY(1.04f);
                    homeScreensaverBackground.animate().alpha(1f)
                            .scaleX(1.12f).scaleY(1.12f)
                            .setDuration(SCREENSAVER_MOVE_MS).start();
                }).start();
    }

    private void shiftHomeScreensaverGlow() {
        if (homeScreensaverGlow == null) return;
        int nextIndex = (homeScreensaverGlowIndex + 1) % SCREENSAVER_GLOW_COLORS.length;
        if (homeScreensaverGlowAnimator != null) homeScreensaverGlowAnimator.cancel();
        homeScreensaverGlowAnimator = ValueAnimator.ofObject(new ArgbEvaluator(),
                homeScreensaverGlowColor, SCREENSAVER_GLOW_COLORS[nextIndex]);
        homeScreensaverGlowAnimator.setDuration(8000L);
        homeScreensaverGlowAnimator.addUpdateListener(animation ->
                setHomeScreensaverGlow((int) animation.getAnimatedValue()));
        homeScreensaverGlowAnimator.start();
        homeScreensaverGlowIndex = nextIndex;
    }

    private void setHomeScreensaverGlow(int color) {
        homeScreensaverGlowColor = color;
        if (homeScreensaverGlow == null) return;
        int rgb = color & 0x00FFFFFF;
        homeScreensaverGlow.setColors(new int[]{
                0x40000000 | rgb, 0x14000000 | rgb, rgb
        });
    }

    private int homeScreensaverGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 12) return R.string.good_morning;
        if (hour < 18) return R.string.good_afternoon;
        if (hour < 22) return R.string.good_evening;
        return R.string.good_night;
    }

    private void setHomePlayerExpanded(boolean expanded) {
        if (!expanded) {
            cancelPendingHomePlayerSeek();
            cancelHomePlayerSessionLossGrace();
        }
        boolean show = expanded && homePlayerSnapshot != null;
        if (homePlayerExpanded == show) return;
        homePlayerLyricsVisibilityGeneration++;
        if (homePlayerPanel.isLaidOut()) {
            TransitionManager.endTransitions((ViewGroup) homePlayerPanel);
        }
        homePlayerLyricsVisibilityTransitionActive = false;
        homePlayerProgress.animate().cancel();
        homePlayerProgress.setAlpha(1f);
        homePlayerLyricsPanel.animate().cancel();
        homePlayerLyricsPanel.setAlpha(1f);
        boolean focusPlayerTileOnCollapse = !show && homePlayerProgress.hasFocus();
        homePlayerExpanded = show;
        homePlayerExpandedForDream = show;
        updateHomePlayerIndicator(homePlayerSnapshot);
        long visibilityGeneration = ++homePlayerVisibilityGeneration;
        adapter.setNowPlaying(homeNowPlayingDismissed ? null : homePlayerSnapshot);
        if (show) {
            homePlayerBackdrop.setVisibility(View.VISIBLE);
            homePlayerPanel.setVisibility(View.VISIBLE);
            homePlayerBackdrop.setAlpha(0f);
            homePlayerPanel.setAlpha(0f);
            homePlayerPanel.setTranslationY(8f * density);
            homeWallpaperForeground.animate().cancel();
            homeWallpaperForeground.animate().alpha(0f)
                    .setDuration(animations ? 180 : 0).start();
            homePlayerBackdrop.animate().cancel();
            homePlayerPanel.animate().cancel();
            homePlayerBackdrop.animate().alpha(1f)
                    .setDuration(animations ? 240 : 0).start();
            homePlayerPanel.animate().alpha(1f).translationY(0f)
                    .setDuration(animations ? 220 : 0).start();
            hideContinueWatching();
            hideHeroName();
            topShelfArtwork.setVisibility(View.GONE);
            stopAmbientAnimation();
            ambient.setAlpha(0f);
            renderHomePlayer();
            if (isHomePlayerLyricsEnabled()
                    && LyricsProvider.LRCLIB.equals(LyricsProvider.selected(
                    getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)))) {
                showLrclibLyrics(homePlayerSnapshot);
            }
            homePlayerProgress.post(() -> {
                if (visibilityGeneration == homePlayerVisibilityGeneration
                        && homePlayerExpanded) {
                    homePlayerProgress.requestFocus();
                }
            });
        } else {
            handler.removeCallbacks(homePlayerProgressTick);
            homeWallpaperForeground.animate().cancel();
            homeWallpaperForeground.animate().alpha(1f)
                    .setDuration(animations ? 180 : 0).start();
            homePlayerBackdrop.animate().cancel();
            homePlayerPanel.animate().cancel();
            if (animations) {
                homePlayerPanel.animate().alpha(0f).translationY(8f * density)
                        .setDuration(160).withEndAction(() -> {
                            if (visibilityGeneration == homePlayerVisibilityGeneration) {
                                homePlayerPanel.setVisibility(View.GONE);
                            }
                        }).start();
                homePlayerBackdrop.animate().alpha(0f).setDuration(220)
                        .withEndAction(() -> {
                            if (visibilityGeneration == homePlayerVisibilityGeneration) {
                                homePlayerBackdrop.setVisibility(View.GONE);
                            }
                        }).start();
            } else {
                homePlayerPanel.setVisibility(View.GONE);
                homePlayerBackdrop.setVisibility(View.GONE);
            }
            if (grid != null) {
                grid.post(() -> {
                    if (focusPlayerTileOnCollapse) focusTile(lastFocused);
                    else refreshDockFocus.run();
                });
            }
        }

        handler.removeCallbacks(homePlayerProgressTick);
        if (show && homePlayerSnapshot.playing) {
            handler.postDelayed(homePlayerProgressTick, 1000);
        }
    }

    /** Rebuilds the tiles (and the module cards), then puts focus back where it was. */
    private void reloadApps(boolean animate) {
        // Capture the app before rebinding can move focus and overwrite its key.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final boolean restoreFocus = animate || grid.hasFocus();
        if (restoreFocus) {
            glassShown = false;
            glass.setAlpha(0f);
            heroShown = false;
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
        reloadTiles();
        if (animate && animations && !reducedMotion) grid.scheduleLayoutAnimation();

        if (restoreFocus) {
            // Follow the same app, or the nearest remaining slot when it was removed.
            restoreFocus();
        } else {
            // A package change must not steal focus from a top button or the Inputs popup.
            int pos = adapter.positionOf(returnKey);
            lastFocused = Math.max(0, Math.min(pos >= 0 ? pos : returnSlot, adapter.getItemCount() - 1));
            lastFocusedKey = adapter.keyAt(lastFocused);
            if (pos < 0) hideHero();
            // A newly installed app may have brought a card with it.
            markTilesReady();
            showCardPrompt();
        }
    }

    /**
     * Rebuilds the tiles; the module cards the owner chose to show stand in for their app's tile.
     * Every step into the card code runs under CardFeature: if it fails, the tiles are the ordinary
     * ones and cards stay off for this process.
     */
    private void reloadTiles() {
        // Read these before reload(): rebinding can move focus to the first tile and overwrite them.
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        final List<Cards.Card> none = Collections.emptyList();
        cards = moduleCards ? CardFeature.run(() -> Cards.discover(this, hiddenApps, Cards.load(Cards.storeFile(this))), none) : none;
        adapter.reload(cards.isEmpty() ? none : CardFeature.run(() -> Cards.shown(cards), none));
        lastFocusedKey = returnKey;
        lastFocused = returnSlot;
    }

    /**
     * Return to the app we left from, or the same slot if that app is gone. Always refocus: after
     * reload() the view that holds focus may no longer show the app it had.
     */
    private void restoreFocus() {
        final String returnKey = lastFocusedKey;
        final int returnSlot = lastFocused;
        grid.post(() -> {
            int pos = adapter.positionOf(returnKey);
            focusTile(pos >= 0 ? pos : returnSlot);
            markTilesReady();
            showCardPrompt();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        homeResumed = false;
        releaseCheckStarted = false;
        handler.removeCallbacks(launcherReleaseCheckTick);
        handler.removeCallbacks(backgroundUpdateCardTick);
        if (homePlayerTitleRow != null) {
            homePlayerTitleRow.setIndicatorPlaying(false, false);
            homePlayerTitleRow.setMarqueeEnabled(false);
        }
        cancelPendingHomePlayerSeek();
        handler.removeCallbacks(homeScreensaverIdle);
        removeHomeScreensaverImmediately();
        launcherWasResumedForDream = false;
        launcherPausedAtUptimeMs = android.os.SystemClock.uptimeMillis();
        nowPlayingTileFocused = false;
        if (activeInstance == this) activeInstance = null;
        spotifyPlaybackRequestGeneration++;
        spotifyPlaybackRequestRunning = false;
        handler.removeCallbacks(spotifyPlaybackTick);
        handler.removeCallbacks(homePlayerProgressTick);
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        handler.removeCallbacks(minuteTick);
        if (diya != null) diya.stop();
        // Nothing of ours stays on screen over another app; an undecided prompt simply returns next time.
        if (cardPrompt != null) cardPrompt.dismiss();
        if (openPopup != null) openPopup.dismiss();
    }

    private void hideHomeHeaderForOverlay() {
        if (homeHeader == null || homeHeaderVisibilityBeforeOverlay >= 0) return;
        homeHeaderVisibilityBeforeOverlay = homeHeader.getVisibility();
        homeHeader.setVisibility(View.INVISIBLE);
    }

    private void restoreHomeHeaderAfterOverlay() {
        if (homeHeader == null || homeHeaderVisibilityBeforeOverlay < 0) return;
        homeHeader.setVisibility(homeHeaderVisibilityBeforeOverlay);
        homeHeaderVisibilityBeforeOverlay = -1;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (nowPlayingReceiverRegistered) {
            unregisterReceiver(nowPlayingReceiver);
            nowPlayingReceiverRegistered = false;
        }
        // Package broadcasts are not delivered while this Activity is stopped (for example, while
        // Fire OS's app manager is open). Re-query on return so installs and removals appear at once.
        rebuildOnResume = true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            showCardPrompt();
            scheduleHomeScreensaver();
        }
    }

    @Override
    protected void onDestroy() {
        removeHomeScreensaverImmediately();
        if (geniusLyricsDialog != null) geniusLyricsDialog.dismiss();
        if (screensaverActivity.get() == this) {
            screensaverActivity = new WeakReference<>(null);
        }
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        spotifyPlaybackRequestGeneration++;
        lyricsRequestGeneration++;
        if (spotifyPlaybackExecutor != null) spotifyPlaybackExecutor.shutdownNow();
        for (ValueAnimator animation : welcomeDotAnimations) animation.cancel();
        welcomeDotAnimations.clear();
        if (packageReceiverRegistered) {
            unregisterReceiver(packageReceiver);
            packageReceiverRegistered = false;
        }
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
            cm.unregisterNetworkCallback(vpnCallback);
        }
    }

    @Override
    public void onBackPressed() {
        if (tutorialOverlay != null) {
            if (tutorialMenuOverlay != null) closeTutorialOptions();
            else showTutorialOptions();
            return;
        }
        if (homePlayerExpanded) setHomePlayerExpanded(false);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (homeScreensaverActive || homeScreensaverDismissing) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                    || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                return super.dispatchKeyEvent(event);
            }
            if (homeScreensaverActive && event.getAction() == KeyEvent.ACTION_DOWN) {
                dismissHomeScreensaver();
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) scheduleHomeScreensaver();
        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();
        boolean menuKey = keyCode == KeyEvent.KEYCODE_SETTINGS
                || keyCode == KeyEvent.KEYCODE_BUTTON_2 || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                || keyCode == KeyEvent.KEYCODE_MENU || scanCode == 150 || scanCode == 580;
        if (tutorialOverlay != null) {
            if (menuKey) {
                if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                    if (tutorialMenuOverlay == null) showTutorialOptions();
                    else closeTutorialOptions();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (tutorialMenuOverlay != null) closeTutorialOptions();
                    else showTutorialOptions();
                }
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && keyCode != KeyEvent.KEYCODE_DPAD_UP) {
            lastExpandedPlayerUpAt = 0;
        }
        if (homePlayerExpanded && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (event.getRepeatCount() > 0) return true;
            long now = android.os.SystemClock.uptimeMillis();
            boolean doublePress = lastExpandedPlayerUpAt > 0
                    && now - lastExpandedPlayerUpAt <= HOME_PLAYER_UP_DOUBLE_PRESS_MS;
            lastExpandedPlayerUpAt = doublePress ? 0 : now;
            if (doublePress) {
                homePlayerProgress.requestFocus();
            } else if (homePlayerProgress.hasFocus() || !isTopBarButton(getCurrentFocus())) {
                btnRecentApps.requestFocus();
            }
            return true;
        }
        if (homeEditMode && keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) setHomeEditMode(false);
            return true;
        }
        SpotifyPlaylistTrackEntry selectedTrack = focusedAddedSpotifyTrack;
        if (menuKey && selectedTrack != null && selectedTrack.addedByLauncher
                && selectedTrack.row.isFocused()) {
            if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                showAddedSpotifyTrackMenu(selectedTrack);
            }
            return true;
        }
        if (menuKey && adapter != null && adapter.isNowPlaying(lastFocused)) {
            if (event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0) {
                if (openPopup != null && openPopup.isShowing()) openPopup.dismiss();
                else showNowPlayingMenu();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_2 || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                || keyCode == KeyEvent.KEYCODE_MENU || scanCode == 150 || scanCode == 580) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                try {
                    Intent recents = new Intent(this, RecentAppsActivity.class);
                    recents.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(recents);
                } catch (Exception e) {
                    android.util.Log.e("AppleTv.HomeKey", "startActivity failed", e);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (homeScreensaverActive || homeScreensaverDismissing) {
            if (homeScreensaverActive
                    && event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                dismissHomeScreensaver();
            }
            return true;
        }
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            scheduleHomeScreensaver();
        }
        return super.dispatchTouchEvent(event);
    }

    // ---- glass highlight -------------------------------------------------------------------------

    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        lastFocusedKey = adapter.keyAt(position);
        // Tile rect in root coordinates, using layout positions (ignores the scale animation).
        View item = (View) card.getParent();
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        r.offset(Math.round(item.getTranslationX()), 0);
        float pad = 6f * density;        // matches GlassView's glow inset
        float scale = adapter.focusedScaleAt(position);
        float w = card.getWidth() * scale + 2 * pad;
        float h = card.getHeight() * scale + 2 * pad;
        float x = r.exactCenterX() - w / 2f;
        float y = r.exactCenterY() - h / 2f - (advancedEffects ? 4f * density : 0f);

        ViewGroup.LayoutParams lp = glass.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            glass.setLayoutParams(lp);
            // GlassView draws its slab inset by pad, i.e. exactly over the zoomed tile: same corners as the tile.
            glass.setRadius(AppAdapter.cornerRadius(card.getWidth(), density) * scale);
        }
        glassTargetX = x;
        glassTargetY = y;
        if (!glassShown) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
            if (animations) glass.animate().alpha(1f).setDuration(reducedMotion ? 100 : 180).start();
            else glass.setAlpha(1f);
            glassShown = true;
        } else if (!animations) {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        } else {
            springX.animateToFinalPosition(x);
            springY.animateToFinalPosition(y - glassScrollShift);
        }
        boolean playerTile = adapter.isNowPlaying(position);
        nowPlayingTileFocused = playerTile;
        String packageName = adapter.packageAt(position);
        int directionX = 0;
        int directionY = 0;
        if (lastShelfPosition != RecyclerView.NO_POSITION && lastShelfPosition != position) {
            int previousRow = lastShelfPosition / COLUMNS;
            int currentRow = position / COLUMNS;
            if (previousRow != currentRow) directionY = Integer.compare(currentRow, previousRow);
            else directionX = Integer.compare(position, lastShelfPosition);
        }
        lastShelfPosition = position;
        if (homePlayerExpanded || playerTile || scenicHomeWallpaper) {
            topShelfArtwork.setVisibility(View.GONE);
        } else {
            topShelfArtwork.show(packageName, adapter.bannerAt(position), adapter.iconAt(position),
                    adapter.ambientColorAt(position), animations && !reducedMotion, directionX, directionY);
        }
        if (position < COLUMNS) {
            // Dock: align it with the banner artwork and show the top shelf for this app.
            if (homePlayerExpanded || playerTile || scenicHomeWallpaper) hideContinueWatching();
            else loadContinueWatching(packageName);
            int decoratedTop = item.getTop() - (int) (6 * density);
            int dy = decoratedTop - grid.getPaddingTop();
            // A 1px mismatch used to start a scroll on every dock move. Only scroll when the row is
            // actually off; reduced motion does it in one jump so it does not fight the highlight.
            boolean scroll = Math.abs(dy) > (int) (8 * density);
            if (scroll) {
                if (!animations || reducedMotion) grid.scrollBy(0, dy);
                else grid.smoothScrollBy(0, dy);
            }
            if (homePlayerExpanded) hideHeroName();
            else showHero(position, scroll ? r.top - dy : r.top);
        } else {
            hideContinueWatching();
            hideHeroName();
            if (!homePlayerExpanded) updateAmbientForTile(adapter.ambientColorAt(position));
            else ambient.setAlpha(0f);
            // Scroll just enough so the whole glass (not only the tile) is visible.
            Rect want = new Rect(0, -(int) pad * 2, item.getWidth(), item.getHeight() + (int) pad);
            grid.requestChildRectangleOnScreen(item, want, reducedMotion);
        }
    }

    @Override
    public void onNowPlayingTileSelected() {
        toggleHomePlayerPlayback();
    }

    private void showNowPlayingMenu() {
        if (homePlayerSnapshot == null || homeNowPlayingDismissed || adapter == null
                || !adapter.isNowPlaying(lastFocused)) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(lastFocused);
        if (holder == null) return;
        View anchor = holder.itemView.findViewById(R.id.card);
        if (anchor == null) return;

        hideHeroName();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        panel.setBackgroundResource(LauncherUiSettings.isLight(this)
                ? R.drawable.quick_glass_panel_light : R.drawable.quick_glass_panel);
        ((TextView) panel.findViewById(R.id.popupTitle)).setText(R.string.now_playing_title);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - (int) (32 * density),
                Math.max(anchor.getWidth(), (int) (220 * density)));
        PopupWindow popup = glassPopup(panel, width);
        addNowPlayingMenuRow(rows, popup, R.drawable.ic_media_next,
                R.string.now_playing_next, () -> skipHomePlayerTrack(true));
        addNowPlayingMenuRow(rows, popup, R.drawable.ic_media_previous,
                R.string.now_playing_previous, () -> skipHomePlayerTrack(false));
        boolean spotifyPlayback = NowPlayingListener.isSpotifyPlayback(homePlayerSnapshot);
        if (spotifyPlayback) {
            addNowPlayingMenuRow(rows, popup, R.drawable.ic_folder,
                    R.string.spotify_playlist_add_track,
                    () -> showSpotifyAddToPlaylistPopup(anchor));
        }
        if (spotifyPlayback) {
            addNowPlayingMenuRow(rows, popup, R.drawable.ic_folder,
                    R.string.spotify_playlist_choose, () -> showSpotifyPlaylistPopup(anchor));
        }
        addNowPlayingMenuRow(rows, popup, R.drawable.ic_expand,
                homePlayerExpanded ? R.string.now_playing_shrink : R.string.now_playing_expand,
                () -> setHomePlayerExpanded(!homePlayerExpanded));
        boolean lyricsVisible = isHomePlayerLyricsVisible();
        addNowPlayingMenuRow(rows, popup, R.drawable.ic_music_note,
                lyricsVisible ? R.string.now_playing_lyrics_disable
                        : R.string.now_playing_lyrics,
                this::toggleHomePlayerLyricsVisibility);
        addNowPlayingMenuRow(rows, popup, R.drawable.ic_speaker,
                R.string.audio_output, () -> showAudioOutputPopup(anchor));
        addNowPlayingMenuRow(rows, popup, R.drawable.ic_close,
                R.string.now_playing_hide, this::showHomePlayerEndDialog);
        showPopupAboveAnchor(popup, panel, anchor, width);

        View firstRow = rows.getChildAt(0);
        firstRow.setFocusableInTouchMode(true);
        firstRow.post(() -> {
            if (popup.isShowing()) firstRow.requestFocus();
        });
    }

    static boolean routeMediaMenuKey() {
        MainActivity activity = activeInstance;
        if (activity == null || !activity.nowPlayingTileFocused) return false;
        activity.runOnUiThread(() -> {
            if (!activity.homeResumed || !activity.nowPlayingTileFocused
                    || activity.adapter == null || !activity.adapter.isNowPlaying(activity.lastFocused)) {
                return;
            }
            if (activity.openPopup != null && activity.openPopup.isShowing()) {
                activity.openPopup.dismiss();
            } else {
                activity.showNowPlayingMenu();
            }
        });
        return true;
    }

    private void addNowPlayingMenuRow(
            LinearLayout rows, PopupWindow popup, int iconRes, int titleRes, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(48 * density)));
        row.setId(View.generateViewId());
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(titleRes);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        View previous = rows.getChildAt(rows.getChildCount() - 1);
        if (previous != null) {
            if (previous.getId() == View.NO_ID) previous.setId(View.generateViewId());
            previous.setNextFocusDownId(row.getId());
            row.setNextFocusUpId(previous.getId());
        }
        rows.addView(row);
    }

    private void showNowPlayingLyrics() {
        if (!isHomePlayerLyricsEnabled()) return;
        NowPlayingListener.Snapshot snapshot = homePlayerSnapshot;
        if (snapshot == null) {
            showLyricsMessage(getString(R.string.now_playing_lyrics_unavailable));
            return;
        }

        String provider = LyricsProvider.selected(
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE));
        if (LyricsProvider.PLAYER.equals(provider)) {
            if (localizedPlayerLyrics(snapshot.lyrics) == null
                    || localizedPlayerLyrics(snapshot.lyrics).trim().isEmpty()) {
                showLyricsMessage(getString(R.string.now_playing_lyrics_unavailable));
            } else {
                setHomePlayerExpanded(true);
            }
            return;
        }
        if (LyricsProvider.LRCLIB.equals(provider)) {
            showLrclibLyrics(snapshot);
            return;
        }
        if (snapshot.title == null || snapshot.title.trim().isEmpty()
                || snapshot.artist == null || snapshot.artist.trim().isEmpty()) {
            showLyricsMessage(getString(R.string.now_playing_lyrics_unavailable));
            return;
        }
        if (LyricsProvider.GENIUS.equals(provider)) {
            setHomePlayerExpanded(true);
            openGeniusLyricsSearch(snapshot.title, snapshot.artist);
            return;
        }
        showMusixmatchLyrics(snapshot);
    }

    private void showLrclibLyrics(NowPlayingListener.Snapshot snapshot) {
        if (!isHomePlayerLyricsEnabled()) return;
        if (snapshot == null) {
            Toast.makeText(this, R.string.now_playing_lyrics_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String trackKey = homePlayerLyricsTrackKey(snapshot);
        if (snapshot.title == null || snapshot.title.trim().isEmpty()
                || snapshot.artist == null || snapshot.artist.trim().isEmpty()) {
            displayHomePlayerLyrics(trackKey, LyricsProvider.LRCLIB,
                    getString(R.string.now_playing_lyrics_unavailable), null, false);
            return;
        }
        if (lrclibRequestRunning && trackKey.equals(lrclibRequestTrackKey)) {
            if (!homePlayerExpanded) setHomePlayerExpanded(true);
            return;
        }
        if (trackKey.equals(fetchedHomeLyricsTrackKey)
                && LyricsProvider.LRCLIB.equals(fetchedHomeLyricsProvider)
                && !fetchedHomeLyricsRetryable) {
            if (!homePlayerExpanded) setHomePlayerExpanded(true);
            else renderHomePlayer();
            return;
        }

        long now = android.os.SystemClock.elapsedRealtime();
        long retryRemainingMs = lrclibRetryUntilUptimeMs - now;
        if (retryRemainingMs > 0) {
            String message = getString(R.string.lrclib_rate_limited,
                    lrclibRetrySeconds(retryRemainingMs));
            displayHomePlayerLyrics(trackKey, LyricsProvider.LRCLIB, message, null, true);
            return;
        }

        String title = snapshot.title;
        String artist = snapshot.artist;
        String album = snapshot.album;
        long durationMs = snapshot.durationMs;
        long requestGeneration = ++lyricsRequestGeneration;
        lrclibRequestRunning = true;
        lrclibRequestTrackKey = trackKey;
        displayHomePlayerLyrics(trackKey, LyricsProvider.LRCLIB,
                getString(R.string.lrclib_loading), null, false);

        LrclibLyricsClient client = new LrclibLyricsClient();
        LYRICS_EXECUTOR.execute(() -> {
            LrclibLyricsClient.Lyrics result = null;
            java.io.IOException failure = null;
            try {
                result = client.lookup(title, artist, album, durationMs);
            } catch (java.io.IOException exception) {
                failure = exception;
            }
            LrclibLyricsClient.Lyrics loadedLyrics = result;
            java.io.IOException requestFailure = failure;
            handler.post(() -> {
                if (requestGeneration == lyricsRequestGeneration
                        && trackKey.equals(lrclibRequestTrackKey)) {
                    lrclibRequestRunning = false;
                    lrclibRequestTrackKey = "";
                }
                if (requestFailure instanceof LrclibLyricsClient.ApiException
                        && ((LrclibLyricsClient.ApiException) requestFailure).retryAfterMs > 0) {
                    long delay = Math.max(1_000L,
                            ((LrclibLyricsClient.ApiException) requestFailure).retryAfterMs);
                    long currentUptime = android.os.SystemClock.elapsedRealtime();
                    lrclibRetryUntilUptimeMs = delay > Long.MAX_VALUE - currentUptime
                            ? Long.MAX_VALUE : currentUptime + delay;
                }
                if (requestGeneration != lyricsRequestGeneration
                        || isFinishing() || isDestroyed()) return;

                NowPlayingListener.Snapshot current = homePlayerSnapshot;
                if (current == null || !trackKey.equals(homePlayerLyricsTrackKey(current))
                        || !LyricsProvider.LRCLIB.equals(LyricsProvider.selected(
                        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)))) return;

                if (requestFailure != null) {
                    String message;
                    boolean retryable = true;
                    if (requestFailure instanceof LrclibLyricsClient.ApiException) {
                        LrclibLyricsClient.ApiException apiException =
                                (LrclibLyricsClient.ApiException) requestFailure;
                        if (apiException.retryAfterMs > 0) {
                            message = getString(R.string.lrclib_rate_limited,
                                    lrclibRetrySeconds(apiException.retryAfterMs));
                        } else {
                            message = getString(R.string.lrclib_http_error_format,
                                    apiException.statusCode);
                        }
                    } else {
                        android.util.Log.w("AppleTv.Lyrics",
                                "Could not load synchronized lyrics from LRCLIB", requestFailure);
                        message = getString(R.string.lrclib_error);
                    }
                    displayHomePlayerLyrics(trackKey, LyricsProvider.LRCLIB,
                            message, null, retryable);
                    return;
                }
                if (loadedLyrics == null || !loadedLyrics.isDisplayable()) {
                    String message = getString(R.string.lrclib_no_match);
                    displayHomePlayerLyrics(trackKey, LyricsProvider.LRCLIB,
                            message, null, false);
                    return;
                }
                displayHomePlayerLyrics(trackKey, LyricsProvider.LRCLIB,
                        "", loadedLyrics, false);
            });
        });
    }

    private int lrclibRetrySeconds(long delayMs) {
        long seconds = delayMs <= 0 ? 1 : 1 + (delayMs - 1) / 1000;
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    private void showMusixmatchLyrics(NowPlayingListener.Snapshot snapshot) {
        MusixmatchApiKeyStore keyStore = new MusixmatchApiKeyStore(this);
        String apiKey;
        try {
            apiKey = keyStore.readApiKey();
        } catch (java.security.GeneralSecurityException | org.json.JSONException
                 | java.io.IOException exception) {
            android.util.Log.e("AppleTv.Lyrics", "Could not read the Musixmatch API key", exception);
            showLyricsMessage(getString(R.string.musixmatch_api_key_store_error));
            return;
        }
        if (apiKey.isEmpty()) {
            setHomePlayerExpanded(true);
            Toast.makeText(this, R.string.musixmatch_key_missing_genius_fallback,
                    Toast.LENGTH_LONG).show();
            openGeniusLyricsSearch(snapshot.title, snapshot.artist);
            return;
        }

        String title = snapshot.title;
        String artist = snapshot.artist;
        String trackKey = homePlayerLyricsTrackKey(snapshot);
        long requestGeneration = ++lyricsRequestGeneration;
        displayHomePlayerLyrics(trackKey, getString(R.string.musixmatch_loading));

        MusixmatchLyricsClient client = new MusixmatchLyricsClient();
        LYRICS_EXECUTOR.execute(() -> {
            MusixmatchLyricsClient.Lyrics result = null;
            java.io.IOException failure = null;
            try {
                result = client.lookup(title, artist, apiKey);
                if (requestGeneration != lyricsRequestGeneration) return;
                if (result.isDisplayable()) client.trackView(result);
            } catch (java.io.IOException exception) {
                failure = exception;
            }
            MusixmatchLyricsClient.Lyrics loadedLyrics = result;
            java.io.IOException requestFailure = failure;
            handler.post(() -> {
                if (requestGeneration != lyricsRequestGeneration || isFinishing() || isDestroyed()) {
                    return;
                }
                NowPlayingListener.Snapshot current = homePlayerSnapshot;
                if (current == null || !trackKey.equals(homePlayerLyricsTrackKey(current))
                        || !LyricsProvider.MUSIXMATCH.equals(LyricsProvider.selected(
                        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)))) return;
                if (requestFailure != null) {
                    String message;
                    if (requestFailure instanceof MusixmatchLyricsClient.ApiException) {
                        message = getString(R.string.musixmatch_api_error_format,
                                ((MusixmatchLyricsClient.ApiException) requestFailure).statusCode);
                    } else {
                        android.util.Log.w("AppleTv.Lyrics", "Could not load Musixmatch lyrics",
                                requestFailure);
                        message = getString(R.string.musixmatch_error);
                    }
                    displayHomePlayerLyrics(trackKey, message);
                    showLyricsMessage(message);
                    return;
                }
                if (loadedLyrics == null || !loadedLyrics.isDisplayable()) {
                    String unavailable = loadedLyrics != null
                            && loadedLyrics.copyright != null
                            && !loadedLyrics.copyright.isEmpty()
                            ? loadedLyrics.copyright
                            : getString(R.string.musixmatch_no_match);
                    displayHomePlayerLyrics(trackKey, unavailable);
                    showLyricsMessage(unavailable);
                    return;
                }
                StringBuilder displayText = new StringBuilder(loadedLyrics.text.trim());
                if (loadedLyrics.copyright != null && !loadedLyrics.copyright.trim().isEmpty()) {
                    displayText.append("\n").append(loadedLyrics.copyright.trim());
                }
                displayText.append("\n").append(getString(R.string.musixmatch_attribution));
                displayHomePlayerLyrics(trackKey, displayText.toString());
            });
        });
    }

    private String homePlayerTrackKey(NowPlayingListener.Snapshot snapshot) {
        return (snapshot.spotifyRemote ? "spotify-connect" : snapshot.packageName)
                + "\n" + snapshot.title + "\n" + snapshot.artist;
    }

    private String homePlayerLyricsTrackKey(NowPlayingListener.Snapshot snapshot) {
        return homePlayerTrackKey(snapshot) + "\n" + snapshot.album + "\n"
                + snapshot.durationMs + "\n" + snapshot.spotifyTrackUri;
    }

    private void displayHomePlayerLyrics(String trackKey, String lyrics) {
        String provider = LyricsProvider.selected(
                getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE));
        displayHomePlayerLyrics(trackKey, provider, lyrics, null, false);
    }

    private void displayHomePlayerLyrics(String trackKey, String provider, String lyrics,
                                         LrclibLyricsClient.Lyrics syncedLyrics,
                                         boolean retryable) {
        NowPlayingListener.Snapshot current = homePlayerSnapshot;
        if (current == null || !trackKey.equals(homePlayerLyricsTrackKey(current))) return;
        fetchedHomeLyricsTrackKey = trackKey;
        fetchedHomeLyricsProvider = provider;
        fetchedHomeLyrics = lyrics;
        fetchedLrclibLyrics = syncedLyrics;
        fetchedHomeLyricsRetryable = retryable;
        if (!homePlayerExpanded) {
            setHomePlayerExpanded(true);
        } else {
            renderHomePlayer();
        }
    }

    private void showLyricsMessage(String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.now_playing_lyrics_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openLyricsProviderSettings() {
        Intent settings = new Intent(this, LauncherSettingsActivity.class);
        settings.putExtra(LauncherSettingsActivity.EXTRA_OPEN_LYRICS_SETTINGS, true);
        startActivity(settings);
    }

    private void openGeniusLyricsSearch(String title, String artist) {
        Uri searchUri = Uri.parse("https://genius.com/search").buildUpon()
                .appendQueryParameter("q", title + " " + artist)
                .build();
        if (geniusLyricsDialog != null) geniusLyricsDialog.dismiss();

        int width = Math.max(1, getResources().getDisplayMetrics().widthPixels
                - Math.round(64 * density));
        int height = Math.max(1, getResources().getDisplayMetrics().heightPixels
                - Math.round(64 * density));
        int contentHeight = Math.max(1, height - Math.round(150 * density));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new ViewGroup.LayoutParams(width, contentHeight));

        TextView status = new TextView(this);
        status.setText(R.string.genius_lyrics_loading);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        int statusPadding = Math.round(12 * density);
        status.setPadding(statusPadding, statusPadding / 2, statusPadding, statusPadding / 2);
        content.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        WebView webView = new WebView(this);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webSettings.setSafeBrowsingEnabled(true);
        content.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        boolean[] pageLoadFailed = {false};
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                return !isGeniusUrl(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                pageLoadFailed[0] = false;
                status.setText(R.string.genius_lyrics_loading);
                status.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!pageLoadFailed[0]) status.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(
                    WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showGeniusLoadError(status, pageLoadFailed);
            }

            @Override
            public void onReceivedHttpError(
                    WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    showGeniusLoadError(status, pageLoadFailed);
                }
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.now_playing_lyrics_title)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            webView.stopLoading();
            webView.destroy();
            if (geniusLyricsDialog == dialog) geniusLyricsDialog = null;
        });
        geniusLyricsDialog = dialog;
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(width, height);
        webView.requestFocus();
        webView.loadUrl(searchUri.toString());
    }

    private static boolean isGeniusUrl(Uri uri) {
        String host = uri.getHost();
        return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                && ("genius.com".equalsIgnoreCase(host)
                || host.toLowerCase(Locale.ROOT).endsWith(".genius.com"));
    }

    private void showGeniusLoadError(TextView status, boolean[] pageLoadFailed) {
        pageLoadFailed[0] = true;
        status.setText(R.string.genius_lyrics_load_error);
        status.setVisibility(View.VISIBLE);
    }

    private void showSpotifyPlaylistPopup(View anchor) {
        showSpotifyPlaylistPopup(anchor, null);
    }

    private void showSpotifyAddToPlaylistPopup(View anchor) {
        NowPlayingListener.Snapshot snapshot = homePlayerSnapshot;
        if (snapshot == null || !NowPlayingListener.isSpotifyPlayback(snapshot)) {
            Toast.makeText(this, R.string.spotify_playlist_track_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (snapshot.spotifyTrackUri != null) {
            showSpotifyPlaylistPopup(anchor, snapshot.spotifyTrackUri);
            return;
        }
        if (spotifyPlaybackExecutor == null || spotifyPlaybackExecutor.isShutdown()) {
            Toast.makeText(this, R.string.spotify_playlist_track_unavailable,
                    Toast.LENGTH_LONG).show();
            return;
        }

        String requestedTrackKey = homePlayerSeekTrackKey(snapshot);
        SpotifyRemotePlayback client = new SpotifyRemotePlayback(getApplicationContext());
        Toast.makeText(this, R.string.loading_please_wait, Toast.LENGTH_SHORT).show();
        spotifyPlaybackExecutor.execute(() -> {
            SpotifyConnectClient.Playback playback = null;
            Exception failure = null;
            try {
                playback = client.currentPlayback();
            } catch (GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                failure = exception;
            }
            SpotifyConnectClient.Playback currentPlayback = playback;
            Exception requestFailure = failure;
            handler.post(() -> {
                if (!homeResumed || isFinishing() || isDestroyed()) return;
                if (requestFailure != null) {
                    android.util.Log.w("AppleTv.SpotifyPlayback",
                            "Could not resolve the current Spotify track for a playlist", requestFailure);
                    Toast.makeText(this, R.string.spotify_playlist_track_unavailable,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                NowPlayingListener.Snapshot current = homePlayerSnapshot;
                if (current == null || !NowPlayingListener.isSpotifyPlayback(current)
                        || !Objects.equals(requestedTrackKey, homePlayerSeekTrackKey(current))) {
                    Toast.makeText(this, R.string.spotify_playlist_track_unavailable,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String trackUri = current.spotifyTrackUri;
                if (trackUri == null && currentPlayback != null) {
                    NowPlayingListener.Snapshot remoteSnapshot =
                            SpotifyRemotePlayback.snapshot(currentPlayback, null);
                    if (NowPlayingListener.isSameSpotifyTrack(current, remoteSnapshot)) {
                        trackUri = currentPlayback.trackUri;
                    }
                }
                if (trackUri == null) {
                    Toast.makeText(this, R.string.spotify_playlist_track_unavailable,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showSpotifyPlaylistPopup(anchor, trackUri);
            });
        });
    }

    private void showSpotifyPlaylistPopup(View anchor, String trackUri) {
        View panel = getLayoutInflater().inflate(
                R.layout.popup_spotify_playlists, root, false);
        panel.setBackgroundResource(LauncherUiSettings.isLight(this)
                ? R.drawable.quick_glass_panel_light : R.drawable.quick_glass_panel);
        ((TextView) panel.findViewById(R.id.spotifyPlaylistPopupTitle))
                .setText(trackUri == null
                        ? R.string.spotify_playlist_title
                        : R.string.spotify_playlist_add_track_title);
        TextView status = panel.findViewById(R.id.spotifyPlaylistPopupStatus);
        TextView createAction = panel.findViewById(R.id.spotifyPlaylistCreateAction);
        TextView action = panel.findViewById(R.id.spotifyPlaylistPopupAction);
        ScrollView playlistScroll = panel.findViewById(R.id.spotifyPlaylistScroll);
        LinearLayout playlistList = panel.findViewById(R.id.spotifyPlaylists);
        int margin = Math.round(16 * density);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - margin * 2,
                Math.max(Math.round(300 * density), anchor.getWidth() + Math.round(120 * density)));
        PopupWindow popup = glassPopup(panel, width);
        popup.setOnDismissListener(() -> spotifyPlaylistPopupGeneration++);
        createAction.setText(R.string.spotify_playlist_create);
        createAction.setOnClickListener(v -> promptCreateSpotifyPlaylist(
                popup, status, createAction, action, playlistScroll, playlistList,
                panel, anchor, width, trackUri));
        createAction.setNextFocusDownId(action.getId());
        action.setNextFocusUpId(createAction.getId());
        action.setText(R.string.spotify_playlist_refresh);
        action.setOnClickListener(v -> loadSpotifyPlaylists(
                popup, status, action, playlistScroll, playlistList, panel, anchor, width,
                0, false, trackUri));
        showSpotifyPlaylistPopupAboveAnchor(popup, panel, playlistScroll, anchor, width);
        loadSpotifyPlaylists(
                popup, status, action, playlistScroll, playlistList, panel, anchor, width,
                0, false, trackUri);
    }

    private void promptCreateSpotifyPlaylist(
            PopupWindow popup, TextView status, TextView createAction, TextView action,
            ScrollView playlistScroll, LinearLayout playlistList, View panel, View anchor,
            int width, String trackUri) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(R.string.spotify_playlist_name_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.spotify_playlist_create_title)
                .setView(input)
                .setPositiveButton(R.string.spotify_playlist_create,
                        (dialog, which) -> {
                            String name = input.getText().toString().trim();
                            if (name.isEmpty()) {
                                Toast.makeText(this, R.string.spotify_playlist_name_required,
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            createSpotifyPlaylist(name, popup, status, createAction, action,
                                    playlistScroll, playlistList, panel, anchor, width, trackUri);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createSpotifyPlaylist(
            String name, PopupWindow popup, TextView status, TextView createAction,
            TextView action, ScrollView playlistScroll, LinearLayout playlistList,
            View panel, View anchor, int width, String trackUri) {
        if (spotifyPlaybackExecutor == null || spotifyPlaybackExecutor.isShutdown()) {
            status.setText(R.string.not_available);
            return;
        }
        long requestGeneration = ++spotifyPlaylistPopupGeneration;
        status.setText(R.string.spotify_playlist_creating);
        createAction.setEnabled(false);
        action.setEnabled(false);
        SpotifyRemotePlayback client = new SpotifyRemotePlayback(getApplicationContext());
        spotifyPlaybackExecutor.execute(() -> {
            SpotifyConnectClient.Playlist created = null;
            Exception failure = null;
            try {
                created = client.createPlaylist(name);
            } catch (SpotifyConnectClient.ApiException exception) {
                failure = exception;
            } catch (java.security.GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                failure = exception;
            }
            SpotifyConnectClient.Playlist result = created;
            Exception requestFailure = failure;
            handler.post(() -> {
                if (requestGeneration != spotifyPlaylistPopupGeneration || !popup.isShowing()) return;
                createAction.setEnabled(true);
                action.setEnabled(true);
                if (requestFailure != null) {
                    android.util.Log.w("AppleTv.NowPlaying", "Could not create Spotify playlist",
                            requestFailure);
                    if (requestFailure instanceof SpotifyConnectClient.ApiException) {
                        int statusCode = ((SpotifyConnectClient.ApiException) requestFailure)
                                .statusCode;
                        if (statusCode == 401 || statusCode == 403) {
                            status.setText(R.string.spotify_playlist_permission_required);
                            action.setText(R.string.spotify_playlist_reauthorize);
                            action.setOnClickListener(v -> openSpotifyForPlaylists(popup, true));
                            return;
                        }
                        status.setText(getString(
                                R.string.spotify_playlist_create_api_error_format, statusCode));
                    } else {
                        String detail = requestFailure.getLocalizedMessage();
                        status.setText(getString(R.string.spotify_playlist_create_error_format,
                                detail == null ? requestFailure.getClass().getSimpleName() : detail));
                    }
                    return;
                }
                if (result == null) {
                    status.setText(R.string.spotify_playlist_create_failed);
                    return;
                }
                Toast.makeText(this, getString(
                        R.string.spotify_playlist_created_format, result.name),
                        Toast.LENGTH_LONG).show();
                loadSpotifyPlaylists(popup, status, action, playlistScroll, playlistList,
                        panel, anchor, width, 0, false, trackUri);
            });
        });
    }

    private void loadSpotifyPlaylists(
            PopupWindow popup, TextView status, TextView action, ScrollView playlistScroll,
            LinearLayout playlistList, View panel, View anchor, int width,
            int offset, boolean append, String trackUri) {
        if (spotifyPlaybackExecutor == null || spotifyPlaybackExecutor.isShutdown()) {
            status.setText(R.string.not_available);
            action.setEnabled(true);
            return;
        }

        long requestGeneration = ++spotifyPlaylistPopupGeneration;
        status.setText(R.string.spotify_playlist_loading);
        action.setEnabled(false);
        if (!append) {
            playlistScroll.setVisibility(View.GONE);
            playlistList.removeAllViews();
        }
        SpotifyRemotePlayback client = new SpotifyRemotePlayback(getApplicationContext());
        spotifyPlaybackExecutor.execute(() -> {
            SpotifyConnectClient.PlaylistPage page = null;
            Exception failure = null;
            try {
                page = client.playlists(offset);
            } catch (SpotifyConnectClient.ApiException exception) {
                failure = exception;
            } catch (java.security.GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                failure = exception;
            }
            SpotifyConnectClient.PlaylistPage result = page;
            Exception requestFailure = failure;
            handler.post(() -> {
                if (requestGeneration != spotifyPlaylistPopupGeneration || !popup.isShowing()) return;
                action.setEnabled(true);
                if (requestFailure != null) {
                    showSpotifyPlaylistLoadError(requestFailure, popup, status, action,
                            playlistScroll, playlistList, panel, anchor, width, trackUri);
                    return;
                }
                if (result == null) {
                    status.setText(R.string.spotify_playlist_connect_required);
                    action.setText(R.string.spotify_connect_action_setup);
                    action.setOnClickListener(v -> openSpotifyForPlaylists(popup, false));
                    showSpotifyPlaylistPopupAboveAnchor(
                            popup, panel, playlistScroll, anchor, width);
                    return;
                }

                int firstPageRow = playlistList.getChildCount();
                for (SpotifyConnectClient.Playlist playlist : result.playlists) {
                    TextView row = (TextView) getLayoutInflater().inflate(
                            R.layout.item_spotify_device, playlistList, false);
                    row.setText(playlist.name);
                    if (trackUri == null) {
                        row.setOnClickListener(v -> playSpotifyPlaylist(playlist, popup, status,
                                action, playlistScroll, playlistList, panel, anchor, width));
                    } else {
                        row.setOnClickListener(v -> {
                            Object tag = row.getTag();
                            if (tag instanceof SpotifyPlaylistTrackEntry) {
                                SpotifyPlaylistTrackEntry entry = (SpotifyPlaylistTrackEntry) tag;
                                status.setText(getString(entry.addedByLauncher
                                        ? R.string.spotify_playlist_track_added_status
                                        : R.string.spotify_playlist_track_already_present_status,
                                        playlist.name));
                                return;
                            }
                            addSpotifyTrackToPlaylist(playlist, trackUri, popup, status,
                                    action, playlistScroll, playlistList, panel, anchor, width, row);
                        });
                        row.setOnFocusChangeListener((view, hasFocus) -> {
                            Object tag = row.getTag();
                            if (hasFocus && tag instanceof SpotifyPlaylistTrackEntry
                                    && ((SpotifyPlaylistTrackEntry) tag).addedByLauncher) {
                                focusedAddedSpotifyTrack = (SpotifyPlaylistTrackEntry) tag;
                            } else if (!hasFocus && focusedAddedSpotifyTrack != null
                                    && focusedAddedSpotifyTrack.row == row) {
                                focusedAddedSpotifyTrack = null;
                            }
                        });
                    }
                    playlistList.addView(row);
                }

                if (playlistList.getChildCount() == 0) {
                    status.setText(R.string.spotify_playlist_empty);
                    playlistScroll.setVisibility(View.GONE);
                    action.setText(R.string.spotify_playlist_refresh);
                    action.setOnClickListener(v -> loadSpotifyPlaylists(
                            popup, status, action, playlistScroll, playlistList, panel, anchor,
                            width, 0, false, trackUri));
                    showSpotifyPlaylistPopupAboveAnchor(
                            popup, panel, playlistScroll, anchor, width);
                    return;
                }

                status.setText(R.string.spotify_playlist_choose);
                ViewGroup.LayoutParams scrollParams = playlistScroll.getLayoutParams();
                scrollParams.height = Math.min(Math.round(280 * density),
                        playlistList.getChildCount() * Math.round(64 * density));
                playlistScroll.setLayoutParams(scrollParams);
                playlistScroll.setVisibility(View.VISIBLE);
                if (result.hasMore()) {
                    action.setText(R.string.spotify_playlist_more);
                    action.setOnClickListener(v -> loadSpotifyPlaylists(
                            popup, status, action, playlistScroll, playlistList, panel, anchor,
                            width, result.nextOffset, true, trackUri));
                } else {
                    action.setText(R.string.spotify_playlist_refresh);
                    action.setOnClickListener(v -> loadSpotifyPlaylists(
                            popup, status, action, playlistScroll, playlistList, panel, anchor,
                            width, 0, false, trackUri));
                }
                linkVerticalFocus(playlistList);
                action.setNextFocusDownId(playlistList.getChildAt(0).getId());
                playlistList.getChildAt(0).setNextFocusUpId(action.getId());
                showSpotifyPlaylistPopupAboveAnchor(
                        popup, panel, playlistScroll, anchor, width);
                View firstRow = playlistList.getChildAt(firstPageRow);
                if (firstRow != null) {
                    firstRow.setFocusableInTouchMode(true);
                    firstRow.post(() -> {
                        if (popup.isShowing()) firstRow.requestFocus();
                    });
                }
            });
        });
    }

    private void showSpotifyPlaylistLoadError(
            Exception failure, PopupWindow popup, TextView status, TextView action,
            ScrollView playlistScroll, LinearLayout playlistList,
            View panel, View anchor, int width, String trackUri) {
        android.util.Log.w("AppleTv.NowPlaying", "Could not load Spotify playlists", failure);
        if (failure instanceof SpotifyConnectClient.ApiException) {
            int statusCode = ((SpotifyConnectClient.ApiException) failure).statusCode;
            if (statusCode == 401 || statusCode == 403) {
                status.setText(R.string.spotify_playlist_permission_required);
                action.setText(R.string.spotify_playlist_reauthorize);
                action.setEnabled(true);
                action.setOnClickListener(v -> openSpotifyForPlaylists(popup, true));
                showSpotifyPlaylistPopupAboveAnchor(
                        popup, panel, playlistScroll, anchor, width);
                return;
            }
            status.setText(getString(R.string.spotify_playlist_api_error_format, statusCode));
        } else {
            String detail = failure.getLocalizedMessage();
            status.setText(getString(R.string.spotify_playlist_error_format,
                    detail == null ? failure.getClass().getSimpleName() : detail));
        }
        action.setText(R.string.spotify_playlist_refresh);
        action.setEnabled(true);
        action.setOnClickListener(v -> loadSpotifyPlaylists(
                popup, status, action, playlistScroll, playlistList, panel, anchor, width,
                0, false, trackUri));
        showSpotifyPlaylistPopupAboveAnchor(popup, panel, playlistScroll, anchor, width);
    }

    private void addSpotifyTrackToPlaylist(
            SpotifyConnectClient.Playlist playlist, String trackUri, PopupWindow popup,
            TextView status, TextView action, ScrollView playlistScroll,
            LinearLayout playlistList, View panel, View anchor, int width, TextView row) {
        if (spotifyPlaybackExecutor == null || spotifyPlaybackExecutor.isShutdown()) {
            status.setText(R.string.not_available);
            return;
        }
        long requestGeneration = ++spotifyPlaylistPopupGeneration;
        status.setText(R.string.spotify_playlist_checking_track);
        action.setEnabled(false);
        for (int i = 0; i < playlistList.getChildCount(); i++) {
            playlistList.getChildAt(i).setEnabled(false);
        }
        SpotifyRemotePlayback client = new SpotifyRemotePlayback(getApplicationContext());
        spotifyPlaybackExecutor.execute(() -> {
            Boolean alreadyPresent = null;
            Exception failure = null;
            try {
                alreadyPresent = client.addTrackToPlaylist(playlist.uri, trackUri);
            } catch (SpotifyConnectClient.ApiException exception) {
                failure = exception;
            } catch (java.security.GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                failure = exception;
            }
            Boolean result = alreadyPresent;
            Exception requestFailure = failure;
            handler.post(() -> {
                if (requestGeneration != spotifyPlaylistPopupGeneration || !popup.isShowing()) return;
                action.setEnabled(true);
                for (int i = 0; i < playlistList.getChildCount(); i++) {
                    playlistList.getChildAt(i).setEnabled(true);
                }
                if (requestFailure != null) {
                    showSpotifyPlaylistAddError(requestFailure, popup, status, action,
                            playlistScroll, playlistList, panel, anchor, width, trackUri);
                    return;
                }
                boolean addedByLauncher = !Boolean.TRUE.equals(result);
                if (!addedByLauncher) {
                    row.setText(getString(R.string.spotify_playlist_track_already_present_format,
                            playlist.name));
                    status.setText(getString(
                            R.string.spotify_playlist_track_already_present_status, playlist.name));
                } else {
                    row.setText(getString(R.string.spotify_playlist_track_added_format,
                            playlist.name));
                    status.setText(getString(R.string.spotify_playlist_track_added_status,
                            playlist.name));
                }
                SpotifyPlaylistTrackEntry entry = new SpotifyPlaylistTrackEntry(
                        playlist, trackUri, row, status, popup, addedByLauncher);
                row.setTag(entry);
                row.setEnabled(true);
                if (row.hasFocus() && addedByLauncher) focusedAddedSpotifyTrack = entry;
                showSpotifyPlaylistPopupAboveAnchor(
                        popup, panel, playlistScroll, anchor, width);
            });
        });
    }

    private void showAddedSpotifyTrackMenu(SpotifyPlaylistTrackEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle(entry.playlist.name)
                .setItems(new String[]{getString(R.string.spotify_playlist_remove_track)},
                        (dialog, which) -> removeAddedSpotifyTrack(entry))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void removeAddedSpotifyTrack(SpotifyPlaylistTrackEntry entry) {
        if (spotifyPlaybackExecutor == null || spotifyPlaybackExecutor.isShutdown()) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        long requestGeneration = ++spotifyPlaylistPopupGeneration;
        entry.status.setText(R.string.spotify_playlist_removing_track);
        SpotifyRemotePlayback client = new SpotifyRemotePlayback(getApplicationContext());
        spotifyPlaybackExecutor.execute(() -> {
            Exception failure = null;
            try {
                client.removeTrackFromPlaylist(entry.playlist.uri, entry.trackUri);
            } catch (SpotifyConnectClient.ApiException exception) {
                failure = exception;
            } catch (java.security.GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                failure = exception;
            }
            Exception requestFailure = failure;
            handler.post(() -> {
                if (requestGeneration != spotifyPlaylistPopupGeneration
                        || !entry.popup.isShowing()) return;
                if (requestFailure != null) {
                    android.util.Log.w("AppleTv.NowPlaying",
                            "Could not remove track from Spotify playlist", requestFailure);
                    if (requestFailure instanceof SpotifyConnectClient.ApiException
                            && (((SpotifyConnectClient.ApiException) requestFailure).statusCode == 401
                            || ((SpotifyConnectClient.ApiException) requestFailure).statusCode == 403)) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.spotify_playlist_title)
                                .setMessage(R.string.spotify_playlist_permission_required)
                                .setPositiveButton(R.string.spotify_playlist_reauthorize,
                                        (dialog, which) -> openSpotifyForPlaylists(entry.popup, true))
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                    } else {
                        String detail = requestFailure.getLocalizedMessage();
                        entry.status.setText(getString(R.string.spotify_playlist_remove_error_format,
                                detail == null ? requestFailure.getClass().getSimpleName() : detail));
                    }
                    return;
                }
                if (focusedAddedSpotifyTrack == entry) focusedAddedSpotifyTrack = null;
                entry.row.setTag(null);
                entry.row.setText(entry.playlist.name);
                entry.status.setText(getString(
                        R.string.spotify_playlist_track_removed_status, entry.playlist.name));
            });
        });
    }

    private void showSpotifyPlaylistAddError(
            Exception failure, PopupWindow popup, TextView status, TextView action,
            ScrollView playlistScroll, LinearLayout playlistList,
            View panel, View anchor, int width, String trackUri) {
        android.util.Log.w("AppleTv.NowPlaying", "Could not add current track to Spotify playlist",
                failure);
        if (failure instanceof SpotifyConnectClient.ApiException) {
            int statusCode = ((SpotifyConnectClient.ApiException) failure).statusCode;
            if (statusCode == 401 || statusCode == 403) {
                status.setText(R.string.spotify_playlist_permission_required);
                action.setText(R.string.spotify_playlist_reauthorize);
                action.setEnabled(true);
                action.setOnClickListener(v -> openSpotifyForPlaylists(popup, true));
                showSpotifyPlaylistPopupAboveAnchor(
                        popup, panel, playlistScroll, anchor, width);
                return;
            }
            status.setText(getString(R.string.spotify_playlist_add_api_error_format, statusCode));
        } else {
            String detail = failure.getLocalizedMessage();
            status.setText(getString(R.string.spotify_playlist_add_error_format,
                    detail == null ? failure.getClass().getSimpleName() : detail));
        }
        action.setText(R.string.spotify_playlist_refresh);
        action.setEnabled(true);
        for (int i = 0; i < playlistList.getChildCount(); i++) {
            playlistList.getChildAt(i).setEnabled(true);
        }
        action.setOnClickListener(v -> loadSpotifyPlaylists(
                popup, status, action, playlistScroll, playlistList, panel, anchor, width,
                0, false, trackUri));
        showSpotifyPlaylistPopupAboveAnchor(popup, panel, playlistScroll, anchor, width);
    }

    private void linkVerticalFocus(LinearLayout list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row.getId() == View.NO_ID) row.setId(View.generateViewId());
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            if (i > 0) row.setNextFocusUpId(list.getChildAt(i - 1).getId());
            if (i + 1 < list.getChildCount()) {
                row.setNextFocusDownId(list.getChildAt(i + 1).getId());
            }
        }
    }

    private void playSpotifyPlaylist(
            SpotifyConnectClient.Playlist playlist, PopupWindow popup, TextView status,
            TextView action, ScrollView playlistScroll, LinearLayout playlistList,
            View panel, View anchor, int width) {
        if (spotifyPlaybackExecutor == null || spotifyPlaybackExecutor.isShutdown()) {
            status.setText(R.string.not_available);
            return;
        }
        long requestGeneration = ++spotifyPlaylistPopupGeneration;
        action.setEnabled(false);
        for (int i = 0; i < playlistList.getChildCount(); i++) {
            playlistList.getChildAt(i).setEnabled(false);
        }
        status.setText(R.string.spotify_playlist_starting);
        String deviceId = homePlayerSnapshot == null
                ? null : homePlayerSnapshot.spotifyDeviceId;
        SpotifyRemotePlayback client = new SpotifyRemotePlayback(getApplicationContext());
        spotifyPlaybackExecutor.execute(() -> {
            Exception failure = null;
            try {
                client.playPlaylist(playlist.uri, deviceId);
            } catch (SpotifyConnectClient.ApiException exception) {
                failure = exception;
            } catch (java.security.GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                failure = exception;
            }
            Exception requestFailure = failure;
            handler.post(() -> {
                if (requestGeneration != spotifyPlaylistPopupGeneration || !popup.isShowing()) return;
                if (requestFailure != null) {
                    showSpotifyPlaylistPlaybackError(requestFailure, popup, status, action,
                            playlistScroll, playlistList, panel, anchor, width);
                    return;
                }
                popup.dismiss();
                Toast.makeText(this, getString(
                        R.string.spotify_playlist_started_format, playlist.name),
                        Toast.LENGTH_LONG).show();
                handler.postDelayed(this::refreshSpotifyRemotePlayback, 700L);
            });
        });
    }

    private void showSpotifyPlaylistPlaybackError(
            Exception failure, PopupWindow popup, TextView status, TextView action,
            ScrollView playlistScroll, LinearLayout playlistList,
            View panel, View anchor, int width) {
        android.util.Log.w("AppleTv.NowPlaying", "Could not start Spotify playlist", failure);
        if (failure instanceof SpotifyConnectClient.ApiException
                && ((SpotifyConnectClient.ApiException) failure).statusCode == 401) {
            status.setText(R.string.spotify_playlist_permission_required);
            action.setText(R.string.spotify_playlist_reauthorize);
            action.setOnClickListener(v -> openSpotifyForPlaylists(popup, true));
        } else if (failure instanceof SpotifyConnectClient.ApiException
                && ((SpotifyConnectClient.ApiException) failure).statusCode == 403) {
            status.setText(R.string.spotify_connect_error_premium);
            action.setText(R.string.spotify_playlist_refresh);
            action.setOnClickListener(v -> loadSpotifyPlaylists(
                    popup, status, action, playlistScroll, playlistList, panel, anchor, width,
                    0, false, null));
        } else {
            String detail = failure.getLocalizedMessage();
            status.setText(getString(R.string.spotify_playlist_play_error_format,
                    detail == null ? failure.getClass().getSimpleName() : detail));
            action.setText(R.string.spotify_playlist_refresh);
            action.setOnClickListener(v -> loadSpotifyPlaylists(
                    popup, status, action, playlistScroll, playlistList, panel, anchor, width,
                    0, false, null));
        }
        action.setEnabled(true);
        for (int i = 0; i < playlistList.getChildCount(); i++) {
            playlistList.getChildAt(i).setEnabled(true);
        }
        showSpotifyPlaylistPopupAboveAnchor(popup, panel, playlistScroll, anchor, width);
    }

    private void openSpotifyForPlaylists(PopupWindow popup, boolean reauthorize) {
        popup.dismiss();
        Intent intent = new Intent(this, SpotifyConnectActivity.class);
        if (reauthorize) {
            intent.putExtra(SpotifyConnectActivity.EXTRA_REAUTHORIZE, true);
        }
        startActivity(intent);
    }

    private void showAudioOutputPopup(View anchor) {
        boolean spotifyPlayback = NowPlayingListener.isSpotifyPlayback(homePlayerSnapshot);
        View panel = getLayoutInflater().inflate(R.layout.popup_audio_output, root, false);
        panel.setBackgroundResource(LauncherUiSettings.isLight(this)
                ? R.drawable.quick_glass_panel_light : R.drawable.quick_glass_panel);
        TextView title = panel.findViewById(R.id.audioOutputPopupTitle);
        TextView bluetooth = panel.findViewById(R.id.audioOutputBluetooth);
        TextView status = panel.findViewById(R.id.audioOutputPopupStatus);
        TextView action = panel.findViewById(R.id.audioOutputPopupAction);
        ScrollView deviceScroll = panel.findViewById(R.id.audioOutputDeviceScroll);
        LinearLayout deviceList = panel.findViewById(R.id.audioOutputDevices);
        int margin = Math.round(16 * density);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - margin * 2,
                Math.max(Math.round(300 * density), anchor.getWidth() + Math.round(120 * density)));
        PopupWindow popup = glassPopup(panel, width);

        bluetooth.setOnClickListener(v -> {
            popup.dismiss();
            openBluetoothAudioSettings();
        });
        if (spotifyPlayback) {
            title.setText(R.string.spotify_connect_title);
            status.setText(R.string.spotify_connect_status_loading);
            action.setVisibility(View.VISIBLE);
            action.setText(R.string.spotify_connect_action_refresh);
            action.setEnabled(false);
            action.setOnClickListener(v ->
                    loadAudioOutputDevices(
                            popup, status, action, deviceScroll, deviceList, panel, anchor, width));
        } else {
            title.setText(R.string.audio_output_title);
            status.setText(R.string.audio_output_bluetooth_only_hint);
            action.setVisibility(View.GONE);
            deviceScroll.setVisibility(View.GONE);
        }

        showAudioOutputPopupAboveAnchor(popup, panel, anchor, width);
        if (spotifyPlayback) {
            loadAudioOutputDevices(
                    popup, status, action, deviceScroll, deviceList, panel, anchor, width);
        } else {
            bluetooth.requestFocus();
        }
    }

    private void loadAudioOutputDevices(
            PopupWindow popup, TextView status, TextView action,
            ScrollView deviceScroll, LinearLayout deviceList,
            View panel, View anchor, int width) {
        if (spotifyPlaybackExecutor == null || spotifyPlaybackExecutor.isShutdown()) {
            status.setText(R.string.not_available);
            action.setEnabled(true);
            return;
        }
        long requestGeneration = ++audioOutputPopupGeneration;
        status.setText(R.string.spotify_connect_status_loading);
        action.setEnabled(false);
        deviceScroll.setVisibility(View.GONE);
        deviceList.removeAllViews();
        SpotifyRemotePlayback client = new SpotifyRemotePlayback(getApplicationContext());
        spotifyPlaybackExecutor.execute(() -> {
            List<SpotifyConnectClient.Device> devices = null;
            Exception failure = null;
            try {
                devices = client.availableDevices();
            } catch (SpotifyConnectClient.ApiException exception) {
                failure = exception;
            } catch (java.security.GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                failure = exception;
            }
            List<SpotifyConnectClient.Device> result = devices;
            Exception requestFailure = failure;
            handler.post(() -> {
                if (requestGeneration != audioOutputPopupGeneration || !popup.isShowing()) return;
                action.setEnabled(true);
                if (requestFailure != null) {
                    showAudioOutputError(status, action, requestFailure);
                    showAudioOutputPopupAboveAnchor(popup, panel, anchor, width);
                    return;
                }
                if (result == null) {
                    status.setText(R.string.spotify_connect_status_setup);
                    action.setText(R.string.spotify_connect_action_setup);
                    action.setOnClickListener(v -> {
                        popup.dismiss();
                        startActivity(new Intent(this, SpotifyConnectActivity.class));
                    });
                    showAudioOutputPopupAboveAnchor(popup, panel, anchor, width);
                    return;
                }

                action.setText(R.string.spotify_connect_action_refresh);
                action.setOnClickListener(v ->
                        loadAudioOutputDevices(
                                popup, status, action, deviceScroll, deviceList,
                                panel, anchor, width));
                if (result.isEmpty()) {
                    status.setText(R.string.spotify_connect_status_empty);
                    showAudioOutputPopupAboveAnchor(popup, panel, anchor, width);
                    return;
                }

                status.setText(R.string.spotify_connect_status_ready);
                for (SpotifyConnectClient.Device device : result) {
                    TextView row = (TextView) getLayoutInflater().inflate(
                            R.layout.item_spotify_device, deviceList, false);
                    String label = getString(device.active
                                    ? R.string.spotify_connect_device_active_format
                                    : R.string.spotify_connect_device_format,
                            device.name, device.type);
                    if (device.local) {
                        label += " · " + getString(R.string.spotify_connect_device_local);
                    } else if (device.restricted) {
                        label += " · " + getString(R.string.spotify_connect_device_restricted);
                    }
                    row.setText(label);
                    row.setEnabled(device.id != null && !device.restricted);
                    row.setOnClickListener(v ->
                            transferAudioOutputToDevice(device, popup, status, action,
                                    deviceScroll, deviceList, panel, anchor, width));
                    deviceList.addView(row);
                }
                ViewGroup.LayoutParams scrollParams = deviceScroll.getLayoutParams();
                scrollParams.height = Math.min(Math.round(240 * density),
                        deviceList.getChildCount() * Math.round(64 * density));
                deviceScroll.setLayoutParams(scrollParams);
                deviceScroll.setVisibility(View.VISIBLE);
                showAudioOutputPopupAboveAnchor(popup, panel, anchor, width);
                View firstDevice = deviceList.getChildAt(0);
                firstDevice.setFocusableInTouchMode(true);
                firstDevice.post(() -> {
                    if (popup.isShowing()) firstDevice.requestFocus();
                });
            });
        });
    }

    private void transferAudioOutputToDevice(
            SpotifyConnectClient.Device device, PopupWindow popup, TextView status, TextView action,
            ScrollView deviceScroll, LinearLayout deviceList,
            View panel, View anchor, int width) {
        if (device.id == null || device.restricted || spotifyPlaybackExecutor == null
                || spotifyPlaybackExecutor.isShutdown()) return;
        if (device.local) {
            status.setText(R.string.spotify_connect_error_local_device);
            return;
        }
        NowPlayingListener.Snapshot snapshotBeforeTransfer = homePlayerSnapshot;
        boolean resumePlayback = NowPlayingListener.isSpotifyPlayback(snapshotBeforeTransfer)
                && snapshotBeforeTransfer.playing;
        status.setText(R.string.spotify_connect_status_loading);
        action.setEnabled(false);
        for (int i = 0; i < deviceList.getChildCount(); i++) {
            deviceList.getChildAt(i).setEnabled(false);
        }
        SpotifyRemotePlayback client = new SpotifyRemotePlayback(getApplicationContext());
        spotifyPlaybackExecutor.execute(() -> {
            Exception failure = null;
            try {
                client.transferPlayback(device.id, resumePlayback);
            } catch (SpotifyConnectClient.ApiException exception) {
                failure = exception;
            } catch (java.security.GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                failure = exception;
            }
            Exception transferFailure = failure;
            handler.post(() -> {
                if (!popup.isShowing()) return;
                action.setEnabled(true);
                for (int i = 0; i < deviceList.getChildCount(); i++) {
                    deviceList.getChildAt(i).setEnabled(true);
                }
                if (transferFailure != null) {
                    showAudioOutputError(status, action, transferFailure);
                    showAudioOutputPopupAboveAnchor(popup, panel, anchor, width);
                    return;
                }
                beginAudioOutputSwitchGrace(snapshotBeforeTransfer);
                status.setText(getString(R.string.spotify_connect_status_transferred, device.name));
                showAudioOutputPopupAboveAnchor(popup, panel, anchor, width);
                handler.postDelayed(() -> {
                    if (!popup.isShowing()) return;
                    loadAudioOutputDevices(
                            popup, status, action, deviceScroll, deviceList, panel, anchor, width);
                }, 1200);
            });
        });
    }

    private void showAudioOutputError(TextView status, TextView action, Exception exception) {
        action.setEnabled(true);
        action.setText(R.string.spotify_connect_action_refresh);
        if (exception instanceof SpotifyConnectClient.ApiException) {
            SpotifyConnectClient.ApiException apiException =
                    (SpotifyConnectClient.ApiException) exception;
            android.util.Log.w("AppleTv.SpotifyConnect",
                    "Spotify API request failed with HTTP " + apiException.statusCode);
            if (apiException.statusCode == 403) {
                status.setText(R.string.spotify_connect_error_premium);
            } else if (apiException.statusCode == 429) {
                status.setText(R.string.spotify_connect_error_rate_limit);
            } else {
                status.setText(getString(R.string.spotify_connect_error_api,
                        apiException.statusCode));
            }
        } else if (exception instanceof java.io.IOException) {
            android.util.Log.w("AppleTv.SpotifyConnect",
                    "Spotify network request failed", exception);
            String detail = exception.getLocalizedMessage();
            status.setText(getString(R.string.spotify_connect_error_network,
                    detail == null ? exception.getClass().getSimpleName() : detail));
        } else {
            android.util.Log.e("AppleTv.SpotifyConnect",
                    "Could not read Spotify authorization data", exception);
            status.setText(R.string.spotify_connect_error_storage);
        }
    }

    private void openBluetoothAudioSettings() {
        NowPlayingListener.Snapshot snapshot = NowPlayingListener.snapshot();
        audioOutputResumeSnapshot = snapshot != null && snapshot.playing ? snapshot : null;
        try {
            startActivity(new Intent("android.settings.CONTROLLERS_SETTINGS"));
        } catch (ActivityNotFoundException | SecurityException exception) {
            audioOutputResumeSnapshot = null;
            android.util.Log.e("AppleTv.AudioOutput",
                    "Could not open Fire TV Bluetooth settings", exception);
            Toast.makeText(this, R.string.audio_output_settings_unavailable,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showHomePlayerEndDialog() {
        SharedPreferences preferences = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        if (preferences.getBoolean(HOME_PLAYER_END_PENDING_KEY, false)) {
            boolean keepBackground = preferences.getBoolean(
                    HOME_PLAYER_END_KEEP_BACKGROUND_KEY, true);
            preferences.edit()
                    .remove(HOME_PLAYER_END_PENDING_KEY)
                    .remove(HOME_PLAYER_END_KEEP_BACKGROUND_KEY)
                    .apply();
            applyHomePlayerEndChoice(keepBackground);
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(24 * density), Math.round(8 * density),
                Math.round(24 * density), Math.round(8 * density));
        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(RadioGroup.VERTICAL);
        RadioButton keepBackground = new RadioButton(this);
        keepBackground.setId(View.generateViewId());
        keepBackground.setText(R.string.now_playing_end_keep_background);
        RadioButton hideAll = new RadioButton(this);
        hideAll.setId(View.generateViewId());
        hideAll.setText(R.string.now_playing_end_hide_all);
        choices.addView(keepBackground);
        choices.addView(hideAll);
        choices.check(keepBackground.getId());
        CheckBox remember = new CheckBox(this);
        remember.setText(R.string.now_playing_end_remember);
        content.addView(choices);
        content.addView(remember);

        new AlertDialog.Builder(this)
                .setTitle(R.string.now_playing_end_title)
                .setMessage(R.string.now_playing_end_message)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.now_playing_end_confirm, (dialog, which) -> {
                    boolean keep = choices.getCheckedRadioButtonId() == keepBackground.getId();
                    if (remember.isChecked()) {
                        preferences.edit()
                                .putBoolean(HOME_PLAYER_END_PENDING_KEY, true)
                                .putBoolean(HOME_PLAYER_END_KEEP_BACKGROUND_KEY, keep)
                                .apply();
                    } else {
                        preferences.edit()
                                .remove(HOME_PLAYER_END_PENDING_KEY)
                                .remove(HOME_PLAYER_END_KEEP_BACKGROUND_KEY)
                                .apply();
                    }
                    applyHomePlayerEndChoice(keep);
                })
                .show();
    }

    private void applyHomePlayerEndChoice(boolean keepBackground) {
        homeNowPlayingDismissed = true;
        setHomePlayerExpanded(keepBackground);
        refreshHomeNowPlaying();
    }

    private void showAudioOutputPopupAboveAnchor(
            PopupWindow popup, View panel, View anchor, int width) {
        ScrollView deviceScroll = panel.findViewById(R.id.audioOutputDeviceScroll);
        showScrollablePopupAboveAnchor(popup, panel, deviceScroll, anchor, width);
    }

    private void showSpotifyPlaylistPopupAboveAnchor(
            PopupWindow popup, View panel, ScrollView playlistScroll, View anchor, int width) {
        showScrollablePopupAboveAnchor(popup, panel, playlistScroll, anchor, width);
    }

    private void showScrollablePopupAboveAnchor(
            PopupWindow popup, View panel, ScrollView scroll, View anchor, int width) {
        int margin = Math.round(16 * density);
        int gap = Math.round(12 * density);
        int[] anchorLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        int availableHeight = Math.max(0, anchorLocation[1] - margin - gap);

        panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int measuredHeight = panel.getMeasuredHeight();
        if (scroll.getVisibility() == View.VISIBLE && measuredHeight > availableHeight) {
            ViewGroup.LayoutParams scrollParams = scroll.getLayoutParams();
            int fixedHeight = measuredHeight - scroll.getMeasuredHeight();
            scrollParams.height = Math.max(0, Math.min(scrollParams.height,
                    availableHeight - fixedHeight));
            scroll.setLayoutParams(scrollParams);
            panel.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            measuredHeight = panel.getMeasuredHeight();
        }

        int popupHeight = Math.min(measuredHeight, availableHeight);
        popup.setHeight(popupHeight);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int x = anchorLocation[0] + anchor.getWidth() / 2 - width / 2;
        x = Math.max(margin, Math.min(x, screenWidth - width - margin));
        int y = Math.max(margin, anchorLocation[1] - popupHeight - gap);
        if (popup.isShowing()) {
            popup.update(x, y, width, popupHeight);
            return;
        }

        panel.setPivotY(popupHeight);
        panel.setAlpha(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(8 * density);
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void showPopupAboveAnchor(PopupWindow popup, View panel, View anchor, int width) {
        panel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int popupHeight = panel.getMeasuredHeight();
        int[] anchorLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        int margin = Math.round(16 * density);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int x = anchorLocation[0] + anchor.getWidth() / 2 - width / 2;
        x = Math.max(margin, Math.min(x, screenWidth - width - margin));
        int y = Math.max(margin, anchorLocation[1] - popupHeight - Math.round(12 * density));
        y = Math.min(y, screenHeight - popupHeight - margin);
        panel.setPivotY(popupHeight);
        panel.setAlpha(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(8 * density);
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void toggleHomePlayerPlayback() {
        NowPlayingListener.Snapshot snapshot = homePlayerSnapshot;
        if (snapshot == null) return;
        if (snapshot.spotifyRemote) {
            controlSpotifyPlayback(snapshot.playing
                    ? SpotifyConnectClient.PlaybackCommand.PAUSE
                    : SpotifyConnectClient.PlaybackCommand.PLAY, snapshot.spotifyDeviceId);
        } else if (snapshot.controller != null) {
            if (snapshot.playing) snapshot.controller.getTransportControls().pause();
            else snapshot.controller.getTransportControls().play();
        }
    }

    private void skipHomePlayerTrack(boolean next) {
        NowPlayingListener.Snapshot snapshot = homePlayerSnapshot;
        if (snapshot == null) return;
        if (snapshot.spotifyRemote) {
            controlSpotifyPlayback(next
                    ? SpotifyConnectClient.PlaybackCommand.NEXT
                    : SpotifyConnectClient.PlaybackCommand.PREVIOUS, snapshot.spotifyDeviceId);
        } else if (snapshot.controller != null) {
            if (next) snapshot.controller.getTransportControls().skipToNext();
            else snapshot.controller.getTransportControls().skipToPrevious();
        }
    }

    private void controlSpotifyPlayback(
            SpotifyConnectClient.PlaybackCommand command, String deviceId) {
        if (spotifyPlaybackExecutor == null || spotifyPlaybackExecutor.isShutdown()) return;
        spotifyPlaybackExecutor.execute(() -> {
            try {
                new SpotifyRemotePlayback(getApplicationContext())
                        .controlPlayback(command, deviceId);
                handler.post(() -> {
                    if (!homeResumed || isFinishing() || isDestroyed()) return;
                    handler.removeCallbacks(spotifyPlaybackTick);
                    handler.postDelayed(spotifyPlaybackTick, 1000);
                });
            } catch (GeneralSecurityException | org.json.JSONException
                     | java.io.IOException exception) {
                android.util.Log.w("AppleTv.SpotifyPlayback",
                        "Spotify playback control failed", exception);
                handler.post(() -> {
                    if (homeResumed && !isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.spotify_remote_control_error,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    // ---- top shelf -------------------------------------------------------------------------------

    private void showHero(int position, float dockTopInRoot) {
        if (scenicHomeWallpaper || homePlayerExpanded) {
            hideHeroName();
            return;
        }
        String label = adapter.labelAt(position);
        int color = adapter.ambientColorAt(position);
        Rect panelBounds = new Rect(0, 0, clockPanel.getWidth(), clockPanel.getHeight());
        root.offsetDescendantRectToMyCoords(clockPanel, panelBounds);
        float y = Math.max(dockTopInRoot - 14 * density - 26 * density - heroName.getHeight(),
                panelBounds.bottom + 8 * density);
        boolean changed = !label.contentEquals(heroName.getText());

        if (!animations || reducedMotion) {
            // Snap the shelf instead of sliding and fading it: each of those frames repaints the
            // full-screen panel, and this switch exists for the GPUs that cannot afford it.
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.setAlpha(1f);
            setAmbient(color);
            if (!animations) {
                ambient.setAlpha(1f);
                heroShown = true;
            } else if (!heroShown) {
                ambient.setAlpha(0f);
                ambient.animate().alpha(1f).setDuration(160).start();
                heroShown = true;
            }
            return;
        }

        if (!heroShown) {
            stopAmbientAnimation();
            heroName.animate().cancel();
            ambient.animate().cancel();
            heroName.setText(label);
            heroName.setY(y);
            heroName.setTranslationX(0f);
            heroName.animate().alpha(1f).setDuration(260).start();
            setAmbient(color);
            ambient.animate().alpha(1f).setDuration(600).start();
            heroShown = true;
        } else if (changed) {
            if (advancedEffects) heroName.animate().cancel();
            heroName.animate().alpha(0f).translationX(-12 * density).setDuration(120).withEndAction(() -> {
                heroName.setText(label);
                heroName.setY(y);
                heroName.setTranslationX(12 * density);
                heroName.animate().alpha(1f).translationX(0f).setDuration(200).start();
            }).start();
            if (advancedEffects) {
                transitionAmbient(color);
            }             else setAmbient(color);
        } else {
            heroName.setY(y);
        }
    }

    /**
     * The glow used to be a full-screen radial GradientDrawable. On Android 10 that drawable is
     * drawn on the CPU, so fading it for half a second redrew 1920×1080 every frame and the
     * highlight looked like it was running at a few frames per second. A small bitmap stretched
     * over the screen is one texture. It is painted once in white and tinted with the app's colour,
     * so changing apps neither repaints it on the CPU (about 11 ms) nor uploads it again.
     */
    private void setAmbient(int color) {
        if (color == ambientColor && ambient.getBackground() != null) return;
        ambientColor = color;
        BitmapDrawable glow = (BitmapDrawable) ambient.getBackground();
        if (glow == null) {
            int w = 480;
            int h = 270;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setShader(new RadialGradient(
                    w * 0.32f, h * 0.42f, w * 0.62f,
                    new int[]{0x5CFFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, paint);
            glow = new BitmapDrawable(getResources(), bmp);
            glow.setGravity(Gravity.FILL);
            ambient.setBackground(glow);
        }
        // The default tint mode, SRC_IN, keeps the mask's alpha and takes the colour from the tint.
        glow.setTint(0xFF000000 | color);
    }

    private void transitionAmbient(int color) {
        // A rapid focus change continues from the colour actually displayed on screen.
        stopAmbientAnimation();
        ambient.animate().cancel();
        if (color == ambientColor) {
            ambient.animate().alpha(1f).setDuration(180).start();
            return;
        }
        ambient.animate().alpha(1f).setDuration(180).start();
        ambientAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), ambientColor, color);
        ambientAnimator.setDuration(360);
        ambientAnimator.addUpdateListener(animation -> setAmbient((int) animation.getAnimatedValue()));
        ambientAnimator.start();
    }

    private void stopAmbientAnimation() {
        if (ambientAnimator != null) {
            ambientAnimator.cancel();
            ambientAnimator = null;
        }
    }

    private void hideHeroName() {
        if (!heroShown) return;
        heroName.animate().cancel();
        heroShown = false;
        if (animations) {
            heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
        } else {
            heroName.setAlpha(0f);
        }
    }

    private void updateAmbientForTile(int color) {
        if (!animations || reducedMotion) {
            ambient.animate().cancel();
            setAmbient(color);
            ambient.setAlpha(1f);
        } else if (advancedEffects) {
            transitionAmbient(color);
        } else if (color != ambientColor) {
            ambient.animate().alpha(0.35f).setDuration(160).withEndAction(() -> {
                setAmbient(color);
                ambient.animate().alpha(1f).setDuration(350).start();
            }).start();
        } else {
            ambient.animate().alpha(1f).setDuration(160).start();
        }
    }

    private void hideHero() {
        topShelfArtwork.hide(animations && !reducedMotion);
        hideContinueWatching();
        if (!heroShown) return;
        stopAmbientAnimation();
        heroName.animate().cancel();
        ambient.animate().cancel();
        heroShown = false;
        if (animations) {
            heroName.animate().alpha(0f).setDuration(reducedMotion ? 100 : 160).start();
            ambient.animate().alpha(0f).setDuration(reducedMotion ? 140 : 300).start();
        } else {
            heroName.setAlpha(0f);
            ambient.setAlpha(0f);
        }
    }

    private void hideGlass() {
        if (!glassShown) return;
        glassShown = false;
        glass.animate().cancel();
        if (animations) glass.animate().alpha(0f).setDuration(160).start();
        else glass.setAlpha(0f);
    }

    private void positionDockAtBottom() {
        if (grid == null || adapter == null || grid.getHeight() == 0) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(0);
        RecyclerView.LayoutManager layoutManager = grid.getLayoutManager();
        if (holder == null || layoutManager == null) return;

        int decoratedHeight = layoutManager.getDecoratedMeasuredHeight(holder.itemView);
        if (decoratedHeight <= 0) return;
        int bottom = grid.getHeight() - grid.getPaddingBottom() + Math.round(24 * density);
        int paddingTop = Math.max(0, bottom - decoratedHeight);
        if (paddingTop != grid.getPaddingTop()) {
            grid.setPadding(grid.getPaddingLeft(), paddingTop,
                    grid.getPaddingRight(), grid.getPaddingBottom());
        }
    }

    private void showGlassForView(View target) {
        Rect bounds = new Rect(0, 0, target.getWidth(), target.getHeight());
        if (bounds.isEmpty()) return;
        root.offsetDescendantRectToMyCoords(target, bounds);
        int inset = Math.round(6 * density);
        int width = bounds.width() + inset * 2;
        int height = bounds.height() + inset * 2;
        ViewGroup.LayoutParams params = glass.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            glass.setLayoutParams(params);
        }
        glass.setRadius(Math.min(width, height) * 0.35f);
        glassTargetX = bounds.left - inset;
        glassTargetY = bounds.top - inset;
        glassScrollShift = 0f;
        glass.setAlpha(1f);
        if (glassShown && animations) {
            springX.animateToFinalPosition(glassTargetX);
            springY.animateToFinalPosition(glassTargetY);
        } else {
            springX.cancel();
            springY.cancel();
            glass.setTranslationX(glassTargetX);
            glass.setTranslationY(glassTargetY);
        }
        glassShown = true;
    }

    private void showGlassAfterLayout(View target) {
        android.view.ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                android.view.ViewTreeObserver current = root.getViewTreeObserver();
                if (current.isAlive()) current.removeOnPreDrawListener(this);
                if (target.isFocused()) showGlassForView(target);
                return true;
            }
        });
    }

    private boolean isTopBarButton(View view) {
        if (view == null) return false;
        int id = view.getId();
        return id == R.id.btnInputs || id == R.id.btnWifi
                || id == R.id.btnRecentApps || id == R.id.btnSettings || id == R.id.btnPower;
    }

    private void openRecentApps() {
        try {
            startActivity(new Intent(this, RecentAppsActivity.class));
        } catch (ActivityNotFoundException | SecurityException exception) {
            android.util.Log.e("AppleTv.HomeKey", "Unable to open app switcher", exception);
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int target;
        if (homeEditMode && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int direction = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
            if (rtlAppGrid) direction = -direction;
            if (adapter.moveInEditMode(position, direction)) {
                focusTile(position + direction);
                grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        ? SoundEffectConstants.NAVIGATION_LEFT : SoundEffectConstants.NAVIGATION_RIGHT);
            }
            return true;
        }
        // Horizontal: swallow the key at a row's ends so focus never wraps to the next row or
        // escapes up into the top bar. Mid-row moves fall through to the default focus search.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int playerOffset = adapter.hasNowPlaying() ? 1 : 0;
            int appSlots = Math.max(0, COLUMNS - 2 - playerOffset);
            int dockedApps = Math.min(adapter.dockedApps(), appSlots);
            int appsTilePosition = adapter.appsTilePosition();
            boolean towardAppsTile = (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) != rtlAppGrid;
            if (dockedApps < appSlots && towardAppsTile
                    && position == playerOffset + dockedApps - 1) {
                focusTile(appsTilePosition);
                return true;
            }
            if (dockedApps < appSlots && !towardAppsTile
                    && position == appsTilePosition && dockedApps > 0) {
                focusTile(playerOffset + dockedApps - 1);
                return true;
            }
            // In the dock, the last app is the row's end: the empty slots after it take no focus.
            int rowCount = position < COLUMNS ? Math.min(count, adapter.dockFilled()) : count;
            return GridNavigation.atHorizontalEdge(position, rowCount, COLUMNS,
                    rtlAppGrid, keyCode == KeyEvent.KEYCODE_DPAD_LEFT);
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (homePlayerExpanded) {
                homePlayerProgress.requestFocus();
                return true;
            }
            if (position < COLUMNS) {
                if (continueShelf.getVisibility() == View.VISIBLE
                        && continueShelfCards.getChildCount() > 0) {
                    continueShelfCards.getChildAt(0).requestFocus();
                    return true;
                }
                (getResources().getBoolean(R.bool.rtl_menu_bar) ? btnPower : btnRecentApps)
                        .requestFocus();
                return true;
            }
            target = position - COLUMNS;
        } else {
            int lastRowStart = ((count - 1) / COLUMNS) * COLUMNS;
            if (position >= lastRowStart) return true;     // already on the last row: stay put
            target = Math.min(position + COLUMNS, count - 1);
        }

        focusTile(target);
        // The default focus search clicks for Left/Right; Up/Down are handled here, so click for them too.
        grid.playSoundEffect(keyCode == KeyEvent.KEYCODE_DPAD_UP
                ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

        private void loadContinueWatching(String packageName) {
            if (packageName.equals(continueShelfPackage)) return;
            continueShelfPackage = packageName;
            long request = ++continueShelfRequest;
            continueShelf.setVisibility(View.GONE);
            continueShelfCards.removeAllViews();
            WATCH_NEXT_EXECUTOR.execute(() -> {
                List<WatchNextRepository.Program> programs = WatchNextRepository.load(
                        getApplicationContext(), packageName);
                handler.post(() -> {
                    if (isFinishing() || request != continueShelfRequest
                            || !packageName.equals(continueShelfPackage)) return;
                    displayContinueWatching(packageName, programs);
                });
            });
        }

        private void displayContinueWatching(String packageName, List<WatchNextRepository.Program> programs) {
            continueShelfCards.removeAllViews();
            if (programs.isEmpty()) {
                continueShelf.setVisibility(View.GONE);
                return;
            }
            for (WatchNextRepository.Program program : programs) {
                continueShelfCards.addView(createWatchNextCard(packageName, program));
            }
            continueShelfScroll.scrollTo(0, 0);
            if (screensaverTransitionActive || homeScreensaverActive
                    || homeScreensaverDismissing) {
                continueShelf.setVisibility(View.GONE);
                return;
            }
            continueShelf.setVisibility(View.VISIBLE);
            continueShelf.requestLayout();
            grid.post(() -> {
                View focused = grid.findFocus();
                if (focused != null && focused.getId() == R.id.card) {
                    int position = grid.getChildAdapterPosition((View) focused.getParent());
                    if (position != RecyclerView.NO_POSITION) onTileFocused(position, focused);
                }
            });
        }

        private boolean isDescendantOf(View view, View parent) {
            View current = view;
            while (current != null) {
                if (current == parent) return true;
                if (!(current.getParent() instanceof View)) return false;
                current = (View) current.getParent();
            }
            return false;
        }

        private View createWatchNextCard(String packageName, WatchNextRepository.Program program) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setFocusable(true);
            card.setClickable(true);
            card.setBackgroundResource(R.drawable.recent_card_bg);
            card.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(
                    this, R.animator.tvos_focus_control));
            card.setPadding(dp(6), dp(6), dp(6), dp(6));
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(dp(204), dp(119));
            cardParams.setMargins(0, 0, dp(14), dp(8));
            card.setLayoutParams(cardParams);

            FrameLayout posterFrame = new FrameLayout(this);
            card.addView(posterFrame, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            ImageView poster = new ImageView(this);
            poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (program.poster != null) poster.setImageURI(program.poster);
            posterFrame.addView(poster, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView title = new TextView(this);
            title.setText(program.title);
            title.setTextColor(getColor(R.color.tvos_label_primary));
            title.setTextSize(14);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            posterFrame.addView(title, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress(program.progress);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE50914));
            progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xA6FFFFFF));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
            progressParams.topMargin = dp(3);
            card.addView(progress, progressParams);

            String detail = program.subtitle;
            long remainingMinutes = program.remainingMinutes;
            if (remainingMinutes > 0) {
                detail = detail.isEmpty()
                        ? getString(R.string.watch_next_minutes_remaining, remainingMinutes)
                        : getString(R.string.watch_next_remaining, detail, remainingMinutes);
            }
            TextView subtitle = new TextView(this);
            subtitle.setText(detail);
            subtitle.setTextColor(getColor(R.color.tvos_label_secondary));
            subtitle.setTextSize(11);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(subtitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(17)));

            card.setOnClickListener(v -> launchWatchNext(packageName, program));
            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    int position = adapter.positionOf(lastFocusedKey);
                    focusTile(position >= 0 ? position : 0);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int position = adapter.positionOf(lastFocusedKey);
                    focusTile(position >= 0 ? position : 0);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    btnRecentApps.requestFocus();
                    return true;
                }
                return false;
            });
            return card;
        }

        private void launchWatchNext(String packageName, WatchNextRepository.Program program) {
            if (program.intent == null) {
                Toast.makeText(this, R.string.watch_next_unavailable, Toast.LENGTH_LONG).show();
                return;
            }
            try {
                Intent intent = Intent.parseUri(program.intent.toString(), 0);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                android.content.pm.ResolveInfo resolved = getPackageManager().resolveActivity(intent, 0);
                if (resolved == null || resolved.activityInfo == null
                        || !packageName.equals(resolved.activityInfo.packageName)) {
                    Toast.makeText(this, R.string.watch_next_unavailable, Toast.LENGTH_LONG).show();
                    return;
                }
                startActivity(intent);
            } catch (URISyntaxException | android.content.ActivityNotFoundException | SecurityException e) {
                android.util.Log.w("AppleTv.WatchNext", "Could not open Watch Next item for " + packageName, e);
                Toast.makeText(this, R.string.watch_next_unavailable, Toast.LENGTH_LONG).show();
            }
        }

        private void hideContinueWatching() {
            continueShelfRequest++;
            continueShelfPackage = "";
            continueShelf.setVisibility(View.GONE);
            continueShelfCards.removeAllViews();
        }

        private int dp(int value) {
            return Math.round(value * density);
        }

    private void focusTile(int position) {
        int itemCount = adapter.getItemCount();
        if (itemCount == 0) {
            btnRecentApps.requestFocus();
            return;
        }
        int requestedPosition = Math.max(0, Math.min(position, itemCount - 1));
        int focusablePosition = adapter.focusablePosition(requestedPosition);
        final int pos = Math.max(0, Math.min(focusablePosition, itemCount - 1));
        if (pos != focusablePosition) {
            android.util.Log.w("AppleTv.Focus", "Clamped stale focus position "
                    + focusablePosition + " to " + pos + " for " + itemCount + " tiles");
        }
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            View card = vh.itemView.findViewById(R.id.card);
            // Already focused (e.g. back from an app): no focus change fires, so restore the glass by hand.
            if (card.isFocused()) onTileFocused(pos, card);
            else card.requestFocus();
            handler.removeCallbacks(refreshDockFocus);
            grid.post(() -> {
                adapter.resetDockTranslations(grid);
                RecyclerView.ViewHolder current = grid.findViewHolderForAdapterPosition(pos);
                if (current != null) {
                    onTileFocused(pos, current.itemView.findViewById(R.id.card));
                }
            });
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        if (!animations) {
            grid.scrollToPosition(pos);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(pos);
                if (h != null) h.itemView.findViewById(R.id.card).requestFocus();
            });
            return;
        }
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res = greetingResourceForHour(hour);
        String festiveGreeting = festive ? festival.greetingText() : "";
        int color;
        if (!festiveGreeting.isEmpty()) {
            greeting.setText(festiveGreeting);
            color = liftTowardsWhite(festival.accentColor(), 0.25f);
        } else {
            greeting.setText(res);
            color = greetingColor;
        }
        greeting.setTextColor(color);
        ((TextView) findViewById(R.id.date)).setTextColor(color);
        if (shamsiDate != null) shamsiDate.setTextColor(color);
        if (shamsi) shamsiDate.setText(Shamsi.today());
    }

    static void copyHomeGreeting(TextView target) {
        MainActivity home = screensaverActivity.get();
        if (home != null && !home.isDestroyed() && home.greeting != null) {
            target.setText(home.greeting.getText());
            target.setTextColor(home.greeting.getCurrentTextColor());
            return;
        }
        target.setText(greetingResourceForHour(
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
        target.setTextColor(0xB3FFFFFF);
    }

    private static int greetingResourceForHour(int hour) {
        if (hour < 5 || hour >= 22) return R.string.good_night;
        if (hour < 12) return R.string.good_morning;
        if (hour < 17) return R.string.good_afternoon;
        return R.string.good_evening;
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    private void updateWifiLabel() {
        String text = getString(R.string.wifi);
        boolean vpn = false, online = false;
        int icon = R.drawable.ic_wifi;
        // The diya's flame: 0 is out (offline), 1 to 4 follow the Wi-Fi signal, Ethernet burns steady at 4.
        int flame = 0;
        boolean steady = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean wifi = false, ethernet = false;
            // All networks, not just the active one: with a VPN up the active network is the VPN itself.
            for (Network net : cm != null ? cm.getAllNetworks() : new Network[0]) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps == null) continue;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpn = true;
                    continue;
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                online = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = true;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
            }
            if (!online) {
                text = getString(R.string.offline);
                vpn = false;
            } else if (ethernet) {
                text = getString(R.string.ethernet);
                icon = R.drawable.ic_ethernet;
                flame = 4;
                steady = true;
            } else if (wifi) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
                String ssid = info != null ? info.getSSID() : null;
                flame = info != null ? Math.max(1, Math.min(4, WifiManager.calculateSignalLevel(info.getRssi(), 5))) : 3;
                if (ssid != null) {
                    ssid = ssid.replace("\"", "").trim();
                    if (!ssid.isEmpty() && !ssid.contains("unknown") && !ssid.equals("0x")) {
                        text = ssid;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // With a VPN app configured, a VPN that is down matters too: show a red "VPN off" capsule then.
        boolean vpnDown = !vpn && online && !vpnApp.isEmpty();
        if (vpn || vpnDown) {
            // Keep the complete network name and show the VPN state after it.
            String badge = getString(vpn ? R.string.vpn : R.string.vpn_off);
            VpnBadge span = new VpnBadge(vpn ? getDrawable(R.drawable.ic_lock) : null, density);
            SpannableString label = new SpannableString(text + " " + badge);
            label.setSpan(span, text.length() + 1, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            wifiDisplayLabel = label;
        } else {
            wifiDisplayLabel = text;
        }
        btnWifi.setText(wifiDisplayLabel);
        if (festive && diya != null) {
            if (online && flame == 0) flame = 3;   // online some other way (cellular, USB tethering)
            diya.setSteady(steady);
            diya.setFlame(flame);
        } else {
            btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        }
    }

    // ---- festive skin ------------------------------------------------------------------------------

    /**
     * Swaps the pill icons for the festival's: a bell for Inputs that swings when focused, a diya for the
     * network whose flame follows the connection, and a marigold for Settings that turns when focused.
     */
    private void applyFestiveSkin(TextView inputs, TextView settings) {
        bell = new BellDrawable(density);
        marigold = new MarigoldDrawable(density);
        diya = new DiyaDrawable(density);
        bell.setReducedMotion(reducedMotion);
        marigold.setReducedMotion(reducedMotion);
        diya.setReducedMotion(reducedMotion);
        setFestiveIcon(inputs, bell);
        setFestiveIcon(btnWifi, diya);
        setFestiveIcon(settings, marigold);
        addFocusAction(inputs, bell::ring);
        addFocusAction(settings, marigold::turn);
    }

    private static void setFestiveIcon(TextView pill, Drawable icon) {
        // The pills tint their icons to follow focus; the festive ones keep their own colours.
        pill.setCompoundDrawableTintList(null);
        pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
    }

    /** Runs {@code action} when the view gains focus, keeping any focus listener it already had. */
    private static void addFocusAction(View view, Runnable action) {
        View.OnFocusChangeListener previous = view.getOnFocusChangeListener();
        view.setOnFocusChangeListener((v, focused) -> {
            if (previous != null) previous.onFocusChange(v, focused);
            if (focused) action.run();
        });
    }

    private void playPetalShower() {
        if (isFinishing() || isDestroyed()) return;
        PetalShowerView shower = new PetalShowerView(this);
        root.addView(shower, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shower.play(60, 2200, () -> root.removeView(shower));
    }

    private static int liftTowardsWhite(int argb, float amount) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (int) ((255 - r) * amount);
        g += (int) ((255 - g) * amount);
        b += (int) ((255 - b) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Preserves the configured VPN shortcut; otherwise opens the launcher's own Wi-Fi controls. */
    private void openNetworkPill() {
        Intent intent = vpnApp.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(vpnApp);
        if (intent == null && !vpnApp.isEmpty()) {
            intent = getPackageManager().getLaunchIntentForPackage(vpnApp);
        }
        if (intent != null) open(intent);
        else startActivity(new Intent(this, NetworkSettingsActivity.class));
    }

    /**
     * China-market MIUI TV firmware answers no standard Settings intents at all; open Xiaomi's own
     * settings app (com.xiaomi.mitv.settings) there, so the Settings and Wi-Fi pills keep working.
     * On firmware with a standard settings handler the behaviour is unchanged.
     */
    private Intent systemIntent(String standardAction) {
        Intent i = new Intent(standardAction);
        if (i.resolveActivity(getPackageManager()) != null) return i;
        i = new Intent().setComponent(new ComponentName(
                "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"));
        if (i.resolveActivity(getPackageManager()) != null) return i;
        return new Intent(standardAction);   // nothing found: let open() show the can't-open toast
    }

    /** Inputs are intentionally omitted from the compact Home shortcut row. */
    private void updateInputsButton() {
        btnInputs.setVisibility(View.GONE);
    }

    /** The Live TV app's launch intent: its leanback entry point, or the configured action if it has none. */
    private Intent liveTvIntent() {
        if (liveTvPkg.isEmpty()) return null;
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (i != null || liveTvAction.isEmpty()) return i;
        Intent byAction = new Intent(liveTvAction).setPackage(liveTvPkg);
        return getPackageManager().resolveActivity(byAction, 0) != null ? byAction : null;
    }

    /** The TV's own source-selection panel, for TVs that have no TvInputManager (TCL Android TV 9). */
    private void openSourcePanel() {
        Intent i = new Intent(sourcePanelAction);
        ComponentName c = ComponentName.unflattenFromString(sourcePanelService);
        if (c != null) i.setComponent(c);
        try {
            startService(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Switches to a passthrough input (HDMI, AV). The standard way is ACTION_VIEW on its channel URI,
     *  which the Live TV app handles; where nothing takes that, the configured `input_action` is sent
     *  with the same URI instead (XGIMI's HDMI player only answers com.xgimi.action.hdmiPlayer). */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Fire TV registers both its Live TV settings alias and the actual input player as handlers.
        // Without an explicit component Android presents a resolver instead of switching sources.
        if ("amazon".equalsIgnoreCase(Build.MANUFACTURER)) {
            for (ResolveInfo candidate : getPackageManager().queryIntentActivities(
                    view, PackageManager.MATCH_DEFAULT_ONLY)) {
                if ("com.vewd.core.tvapp".equals(candidate.activityInfo.packageName)) {
                    view.setComponent(new ComponentName(candidate.activityInfo.packageName,
                            candidate.activityInfo.name));
                    break;
                }
            }
        }
        try {
            startActivity(view);
            return;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (inputAction.isEmpty()) {
                android.util.Log.e("AppleTv.Inputs", "No permitted handler for input " + inputId, e);
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        open(new Intent(inputAction).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Compact: as wide as the Inputs pill (a touch wider so "HDMI 1" fits), hanging right below it.
        int width = Math.max(anchor.getWidth(), (int) (150 * density));
        final PopupWindow popup = glassPopup(panel, width);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        String tunerId = null;
        boolean tunerIsHw = false;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            // Some TVs (Sony) report every HDMI port as connected whether or not anything is plugged in,
            // so drop the HDMI status then. A TV with every port really in use loses it too.
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean hdmiStateBogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // Prefer a hardware tuner: the framework names those ".../HW<n>". System apps such as
                    // AirPlay also register software inputs of type TUNER and may sort first (seen on TCL G05).
                    boolean hw = info.getId().matches(".*/HW\\d+$");
                    if (tunerId == null || (hw && !tunerIsHw) || (hw == tunerIsHw && info.getId().compareTo(tunerId) < 0)) {
                        tunerId = info.getId();
                        tunerIsHw = hw;
                    }
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                int state = hdmi && hdmiStateBogus ? -1 : tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, label, state,
                        () -> openInput(id));
            }
        }
        // Nothing to list where there is no TvInputManager: the TV's own source panel switches inputs.
        if (rows.getChildCount() == 0 && !sourcePanelService.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.inputs), -1, this::openSourcePanel);
        }
        Intent liveTv = liveTvIntent();
        if (liveTv != null && tunerId != null) {
            liveTv = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(tunerId), "vnd.android.cursor.item/channel")
                    .setPackage(liveTvPkg);
        }
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            final Intent liveTvIntent = liveTv;
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTvIntent));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Fit the widest row: TVs that report a status on every port (Sony) would otherwise squeeze the names out.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max(width, panel.getMeasuredWidth()));

        int alignment = getResources().getBoolean(R.bool.rtl_menu_bar) ? Gravity.END : Gravity.START;
        dropIn(popup, panel, anchor, alignment);
        View firstRow = rows.getChildAt(0);
        firstRow.setFocusableInTouchMode(true);
        firstRow.post(() -> {
            if (popup.isShowing()) firstRow.requestFocus();
        });
    }

    private PopupWindow glassPopup(View panel, int width) {
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(animations && !reducedMotion
                ? R.style.PopupWindowExitAnimation : 0);
        popup.setElevation(0f);
        popup.setOnDismissListener(() -> {
            if (openPopup == popup) openPopup = null;
        });
        openPopup = popup;
        return popup;
    }

    /** Drop in: fade and unfold from the top edge, hanging under the anchor pill. */
    private void dropIn(PopupWindow popup, View panel, View anchor, int gravity) {
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, 0, (int) (10 * density), gravity);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        rows.addView(row);
    }

    // ---- launcher settings and module cards -------------------------------------------------------

    /** Keep the hold-OK menu for Home editing and optional module cards. */
    private void showSettingsMenu() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel, (int) (200 * density));
        addSettingsMenuRow(rows, popup, R.string.home_edit_mode, () -> setHomeEditMode(true));
        if (moduleCards) addSettingsMenuRow(rows, popup, R.string.module_cards, this::showModuleCards);
        ((TextView) panel.findViewById(R.id.popupTitle)).setText(R.string.settings);
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(settingsMenuWidth(rows));
        // Hebrew puts Settings at the left of the header: grow the menu inward from it.
        dropIn(popup, panel, anchor, getResources().getBoolean(R.bool.rtl_menu_bar)
                ? Gravity.START : Gravity.END);
        View firstRow = rows.getChildAt(0);
        firstRow.setFocusableInTouchMode(true);
        firstRow.post(() -> {
            if (popup.isShowing()) firstRow.requestFocus();
        });
    }

    private int settingsMenuWidth(LinearLayout rows) {
        float widest = 0f;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View title = rows.getChildAt(i).findViewById(R.id.title);
            if (title instanceof TextView) {
                TextView text = (TextView) title;
                widest = Math.max(widest, text.getPaint().measureText(text.getText().toString()));
            }
        }
        int desired = Math.max((int) (200 * density), (int) Math.ceil(widest) + (int) (76 * density));
        return Math.min(desired, getResources().getDisplayMetrics().widthPixels - (int) (32 * density));
    }

    private void setHomeEditMode(boolean enabled) {
        if (homeEditMode == enabled) return;
        homeEditMode = enabled;
        adapter.setEditMode(enabled);
        if (enabled) {
            Toast.makeText(this, R.string.home_edit_hint, Toast.LENGTH_LONG).show();
            restoreFocus();
        }
    }

    @Override
    public void onHomeEditModeFinished() {
        setHomeEditMode(false);
    }

    private void addSettingsMenuRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up after opening the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    /**
     * Asks about the next card that has no decision for its current signing key. One prompt at a
     * time, only over a laid out grid with window focus (not under a popup or another app), and
     * the next one follows as soon as this one is answered. Nothing is shown until the owner says Show.
     */
    private void showCardPrompt() {
        if (!moduleCards || !tilesReady || promptsPaused || cardPrompt != null || !hasWindowFocus() || isFinishing()) return;
        if (cards.isEmpty() || !CardFeature.working()) return;
        CardFeature.run(() -> {
            showCardPromptNow();
            return null;
        }, null);
    }

    private void showCardPromptNow() {
        final Cards.Card card = Cards.firstPending(cards);
        if (card == null) return;

        View panel = getLayoutInflater().inflate(R.layout.dialog_card_prompt, root, false);
        ((TextView) panel.findViewById(R.id.title)).setText(getString(R.string.card_prompt_title, card.appLabel));
        ((TextView) panel.findViewById(R.id.body)).setText(card.state == Cards.State.RESIGNED
                ? getString(R.string.card_prompt_resigned, card.appLabel)
                : card.state == Cards.State.RELABELLED
                ? getString(R.string.card_prompt_relabelled, card.appLabel)
                : getString(R.string.card_prompt_body, card.appLabel));
        TextView names = panel.findViewById(R.id.names);
        if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) names.setVisibility(View.GONE);
        else names.setText(getString(R.string.card_prompt_names, card.label, card.appLabel));
        ((TextView) panel.findViewById(R.id.identity)).setText(
                getString(R.string.card_prompt_identity, card.pkg, Cards.shortDigest(card.digest)));
        TextView keep = panel.findViewById(R.id.btnKeep);
        TextView show = panel.findViewById(R.id.btnShow);

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(false);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (440 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.6f);
        }
        // One decision per prompt: the buttons and Back (cancel) all end here.
        final boolean[] decided = {false};
        View.OnClickListener choose = v -> {
            if (decided[0]) return;
            decided[0] = true;
            cardPrompt = null;
            dialog.dismiss();
            decideCard(card, v.getId() == R.id.btnShow);   // guards its own card calls; the tile reload stays outside
            restoreFocus();   // the tiles changed under the prompt; this also chains the next prompt
        };
        keep.setOnClickListener(choose);
        show.setOnClickListener(choose);
        dialog.setOnCancelListener(d -> choose.onClick(keep));
        dialog.setOnDismissListener(d -> {
            if (cardPrompt == dialog) cardPrompt = null;
        });
        // Left and Right move between the two buttons; Up and Down stay put; the ends don't wrap.
        View.OnKeyListener keys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return v.getId() == R.id.btnKeep;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnShow;
        };
        keep.setOnKeyListener(keys);
        show.setOnKeyListener(keys);
        cardPrompt = dialog;
        dialog.show();
        keep.requestFocus();
    }

    /** Writes the owner's decision and rebuilds the tiles; focus is left where it is. */
    private void decideCard(Cards.Card card, boolean show) {
        boolean saved = CardFeature.run(() -> Cards.decide(Cards.storeFile(this), card, show), false);
        if (!saved) {
            Toast.makeText(this, R.string.card_not_saved, Toast.LENGTH_SHORT).show();
            promptsPaused = true;
        }
        reloadTiles();
    }

    /** Dropdown under the Settings pill (hold OK): every discovered card, its package and key; OK toggles Shown and Hidden. */
    private void showModuleCards() {
        if (cards.isEmpty() || !CardFeature.working()) {
            Toast.makeText(this, R.string.no_module_cards, Toast.LENGTH_SHORT).show();
            return;
        }
        CardFeature.run(() -> {
            showModuleCardsNow();
            return null;
        }, null);
    }

    private void showModuleCardsNow() {
        View anchor = findViewById(R.id.btnSettings);
        View panel = getLayoutInflater().inflate(R.layout.popup_cards, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = glassPopup(panel, (int) (400 * density));
        List<Cards.Card> listed = new ArrayList<>(cards);
        Collections.sort(listed, Cards::byLabel);
        for (Cards.Card card : listed) {
            View row = getLayoutInflater().inflate(R.layout.item_card_row, rows, false);
            row.setTag(card.pkg);
            ((TextView) row.findViewById(R.id.title)).setText(card.label);
            TextView app = row.findViewById(R.id.app);
            if (Cards.fold(card.label).equals(Cards.fold(card.appLabel))) app.setVisibility(View.GONE);
            else app.setText(getString(R.string.card_app_name, card.appLabel));
            ((TextView) row.findViewById(R.id.pkg)).setText(card.pkg);
            ((TextView) row.findViewById(R.id.digest)).setText(Cards.longDigest(card.digest));
            final String pkg = card.pkg;
            row.setOnClickListener(v -> CardFeature.run(() -> {
                // The list stays open; the decision is for the card as it is now, not as it was listed.
                Cards.Card now = cardFor(pkg);
                if (now == null) return null;
                decideCard(now, now.state != Cards.State.SHOWN);
                // Every row, not only this one: a toggle can move another card across the limit of 8.
                updateCardStates(rows);
                return null;
            }, null));
            rows.addView(row);
        }
        updateCardStates(rows);
        // Scroll inside the panel instead of running off the bottom of a 720p screen.
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationInWindow(at);
        int room = root.getHeight() - (at[1] + anchor.getHeight()) - (int) (34 * density);
        popup.setHeight(Math.min(panel.getMeasuredHeight(), Math.max(room, (int) (120 * density))));
        dropIn(popup, panel, anchor, Gravity.END);
        rows.getChildAt(0).requestFocus();
    }

    private Cards.Card cardFor(String pkg) {
        for (Cards.Card c : cards) if (c.pkg.equals(pkg)) return c;
        return null;
    }

    /** Writes the state of every row in the Module cards list from the current card list. */
    private void updateCardStates(LinearLayout rows) {
        List<Cards.Card> drawn = Cards.shown(cards);
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            Cards.Card card = row.getTag() instanceof String ? cardFor((String) row.getTag()) : null;
            ((TextView) row.findViewById(R.id.state)).setText(card != null ? stateText(card, drawn.contains(card)) : "");
        }
    }

    /** The row's state: a card the owner set to Shown but which is beyond the eight drawn says so. */
    private String stateText(Cards.Card card, boolean drawn) {
        switch (card.state) {
            case SHOWN:
                return getString(drawn ? R.string.card_state_shown : R.string.card_state_over_limit);
            case HIDDEN:
                return getString(R.string.card_state_hidden);
            case RESIGNED:
                return getString(R.string.card_state_new_key);
            case RELABELLED:
                return getString(R.string.card_state_new_label);
            default:
                return getString(R.string.card_state_new);
        }
    }
}

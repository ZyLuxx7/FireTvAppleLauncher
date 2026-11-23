package com.appletv.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.animation.ObjectAnimator;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The home adapter contains dock apps followed by the fixed Apps and Launcher Settings tiles.
 * The Apps screen owns the full installed-app grid.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        void onHomeEditModeFinished();

        void onNowPlayingTileSelected();
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, EMPTY = 3, LAUNCHER_SETTINGS = 5,
                ALL_APPS = 6, NOW_PLAYING = 7;
        static final int FOLDER = 4;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        final List<Entry> folderApps;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0);
        }

        Entry(String label, ComponentName component, Drawable icon, List<Entry> folderApps) {
            this.kind = FOLDER;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = null;
            this.icon = icon;
            this.name = label;
            this.ambient = 0;
            this.folderApps = new ArrayList<>(folderApps);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
            this.folderApps = Collections.emptyList();
        }
    }

    static final float FOCUS_SCALE = 1.2267f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 125f, DESIGN_H_DP = 75f;

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int baseDockSize;
    private int dockSize;
    private final float dockScale;
    /** Dock order (config.xml dock_apps, or a theme's config.json). */
    private final List<String> favourites = new ArrayList<>();
    private final List<String> dockOrder = new ArrayList<>();
    /** Grid apps fill the dock slots dock_apps leaves free (config.xml, fill_dock). */
    private final boolean fillDock;
    /** Dock apps found at the last reload, and the empty slots after them. */
    private int docked, emptySlots;
    /** Never shown as tiles (res/values/config.xml, hidden_apps). */
    private final Set<String> hidden;
    private final Set<String> userHiddenApps = new HashSet<>();
    private final Set<String> dockExcludedApps = new HashSet<>();
    private final boolean animations;
    private final boolean advancedEffects;
    private final boolean showHomeSettings;
    /** Calmer motion for weak GPUs or the pre-API-31 software glass profile. */
    private final boolean reducedMotion;
    private final boolean editModeWiggle;
    private final boolean lightAppearance;
    private final List<String> appOrder;
    private final List<Entry> items = new ArrayList<>();
    private boolean editMode;
    private NowPlayingListener.Snapshot nowPlaying;

    AppAdapter(Context ctx, TileListener listener, int dockSize, Config cfg,
               boolean animations, boolean forceReducedMotion) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.baseDockSize = dockSize;
        this.fillDock = cfg.fillDock;
        this.hidden = new HashSet<>(cfg.hiddenApps);
        this.animations = animations;
        this.reducedMotion = cfg.reducedMotion || forceReducedMotion;
        this.editModeWiggle = cfg.animations && !cfg.reducedMotion;
        this.advancedEffects = cfg.animations && cfg.advancedEffects && !this.reducedMotion;
        this.showHomeSettings = cfg.showHomeSettingsShortcut;
        boolean scenicWallpaper = cfg.wallpaper == null
                && !HomeWallpaperCatalog.isBanner(HomeWallpaperCatalog.selectedId(ctx));
        this.lightAppearance = LauncherUiSettings.isLight(ctx) && !scenicWallpaper;
        refreshFavourites(cfg.dockApps);
        android.content.SharedPreferences preferences =
                ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        dockScale = Math.max(75, Math.min(120,
                preferences.getInt("dock_visual_size", 100))) / 100f;
        String savedOrder = preferences.getString("app_order", "");
        this.appOrder = new ArrayList<>();
        if (savedOrder != null && !savedOrder.isEmpty()) {
            this.appOrder.addAll(Arrays.asList(savedOrder.split(",")));
        }
        dockExcludedApps.addAll(preferences.getStringSet(
                DockApps.EXCLUDED_APPS_KEY, Collections.emptySet()));
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    private void refreshFavourites(List<String> configuredApps) {
        favourites.clear();
        favourites.addAll(configuredApps);
        String savedDockOrder = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString("dock_order", "");
        dockOrder.clear();
        if (savedDockOrder != null && !savedDockOrder.isEmpty()) {
            List<String> savedDockApps = Arrays.asList(savedDockOrder.split(","));
            List<String> orderedDockApps = new ArrayList<>();
            for (String pkg : savedDockApps) {
                if (!dockOrder.contains(pkg)) dockOrder.add(pkg);
                if (favourites.contains(pkg) && !orderedDockApps.contains(pkg)) {
                    orderedDockApps.add(pkg);
                }
            }
            for (String pkg : favourites) if (!orderedDockApps.contains(pkg)) orderedDockApps.add(pkg);
            favourites.clear();
            favourites.addAll(orderedDockApps);
        }
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     */
    void reload(List<Cards.Card> cards) {
        refreshFavourites(Config.load(ctx).dockApps);
        android.content.SharedPreferences preferences =
                ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        userHiddenApps.clear();
        userHiddenApps.addAll(preferences.getStringSet("user_hidden_apps", Collections.emptySet()));
        dockExcludedApps.clear();
        dockExcludedApps.addAll(preferences.getStringSet(
                DockApps.EXCLUDED_APPS_KEY, Collections.emptySet()));
        String savedOrder = preferences.getString("app_order", "");
        appOrder.clear();
        if (savedOrder != null && !savedOrder.isEmpty()) {
            appOrder.addAll(Arrays.asList(savedOrder.split(",")));
        }

        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        Map<String, List<ResolveInfo>> activitiesByPackage = new LinkedHashMap<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || isHidden(pkg)) continue;
            activitiesByPackage.computeIfAbsent(pkg, ignored -> new ArrayList<>()).add(ri);
        }

        List<Entry> fresh = new ArrayList<>();
        for (Map.Entry<String, List<ResolveInfo>> packageActivities : activitiesByPackage.entrySet()) {
            String pkg = packageActivities.getKey();
            ResolveInfo ri = preferredLeanbackActivity(pm, pkg, packageActivities.getValue());
            Entry entry = createEntry(pm, ri, cardByPkg.get(pkg));
            if (entry != null) fresh.add(entry);
        }

        // Some Fire TV apps, including Prime Video, expose a launcher activity but no Leanback entry.
        // Only configured dock apps get this fallback so phone-only apps do not fill the TV grid.
        for (String pkg : favourites) {
            if (isHidden(pkg) || activitiesByPackage.containsKey(pkg)) continue;
            Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launchIntent == null) launchIntent = pm.getLaunchIntentForPackage(pkg);
            if (launchIntent == null) continue;
            ResolveInfo ri = pm.resolveActivity(launchIntent, 0);
            if (ri == null || ri.activityInfo == null || !pkg.equals(ri.activityInfo.packageName)) continue;
            Entry entry = createEntry(pm, ri, cardByPkg.get(pkg));
            if (entry != null) fresh.add(entry);
        }
        Collections.sort(fresh, (a, b) -> {
            int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            int oa = appOrder.indexOf(a.pkg), ob = appOrder.indexOf(b.pkg);
            if (oa >= 0 || ob >= 0) {
                if (oa < 0) return 1;
                if (ob < 0) return -1;
                if (oa != ob) return Integer.compare(oa, ob);
            }
            // Cards come right after the configured dock apps, before the alphabetical grid.
            boolean ca = a.kind == Entry.CARD, cb = b.kind == Entry.CARD;
            if (ca != cb) return ca ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        applyFolders(fresh);
        Map<String, Entry> entriesByPackage = new HashMap<>();
        for (Entry entry : fresh) entriesByPackage.putIfAbsent(entry.pkg, entry);
        List<Entry> home = new ArrayList<>();
        int appSlots = Math.max(0, baseDockSize - 2);
        Set<String> unavailableDockApps = new HashSet<>(dockExcludedApps);
        for (Entry entry : fresh) {
            if (entry.kind != Entry.FOLDER) continue;
            for (Entry member : entry.folderApps) {
                unavailableDockApps.add(member.pkg);
            }
        }
        List<String> dockCandidates = new ArrayList<>();
        if (fillDock) {
            dockCandidates.addAll(dockOrder);
        }
        for (String pkg : favourites) if (!dockCandidates.contains(pkg) || !fillDock) {
            dockCandidates.add(pkg);
        }
        List<String> availablePackages = new ArrayList<>();
        for (Entry entry : fresh) availablePackages.add(entry.pkg);
        List<String> dockPackages = DockApps.addSelected(
                dockCandidates, entriesByPackage.keySet(), unavailableDockApps,
                fillDock ? availablePackages : Collections.emptyList(), appSlots);
        for (String pkg : dockPackages) home.add(entriesByPackage.get(pkg));
        docked = home.size();
        emptySlots = DockSlots.empty(docked, appSlots, fresh.size(), fillDock);
        for (int i = 0; i < emptySlots; i++) {
            home.add(new Entry(Entry.EMPTY, "", new ComponentName(ctx, "empty-dock-slot-" + i),
                    null, null, null, 0));
        }
        home.add(new Entry(Entry.ALL_APPS, ctx.getString(R.string.quick_apps),
                new ComponentName(ctx, AllAppsActivity.class), null,
                ctx.getDrawable(R.drawable.ic_apps), null, 0));
        home.add(new Entry(Entry.LAUNCHER_SETTINGS, ctx.getString(R.string.settings),
                new ComponentName(ctx, LauncherSettingsActivity.class), null,
                ctx.getDrawable(R.drawable.ic_settings), null, 0));
        items.clear();
        if (nowPlaying != null) home.add(0, createNowPlayingEntry(nowPlaying));
        items.addAll(home);
        notifyDataSetChanged();
    }

    void setNowPlaying(NowPlayingListener.Snapshot snapshot) {
        NowPlayingListener.Snapshot previousSnapshot = nowPlaying;
        NowPlayingListener.Snapshot activeSnapshot =
                snapshot != null && snapshot.active ? snapshot : null;
        boolean wasVisible = nowPlaying != null;
        nowPlaying = activeSnapshot;
        dockSize = baseDockSize + (nowPlaying == null ? 0 : 1);
        if (items.isEmpty()) return;

        if (wasVisible != (nowPlaying != null)) {
            if (nowPlaying == null) {
                if (items.get(0).kind == Entry.NOW_PLAYING) items.remove(0);
            } else {
                items.add(0, createNowPlayingEntry(nowPlaying));
            }
            notifyDataSetChanged();
        } else if (nowPlaying != null && items.get(0).kind == Entry.NOW_PLAYING
                && !NowPlayingListener.sameVisibleContent(previousSnapshot, nowPlaying)) {
            items.set(0, createNowPlayingEntry(nowPlaying));
            notifyItemChanged(0);
        }
    }

    private Entry createNowPlayingEntry(NowPlayingListener.Snapshot snapshot) {
        String title = nonEmpty(snapshot.title, R.string.now_playing_unknown_title);
        String artist = nonEmpty(snapshot.artist, R.string.now_playing_unknown_artist);
        Drawable artwork = snapshot.artwork == null ? null
                : new BitmapDrawable(ctx.getResources(), snapshot.artwork);
        Drawable icon = artwork == null ? ctx.getDrawable(R.drawable.ic_music_note) : null;
        return new Entry(Entry.NOW_PLAYING, title,
                new ComponentName(ctx, "com.appletv.launcher.NowPlayingTile"),
                artwork, icon, artist, 0);
    }

    private String nonEmpty(String value, int fallback) {
        return value == null || value.trim().isEmpty() ? ctx.getString(fallback) : value;
    }

    boolean hasNowPlaying() {
        return nowPlaying != null;
    }

    boolean isNowPlaying(int position) {
        return nowPlaying != null && position == 0;
    }

    int dockedApps() {
        return docked;
    }

    private boolean isHidden(String packageName) {
        return hidden.contains(packageName) || userHiddenApps.contains(packageName);
    }

    private void applyFolders(List<Entry> entries) {
        List<LauncherFolders.Folder> folders = LauncherFolders.load(ctx);
        for (int folderIndex = 0; folderIndex < folders.size(); folderIndex++) {
            LauncherFolders.Folder folder = folders.get(folderIndex);
            List<Entry> members = new ArrayList<>();
            int insertAt = entries.size();
            for (Entry entry : entries) {
                if (!favourites.contains(entry.pkg) && folder.packages.contains(entry.pkg)) {
                    members.add(entry);
                    insertAt = Math.min(insertAt, entries.indexOf(entry));
                }
            }
            if (members.size() < 2) continue;
            entries.removeAll(members);
            String componentName = ".Folder" + Integer.toHexString(folder.name.hashCode()) + folderIndex;
            Entry folderEntry = new Entry(folder.name,
                    new ComponentName(ctx.getPackageName(), componentName),
                    ctx.getDrawable(R.drawable.ic_folder), members);
            entries.add(Math.min(insertAt, entries.size()), folderEntry);
        }
    }

    private ResolveInfo preferredLeanbackActivity(PackageManager pm, String pkg, List<ResolveInfo> candidates) {
        Intent launchIntent = pm.getLeanbackLaunchIntentForPackage(pkg);
        ComponentName launchComponent = launchIntent == null ? null : launchIntent.getComponent();
        if (launchComponent != null) {
            for (ResolveInfo candidate : candidates) {
                if (launchComponent.equals(new ComponentName(pkg, candidate.activityInfo.name))) return candidate;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private Entry createEntry(PackageManager pm, ResolveInfo ri, Cards.Card card) {
        if (ri == null || ri.activityInfo == null) return null;
        String pkg = ri.activityInfo.packageName;
        ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
        if (card != null && !card.component.equals(component)) return null;
        Drawable banner = loadBanner(pm, ri);
        Drawable icon = loadIcon(pm, ri);
        if (card != null) {
            return new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent);
        }
        // Same cleaner as the card prompt: labels cannot redraw the tile name through bidi controls.
        String appLabel = "org.smarttube.stable".equals(pkg)
                ? "YouTube" : "com.amazon.avod".equals(pkg)
                ? "Prime Video" : Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
        return new Entry(appLabel, component, banner, icon);
    }

    private Drawable loadBanner(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                int density = Math.max(640, ctx.getResources().getDisplayMetrics().densityDpi);
                return highQualityBitmap(resources.getDrawableForDensity(id, density, null));
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return highQualityBitmap(ri.activityInfo.loadBanner(pm));
    }

    private Drawable loadIcon(PackageManager pm, ResolveInfo ri) {
        int id = ri.activityInfo.getIconResource();
        if (id == 0) id = ri.activityInfo.applicationInfo.icon;
        if (id != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                int density = Math.max(640, ctx.getResources().getDisplayMetrics().densityDpi);
                return highQualityBitmap(resources.getDrawableForDensity(id, density, null));
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        return highQualityBitmap(ri.loadIcon(pm));
    }

    private Drawable highQualityBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            ((BitmapDrawable) drawable).setFilterBitmap(true);
        }
        return drawable;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener, animations, advancedEffects, reducedMotion,
                editModeWiggle, dockScale, dockSize);
    }

    private void resizeHolder(Holder holder, ViewGroup parent) {
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int tileWidth = tileWidthPx(inner, dockSize, density);
        int tileHeight = tileHeightPx(tileWidth);

        ViewGroup.LayoutParams cardParams = holder.card.getLayoutParams();
        if (cardParams.width != tileWidth || cardParams.height != tileHeight) {
            cardParams.width = tileWidth;
            cardParams.height = tileHeight;
            holder.card.setLayoutParams(cardParams);
        }
        ViewGroup.MarginLayoutParams nameParams =
                (ViewGroup.MarginLayoutParams) holder.name.getLayoutParams();
        int nameTop = tileHeight + Math.round(7 * density);
        if (nameParams.width != tileWidth || nameParams.topMargin != nameTop) {
            nameParams.width = tileWidth;
            nameParams.topMargin = nameTop;
            holder.name.setLayoutParams(nameParams);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        boolean wasNowPlayingTile = h.boundKind == Entry.NOW_PLAYING;
        if (h.itemView.getParent() instanceof ViewGroup) {
            resizeHolder(h, (ViewGroup) h.itemView.getParent());
        }
        h.itemView.setTranslationX(0f);
        h.card.setContentDescription(null);
        View mediaControlSurface = h.card.findViewById(R.id.mediaControlSurface);
        ImageView mediaControlIcon = h.card.findViewById(R.id.mediaControlIcon);
        if (e.kind == Entry.EMPTY) {
            // A free dock slot keeps its place in the row and the band, but shows nothing and takes no focus.
            h.setEditMode(false);
            h.card.setVisibility(View.INVISIBLE);
            h.card.setFocusable(false);
            h.name.setVisibility(View.INVISIBLE);
            h.banner.setImageDrawable(null);
            h.icon.setImageDrawable(null);
            mediaControlSurface.setVisibility(View.GONE);
            h.card.setOnClickListener(null);
            h.card.setOnLongClickListener(null);
            h.boundKind = Entry.EMPTY;
            return;
        }
        h.card.setVisibility(View.VISIBLE);
        h.card.setFocusable(true);
        h.setEditMode(editMode);
        h.name.setText(e.name);
        h.name.setTextColor(lightAppearance ? Color.rgb(28, 28, 30)
                : ctx.getColor(R.color.tvos_label_primary));
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.dockSize = dockSize;
        float baseScale = position < dockSize ? dockScale : 1f;
        h.name.setVisibility(position < dockSize && !focused ? View.INVISIBLE : View.VISIBLE);
        h.name.setAlpha(focused ? 1f : 0f);
        if (e.kind == Entry.NOW_PLAYING) h.card.setContentDescription(e.name);
        float cardScale = baseScale * (focused && animations ? focusScale() : 1f);
        h.card.setScaleX(cardScale);
        h.card.setScaleY(cardScale);
        h.card.setTranslationY(focused && advancedEffects ? -4f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.card.setTranslationZ(focused && advancedEffects ? 8f * ctx.getResources().getDisplayMetrics().density : 0f);
        h.banner.setScaleX(focused && advancedEffects ? 1.035f : 1f);
        h.banner.setScaleY(focused && advancedEffects ? 1.035f : 1f);
        if (e.kind == Entry.NOW_PLAYING) {
            Drawable current = h.banner.getDrawable();
            Drawable next = copyDrawable(e.banner);
            if (e.banner != null) {
                if (wasNowPlayingTile && current != null
                        && !sameMediaArtwork(current, next)) {
                    TransitionDrawable transition = new TransitionDrawable(
                            new Drawable[]{copyDrawable(current), next});
                    transition.setCrossFadeEnabled(true);
                    h.banner.setImageDrawable(transition);
                    transition.startTransition(240);
                } else if (!sameMediaArtwork(current, next)) {
                    h.banner.setImageDrawable(next);
                }
                h.banner.setVisibility(View.VISIBLE);
            } else {
                h.banner.setImageDrawable(null);
                h.banner.setVisibility(View.GONE);
            }
            if (e.icon != null && e.banner == null) {
                int iconPadding = Math.round(12f * ctx.getResources().getDisplayMetrics().density);
                h.icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
                h.icon.setImageDrawable(e.icon);
                h.icon.setVisibility(View.VISIBLE);
            } else {
                h.icon.setVisibility(View.GONE);
            }
            h.label.setVisibility(View.GONE);
            mediaControlIcon.setImageResource(nowPlaying.playing
                    ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
            mediaControlSurface.setContentDescription(ctx.getString(nowPlaying.playing
                    ? R.string.now_playing_pause : R.string.now_playing_play));
            mediaControlSurface.setVisibility(View.VISIBLE);
        } else if (e.kind == Entry.FOLDER) {
            mediaControlSurface.setVisibility(View.GONE);
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            h.icon.setPadding(0, 0, 0, 0);
            h.icon.setImageDrawable(folderPreview(e, h.card.getWidth(), h.card.getHeight()));
            h.icon.setVisibility(View.VISIBLE);
            h.label.setVisibility(View.GONE);
        } else if (e.banner != null) {
            mediaControlSurface.setVisibility(View.GONE);
            Drawable current = h.banner.getDrawable();
            Drawable next = copyDrawable(e.banner);
            if (e.kind == Entry.NOW_PLAYING && wasNowPlayingTile && current != null
                    && !sameMediaArtwork(current, next)) {
                TransitionDrawable transition = new TransitionDrawable(
                        new Drawable[]{copyDrawable(current), next});
                transition.setCrossFadeEnabled(true);
                h.banner.setImageDrawable(transition);
                transition.startTransition(240);
            } else if (!sameMediaArtwork(current, next)) {
                h.banner.setImageDrawable(next);
            }
            h.banner.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else if (e.icon != null) {
            mediaControlSurface.setVisibility(View.GONE);
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            int iconPadding = Math.round(12f * ctx.getResources().getDisplayMetrics().density);
            h.icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setVisibility(View.GONE);
        } else {
            mediaControlSurface.setVisibility(View.GONE);
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            int iconPadding = Math.round(12f * ctx.getResources().getDisplayMetrics().density);
            h.icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
            h.icon.setImageDrawable(null);
            h.icon.setVisibility(View.GONE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }

        h.card.setOnClickListener(v -> {
            if (editMode) {
                if (listener != null) listener.onHomeEditModeFinished();
            } else if (e.kind == Entry.NOW_PLAYING) {
                if (listener != null) listener.onNowPlayingTileSelected();
            } else {
                launch(e);
            }
        });
        h.card.setOnLongClickListener(e.kind == Entry.ALL_APPS || e.kind == Entry.LAUNCHER_SETTINGS
                || e.kind == Entry.NOW_PLAYING ? null : v -> {
                    if (h.longPressHandled) return true;
                    h.longPressHandled = true;
                    openTileMenu(e, h.card);
                    return true;
                });
        h.boundKind = e.kind;
    }

    private boolean sameMediaArtwork(Drawable first, Drawable second) {
        if (first == second) return true;
        if (first instanceof BitmapDrawable && second instanceof BitmapDrawable) {
            return ((BitmapDrawable) first).getBitmap() == ((BitmapDrawable) second).getBitmap();
        }
        return false;
    }

    void resetDockTranslations(RecyclerView parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int position = parent.getChildAdapterPosition(child);
            if (position >= 0 && position < dockSize) child.setTranslationX(0f);
        }
    }

    private void openTileMenu(Entry entry, View anchor) {
        DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
        int panelWidth = Math.min(metrics.widthPixels - dp(32), dp(250));
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.setBackground(roundRect(0xF21E1E1E, 0x33FFFFFF, dp(18)));
        panel.setElevation(dp(18));

        TextView title = new TextView(ctx);
        title.setText(entry.kind == Entry.FOLDER
                ? R.string.folder_options : R.string.app_options);
        title.setTextColor(ctx.getColor(R.color.tvos_label_primary));
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(8), dp(3), dp(8), dp(6));
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PopupWindow popup = new PopupWindow(panel, panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(18));
        popup.setAnimationStyle(animations && !reducedMotion
                ? R.style.PopupWindowExitAnimation : 0);

        View firstAction;
        if (entry.kind == Entry.FOLDER) {
            firstAction = addMenuAction(panel, R.string.open_folder, () -> {
                popup.dismiss();
                openFolder(entry);
            });
            addMenuAction(panel, R.string.manage_folders, () -> {
                popup.dismiss();
                openAppsManagement(null);
            });
        } else {
            firstAction = addMenuAction(panel, R.string.home_edit_mode, () -> {
                popup.dismiss();
                openHomeEditMode();
            });
            if (favourites.contains(entry.pkg)) {
                addMenuAction(panel, R.string.apps_management, () -> {
                    popup.dismiss();
                    openAppsManagement(null);
                });
            } else {
                addMenuAction(panel, R.string.move_to_folder, () -> {
                    popup.dismiss();
                    openAppsManagement(entry.pkg);
                });
                addMenuAction(panel, R.string.apps_management, () -> {
                    popup.dismiss();
                    openAppsManagement(null);
                });
            }
            addMenuAction(panel, R.string.app_info, () -> {
                popup.dismiss();
                Intent intent = new Intent(ctx, AppInfoActivity.class)
                        .putExtra(AppInfoActivity.EXTRA_PACKAGE, entry.pkg);
                try {
                    ctx.startActivity(intent);
                    if (animations && !reducedMotion && ctx instanceof Activity) {
                        ((Activity) ctx).overridePendingTransition(
                                R.anim.app_open_enter, R.anim.app_open_exit);
                    }
                } catch (ActivityNotFoundException ex) {
                    Toast.makeText(ctx, R.string.app_info_unavailable, Toast.LENGTH_SHORT).show();
                }
            });
            addMenuAction(panel, R.string.app_info_uninstall, () -> {
                popup.dismiss();
                Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + entry.pkg));
                try {
                    ctx.startActivity(uninstall);
                } catch (RuntimeException ex) {
                    Toast.makeText(ctx, R.string.app_info_uninstall_failed, Toast.LENGTH_SHORT).show();
                }
            });
        }

        panel.measure(View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST));
        int panelHeight = panel.getMeasuredHeight();
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int gap = dp(10);
        int x = location[0] + anchor.getWidth() + gap;
        if (x + panelWidth > metrics.widthPixels) x = location[0] - panelWidth - gap;
        x = Math.max(dp(8), Math.min(x, metrics.widthPixels - panelWidth - dp(8)));
        int y = location[1] + (anchor.getHeight() - panelHeight) / 2;
        y = Math.max(dp(8), Math.min(y, metrics.heightPixels - panelHeight - dp(8)));
        popup.showAtLocation(anchor.getRootView(), Gravity.TOP | Gravity.LEFT, x, y);
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(150).setInterpolator(new DecelerateInterpolator()).start();
        firstAction.requestFocus();
    }

    private View addMenuAction(LinearLayout panel, int label, Runnable action) {
        TextView button = new TextView(ctx);
        button.setText(label);
        button.setTextColor(ctx.getResources().getColorStateList(R.drawable.pill_text));
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setClickable(true);
        button.setBackgroundResource(R.drawable.settings_choice_bg);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        params.topMargin = dp(5);
        panel.addView(button, params);
        return button;
    }

    private void openAppsManagement(String packageToSelect) {
        Intent intent = new Intent(ctx, AppsManagementActivity.class);
        if (packageToSelect != null) {
            intent.putExtra(AppsManagementActivity.EXTRA_SELECT_PACKAGE, packageToSelect);
        }
        ctx.startActivity(intent);
        if (animations && !reducedMotion && ctx instanceof Activity) {
            ((Activity) ctx).overridePendingTransition(
                    R.anim.app_open_enter, R.anim.app_open_exit);
        }
    }

    private void openHomeEditMode() {
        Intent intent = new Intent(ctx, MainActivity.class)
                .setAction(MainActivity.ACTION_HOME_EDIT_MODE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    private Drawable folderPreview(Entry folder, int width, int height) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int bitmapWidth = Math.max(1, width > 0 ? width : Math.round(DESIGN_W_DP * density));
        int bitmapHeight = Math.max(1, height > 0 ? height : Math.round(DESIGN_H_DP * density));
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setColor(0xFF59636B);
        float radius = 14f * density;
        canvas.drawRoundRect(new RectF(0, 0, bitmapWidth, bitmapHeight), radius, radius, background);
        background.setStyle(Paint.Style.STROKE);
        background.setStrokeWidth(Math.max(1f, density));
        background.setColor(0x66FFFFFF);
        canvas.drawRoundRect(new RectF(density / 2f, density / 2f,
                bitmapWidth - density / 2f, bitmapHeight - density / 2f),
                radius, radius, background);

        int count = Math.min(6, folder.folderApps.size());
        int columns = Math.min(3, count);
        int rows = (count + columns - 1) / columns;
        float sidePadding = 8f * density;
        float topPadding = 7f * density;
        float gap = 5f * density;
        float cellWidth = (bitmapWidth - sidePadding * 2f - gap * (columns - 1)) / columns;
        float cellHeight = Math.min(24f * density,
                (bitmapHeight - topPadding * 2f - gap * (rows - 1)) / rows);
        float totalHeight = rows * cellHeight + (rows - 1) * gap;
        float top = Math.max(topPadding, (bitmapHeight - totalHeight) / 2f);
        background.setStyle(Paint.Style.FILL);

        for (int i = 0; i < count; i++) {
            int column = i % columns;
            int row = i / columns;
            float left = sidePadding + column * (cellWidth + gap);
            float cellTop = top + row * (cellHeight + gap);
            RectF cell = new RectF(left, cellTop, left + cellWidth, cellTop + cellHeight);
            background.setColor(0xCC111820);
            canvas.drawRoundRect(cell, 3f * density, 3f * density, background);

            Drawable source = folder.folderApps.get(i).icon;
            if (source == null) continue;
            Drawable icon = source.getConstantState() == null
                    ? source : source.getConstantState().newDrawable(ctx.getResources()).mutate();
            float inset = 3f * density;
            Rect bounds = new Rect(Math.round(cell.left + inset), Math.round(cell.top + inset),
                    Math.round(cell.right - inset), Math.round(cell.bottom - inset));
            icon.setBounds(bounds);
            icon.draw(canvas);
        }
        BitmapDrawable preview = new BitmapDrawable(ctx.getResources(), bitmap);
        preview.setFilterBitmap(true);
        return preview;
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.ALL_APPS) {
            Intent intent = new Intent(ctx, AllAppsActivity.class);
            if (!(ctx instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            if (animations && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(
                        R.anim.app_open_enter, R.anim.app_open_exit);
            }
            return;
        }
        if (e.kind == Entry.FOLDER) {
            openFolder(e);
            return;
        }
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (e.kind == Entry.LAUNCHER_SETTINGS) {
            ctx.startActivity(new Intent(ctx, MainActivity.class)
                    .setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            if (animations && ctx instanceof Activity) {
                ((Activity) ctx).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (Exception ex) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    private void openFolder(Entry folder) {
        Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int panelWidth = Math.min(screenWidth - dp(64), dp(690));

        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(20), dp(24), dp(24));
        panel.setBackground(roundRect(0xE61E1E1E, 0x26FFFFFF, dp(26)));
        panel.setAlpha(0f);
        panel.setScaleX(0.88f);
        panel.setScaleY(0.88f);
        panel.setElevation(dp(18));

        TextView title = new TextView(ctx);
        title.setText(folder.label);
        title.setTextColor(ctx.getColor(R.color.tvos_label_primary));
        title.setTextSize(20);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(18), dp(9), dp(18), dp(9));
        title.setBackground(roundRect(0xCC202923, 0x22FFFFFF, dp(10)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.gravity = Gravity.CENTER_HORIZONTAL;
        titleParams.bottomMargin = dp(18);
        panel.addView(title, titleParams);

        ScrollView scroll = new ScrollView(ctx);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackground(roundRect(0x8C12171C, 0x1FFFFFFF, dp(26)));
        scroll.setPadding(dp(12), dp(12), dp(12), dp(12));

        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(3);
        grid.setUseDefaultMargins(false);
        int gap = dp(10);
        int cellWidth = Math.max(dp(100), (panelWidth - dp(48) - dp(24) - gap * 2) / 3);
        View firstTile = null;
        for (int i = 0; i < folder.folderApps.size(); i++) {
            Entry app = folder.folderApps.get(i);
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(4), dp(4), dp(4), dp(4));

            FrameLayout tile = new FrameLayout(ctx);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setBackground(roundRect(ctx.getColor(R.color.tvos_tile), 0x22FFFFFF, dp(15)));
            tile.setClipToOutline(true);
            tile.setElevation(dp(2));
            ImageView artwork = new ImageView(ctx);
            artwork.setScaleType(app.banner != null
                    ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            if (app.banner != null) {
                artwork.setImageDrawable(copyDrawable(app.banner));
            } else if (app.icon != null) {
                artwork.setPadding(dp(16), dp(12), dp(16), dp(12));
                artwork.setImageDrawable(copyDrawable(app.icon));
            }
            tile.addView(artwork, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView label = new TextView(ctx);
            label.setText(app.label);
            label.setTextColor(ctx.getColor(R.color.tvos_label_primary));
            label.setTextSize(12.5f);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setAlpha(0f);
            label.setPadding(dp(3), dp(3), dp(3), 0);

            tile.setOnFocusChangeListener((view, focused) -> {
                label.animate().cancel();
                label.animate().alpha(focused ? 1f : 0f).setDuration(120).start();
                tile.animate().cancel();
                tile.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f)
                        .translationZ(focused ? dp(8) : dp(2)).setDuration(150).start();
            });
            tile.setOnClickListener(view -> {
                dialog.dismiss();
                launch(app);
            });
            item.addView(tile, new LinearLayout.LayoutParams(cellWidth, Math.round(cellWidth * 0.60f)));
            item.addView(label, new LinearLayout.LayoutParams(cellWidth, dp(22)));

            GridLayout.LayoutParams itemParams = new GridLayout.LayoutParams();
            itemParams.width = cellWidth;
            itemParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            itemParams.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            itemParams.columnSpec = GridLayout.spec(i % 3, 1f);
            itemParams.rowSpec = GridLayout.spec(i / 3);
            grid.addView(item, itemParams);
            if (firstTile == null) firstTile = tile;
        }
        scroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxGridHeight = Math.max(dp(120), Math.round(screenHeight * 0.66f) - dp(90));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(gridHeight(folder.folderApps.size(), cellWidth, gap), maxGridHeight));
        panel.addView(scroll, scrollParams);

        dialog.setContentView(panel);
        dialog.setOnShowListener(ignored -> panel.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(new OvershootInterpolator(0.7f)).start());
        Window window = dialog.getWindow();
        if (window != null) {
            if (animations && !reducedMotion) {
                window.setWindowAnimations(R.style.PopupWindowExitAnimation);
            }
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.48f;
            window.setAttributes(attributes);
        }
        dialog.show();
        if (window != null) window.setLayout(panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (firstTile != null) firstTile.requestFocus();
    }

    private int gridHeight(int itemCount, int cellWidth, int gap) {
        int rows = (itemCount + 2) / 3;
        int cellHeight = Math.round(cellWidth * 0.60f) + dp(22) + gap;
        return rows * cellHeight + gap;
    }

    private GradientDrawable roundRect(int color, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(Math.max(1, dp(1)), strokeColor);
        return drawable;
    }

    private Drawable copyDrawable(Drawable drawable) {
        if (drawable == null) return null;
        Drawable.ConstantState state = drawable.getConstantState();
        return state == null ? drawable : state.newDrawable(ctx.getResources()).mutate();
    }

    private int dp(int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    /** Dock slots that hold an app: the whole dock unless some slots are empty. */
    int dockFilled() {
        int playerOffset = nowPlaying == null ? 0 : 1;
        return playerOffset + (emptySlots > 0 ? docked : baseDockSize);
    }

    int appsTilePosition() {
        int playerOffset = nowPlaying == null ? 0 : 1;
        return playerOffset + DockSlots.appsPosition(docked, Math.max(0, baseDockSize - 2), emptySlots);
    }

    void setEditMode(boolean enabled) {
        editMode = enabled;
        notifyDataSetChanged();
    }

    boolean moveInEditMode(int position, int direction) {
        if (!editMode || direction == 0) return false;
        int destination = position + direction;
        if (position < 0 || destination < 0 || destination >= items.size()) return false;
        int playerOffset = nowPlaying == null ? 0 : 1;
        int firstDockApp = playerOffset;
        int afterDockApps = firstDockApp + docked;
        if (position < playerOffset || destination < playerOffset) return false;
        if (position < afterDockApps || destination < afterDockApps) {
            if (position < firstDockApp || destination < firstDockApp
                    || position >= afterDockApps || destination >= afterDockApps) return false;
            Collections.swap(items, position, destination);
            saveDockOrder(firstDockApp, afterDockApps);
        } else {
            Entry from = items.get(position);
            Entry to = items.get(destination);
            if ((from.kind != Entry.APP && from.kind != Entry.CARD)
                    || (to.kind != Entry.APP && to.kind != Entry.CARD)) return false;
            Collections.swap(items, position, destination);
            appOrder.clear();
            for (Entry entry : items) {
                if ((entry.kind == Entry.APP || entry.kind == Entry.CARD)
                        && !appOrder.contains(entry.pkg)) {
                    appOrder.add(entry.pkg);
                }
            }
            ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                    .putString("app_order", join(appOrder))
                    .putBoolean("apps_changed", true)
                    .apply();
        }
        notifyDataSetChanged();
        return true;
    }

    private void saveDockOrder(int firstDockApp, int afterDockApps) {
        dockOrder.clear();
        for (int i = firstDockApp; i < afterDockApps; i++) {
            String pkg = items.get(i).pkg;
            if (!dockOrder.contains(pkg)) dockOrder.add(pkg);
        }
        for (String pkg : favourites) if (!dockOrder.contains(pkg)) dockOrder.add(pkg);
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putString("dock_order", join(dockOrder))
                .putBoolean("apps_changed", true)
                .apply();
    }

    private String join(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() > 0) joined.append(',');
            joined.append(value);
        }
        return joined.toString();
    }

    /** A position focus can land on: an empty dock slot gives the last dock app. */
    int focusablePosition(int position) {
        int playerOffset = nowPlaying == null ? 0 : 1;
        if (playerOffset > 0 && position == 0) return 0;
        return playerOffset + DockSlots.focusable(position - playerOffset, docked, emptySlots,
                Math.max(0, baseDockSize - 2));
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    Drawable bannerAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).banner : null;
    }

    Drawable iconAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).icon : null;
    }

    String packageAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).pkg : "";
    }

    float focusScale() {
        if (!animations) return 1f;
        if (reducedMotion) return 1.08f;
        return advancedEffects ? 1.25f : 1.2267f;
    }

    float focusedScaleAt(int position) {
        return focusScale() * (position >= 0 && position < dockSize ? dockScale : 1f);
    }

    /** A vivid colour sampled from the app's banner (or icon), cached per entry; a card's accent when it set one. */
    int ambientColorAt(int position) {
        if (position < 0 || position >= items.size()) return 0xFF444444;
        Entry e = items.get(position);
        if (e.ambient == 0) e.ambient = vividColor(e.banner != null ? e.banner : e.icon);
        return e.ambient;
    }

    private static int vividColor(Drawable live) {
        if (live == null) return 0xFF444444;
        // Draw a private copy: changing the bounds of the drawable an ImageView shows would blank the tile.
        Drawable d = live.getConstantState() != null ? live.getConstantState().newDrawable().mutate() : live;
        Rect saved = new Rect(d.getBounds());
        int n = 12;
        Bitmap bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        d.setBounds(saved);
        float[] hsv = new float[3];
        int best = 0xFF444444;
        float bestScore = -1f;
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int p = bmp.getPixel(x, y);
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                Color.colorToHSV(p, hsv);
                float score = hsv[1] * Math.min(1f, hsv[2] * 1.4f);   // saturated and not too dark
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
        }
        bmp.recycle();
        if (bestScore < 0.25f) {   // nearly monochrome banner (e.g. white): use its average, lifted
            int avg = Color.rgb((int) (r / (n * n)), (int) (g / (n * n)), (int) (b / (n * n)));
            Color.colorToHSV(avg, hsv);
            hsv[2] = Math.max(hsv[2], 0.55f);
            return Color.HSVToColor(hsv);
        }
        Color.colorToHSV(best, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.1f);
        hsv[2] = Math.max(0.5f, hsv[2]);
        return Color.HSVToColor(hsv);
    }

    /** Corner radius for a tile of this width: 16dp at the design width, proportionally less when smaller. */
    static float cornerRadius(int tileWidthPx, float density) {
        return 15f * density * Math.min(1f, tileWidthPx / (DESIGN_W_DP * density));
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;
        final boolean animations;
        final boolean advancedEffects;
        final boolean reducedMotion;
        final boolean editModeWiggle;
        final float dockScale;
        int dockSize;
        boolean editMode;
        int boundKind = -1;
        ObjectAnimator wiggle;
        boolean longPressHandled;
        Runnable pendingLongPress;

        Holder(View v, TileListener listener, boolean animations, boolean advancedEffects,
               boolean reducedMotion, boolean editModeWiggle,
               float dockScale, int dockSize) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            this.animations = animations;
            this.advancedEffects = advancedEffects;
            this.reducedMotion = reducedMotion;
            this.editModeWiggle = editModeWiggle;
            this.dockScale = dockScale;
            this.dockSize = dockSize;

            final float density = v.getResources().getDisplayMetrics().density;
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // 16dp at the design width, scaled down with the tile so small tiles keep the same shape.
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(view.getWidth(), density));
                }
            });
            card.setClipToOutline(true);

            card.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (editMode) {
                        if (pendingLongPress != null) view.removeCallbacks(pendingLongPress);
                        pendingLongPress = null;
                        if (event.getAction() == KeyEvent.ACTION_UP) {
                            longPressHandled = false;
                            if (listener != null) listener.onHomeEditModeFinished();
                        }
                        return true;
                    }
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (event.isLongPress() || event.getRepeatCount() > 0) {
                            if (pendingLongPress != null) view.removeCallbacks(pendingLongPress);
                            if (!longPressHandled) view.performLongClick();
                            return true;
                        }
                        longPressHandled = false;
                        pendingLongPress = () -> {
                            if (!longPressHandled && view.isFocused()) view.performLongClick();
                        };
                        view.postDelayed(pendingLongPress,
                                android.view.ViewConfiguration.getLongPressTimeout());
                        return false;
                    }
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        if (pendingLongPress != null) view.removeCallbacks(pendingLongPress);
                        pendingLongPress = null;
                        boolean handled = longPressHandled;
                        longPressHandled = false;
                        return handled;
                    }
                }
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                banner.animate().cancel();
                name.animate().cancel();
                float scale = !animations ? 1f : reducedMotion ? 1.08f
                        : advancedEffects ? 1.25f : 1.2267f;
                int position = getBindingAdapterPosition();
                float baseScale = position >= 0 && position < dockSize ? dockScale : 1f;
                float s = focused ? scale * baseScale : baseScale;
                if (!animations) {
                    view.setScaleX(baseScale);
                    view.setScaleY(baseScale);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                    name.setAlpha(focused ? 1f : 0f);
                    name.setTranslationY(0f);
                } else if (reducedMotion) {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(0f)
                            .translationZ(0f)
                            .setDuration(focused ? 120 : 80)
                            .setInterpolator(new DecelerateInterpolator())
                            .withLayer().start();
                    name.animate().alpha(focused ? 1f : 0f).setDuration(100).start();
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .translationY(focused && advancedEffects ? -4f * density : 0f)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                            .setInterpolator(focused ? new PathInterpolator(0.32f, 1.28f, 0.42f, 1.0f) : new PathInterpolator(0.25f, 0.1f, 0.25f, 1.0f))
                            .withLayer().start();
                    if (advancedEffects) {
                        banner.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                                .setDuration(280).start();
                    }
                    name.animate().alpha(focused ? 1f : 0f)
                            .translationY(focused ? density * 4f : -density * 6f)
                            .setDuration(220).start();
                }
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }

        void setEditMode(boolean enabled) {
            editMode = enabled;
            if (enabled && editModeWiggle) {
                if (wiggle != null && wiggle.isRunning()) return;
                float angle = reducedMotion ? 0.6f : 1.15f;
                wiggle = ObjectAnimator.ofFloat(card, View.ROTATION, -angle, angle);
                wiggle.setDuration(reducedMotion ? 260 : 145);
                wiggle.setRepeatMode(ObjectAnimator.REVERSE);
                wiggle.setRepeatCount(ObjectAnimator.INFINITE);
                wiggle.setInterpolator(new LinearInterpolator());
                wiggle.start();
            } else {
                if (wiggle != null) {
                    wiggle.cancel();
                    wiggle = null;
                }
                card.setRotation(0f);
            }
        }
    }
}

package com.appletv.launcher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Launcher-owned Wi-Fi view for Fire OS versions where ordinary apps can manage networks. */
public final class NetworkSettingsActivity extends LauncherActivity {
    private static final int LOCATION_REQUEST = 61;
    private WifiManager wifi;
    private LinearLayout networkRows;
    private TextView connectionStatus;
    private TextView emptyState;
    private Button scanButton;
    private boolean scanReceiverRegistered;
    private FocusGlassController focusGlass;
    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                try {
                    showNetworks(wifi.getScanResults());
                } catch (SecurityException exception) {
                    android.util.Log.e("AppleTv.Network", "Reading Wi-Fi scan results was denied", exception);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        setContentView(buildContent());
        focusGlass = new FocusGlassController(this);
        refreshConnection();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (scanReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scanReceiver, filter);
        }
        scanReceiverRegistered = true;
        scanNetworks();
    }

    @Override
    protected void onStop() {
        if (scanReceiverRegistered) {
            unregisterReceiver(scanReceiver);
            scanReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (focusGlass != null) focusGlass.close();
        super.onDestroy();
    }

    private View buildContent() {
        boolean light = LauncherUiSettings.isLight(this);
        int surface = LauncherUiSettings.isLight(this) ? 0xFFF2F2F7 : 0xEE191A1F;
        int foreground = LauncherUiSettings.isLight(this) ? 0xFF1C1C1E : Color.WHITE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(42), dp(26), dp(42), dp(30));
        root.setBackgroundResource(light ? R.drawable.settings_backdrop_light
                : R.drawable.settings_backdrop);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(22), dp(28), dp(22));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(surface);
        panelBackground.setCornerRadius(dp(28));
        panelBackground.setStroke(dp(1), LauncherUiSettings.isLight(this) ? 0x22000000 : 0x36FFFFFF);
        panel.setBackground(panelBackground);
        root.addView(panel, new LinearLayout.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText(R.string.network_settings);
        title.setTextColor(foreground);
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(title);

        connectionStatus = new TextView(this);
        connectionStatus.setTextColor(foreground);
        connectionStatus.setTextSize(18);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(8);
        panel.addView(connectionStatus, statusParams);

        scanButton = new Button(this);
        scanButton.setText(R.string.network_scan);
        styleNetworkButton(scanButton);
        scanButton.setOnClickListener(view -> scanNetworks());
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(-2, dp(54));
        scanParams.topMargin = dp(16);
        panel.addView(scanButton, scanParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        scrollParams.topMargin = dp(12);
        panel.addView(scroll, scrollParams);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        emptyState = new TextView(this);
        emptyState.setText(R.string.network_no_results);
        emptyState.setTextColor(foreground);
        emptyState.setTextSize(17);
        emptyState.setPadding(dp(10), dp(14), dp(10), dp(14));
        list.addView(emptyState);

        networkRows = list;
        scanButton.requestFocus();
        return root;
    }

    private void scanNetworks() {
        if (wifi == null) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        scanButton.setText(R.string.network_scanning);
        try {
            wifi.startScan();
            showNetworks(wifi.getScanResults());
        } catch (SecurityException exception) {
            android.util.Log.e("AppleTv.Network", "Wi-Fi scan was denied", exception);
            Toast.makeText(this, R.string.network_permission_required, Toast.LENGTH_LONG).show();
        } catch (RuntimeException exception) {
            android.util.Log.e("AppleTv.Network", "Wi-Fi scan failed", exception);
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        } finally {
            scanButton.setText(R.string.network_scan);
        }
    }

    private void showNetworks(List<ScanResult> results) {
        networkRows.removeViews(1, Math.max(0, networkRows.getChildCount() - 1));
        Map<String, ScanResult> strongestBySsid = new LinkedHashMap<>();
        if (results != null) {
            for (ScanResult result : results) {
                if (result.SSID == null || result.SSID.trim().isEmpty()) continue;
                ScanResult current = strongestBySsid.get(result.SSID);
                if (current == null || result.level > current.level) strongestBySsid.put(result.SSID, result);
            }
        }
        List<ScanResult> networks = new ArrayList<>(strongestBySsid.values());
        Collections.sort(networks, Comparator.comparingInt((ScanResult result) -> result.level).reversed());
        emptyState.setVisibility(networks.isEmpty() ? View.VISIBLE : View.GONE);
        for (ScanResult network : networks) addNetworkRow(network);
    }

    private void addNetworkRow(ScanResult network) {
        boolean secured = network.capabilities != null
                && network.capabilities.toUpperCase(Locale.ROOT).matches(".*(WEP|WPA|SAE|EAP).*");
        String security = secured ? "•  " + getString(R.string.network_secure) : "•  " + getString(R.string.network_open);
        Button row = new Button(this);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setText(network.SSID + "     " + security);
        row.setTextSize(18);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        styleNetworkButton(row);
        row.setOnClickListener(view -> {
            if (secured) requestPassword(network.SSID);
            else connect(network.SSID, null, false);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(58));
        params.bottomMargin = dp(5);
        networkRows.addView(row, params);
    }

    private void requestPassword(String ssid) {
        EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.network_password, ssid))
                .setView(password)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.network_connect, (dialog, which) ->
                        connect(ssid, password.getText().toString(), true))
                .show();
    }

    private void connect(String ssid, String password, boolean secured) {
        if (wifi == null) return;
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.SSID = "\"" + ssid.replace("\"", "\\\"") + "\"";
        if (secured) {
            configuration.preSharedKey = "\"" + password.replace("\"", "\\\"") + "\"";
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        } else {
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        }
        try {
            int networkId = wifi.addNetwork(configuration);
            if (networkId < 0 || !wifi.enableNetwork(networkId, true) || !wifi.reconnect()) {
                Toast.makeText(this, R.string.network_connect_failed, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, R.string.network_connect, Toast.LENGTH_SHORT).show();
            connectionStatus.postDelayed(this::refreshConnection, 1400);
        } catch (SecurityException | UnsupportedOperationException exception) {
            android.util.Log.e("AppleTv.Network", "Fire OS denied the Wi-Fi connection request", exception);
            Toast.makeText(this, R.string.network_connect_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshConnection() {
        if (wifi == null || connectionStatus == null) return;
        try {
            WifiInfo info = wifi.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid != null && !ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                ssid = ssid.replace("\"", "");
                connectionStatus.setText(getString(R.string.network_connected, ssid));
            } else {
                connectionStatus.setText(R.string.network_not_connected);
            }
        } catch (SecurityException exception) {
            android.util.Log.e("AppleTv.Network", "Reading the connected Wi-Fi network was denied", exception);
            connectionStatus.setText(R.string.network_unknown);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_REQUEST) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanNetworks();
        } else {
            Toast.makeText(this, R.string.network_permission_required, Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void styleNetworkButton(Button button) {
        boolean light = LauncherUiSettings.isLight(this);
        button.setBackgroundResource(light ? R.drawable.network_button_bg_light
                : R.drawable.network_button_bg_dark);
        button.setTextColor(getResources().getColorStateList(
                light ? R.color.network_button_text_light : R.color.network_button_text_dark));
        button.setStateListAnimator(null);
        button.setPadding(dp(14), 0, dp(14), 0);
    }
}

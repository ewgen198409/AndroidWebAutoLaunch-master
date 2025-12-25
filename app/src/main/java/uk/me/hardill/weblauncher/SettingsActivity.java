package uk.me.hardill.weblauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Created by hardillb on 23/11/16.
 */

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(newBase);
        String language = prefs.getString("language", "en");
        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Configuration configuration = newBase.getResources().getConfiguration();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            configuration.setLocale(locale);
            newBase = newBase.createConfigurationContext(configuration);
        } else {
            configuration.locale = locale;
            newBase.getResources().updateConfiguration(configuration, newBase.getResources().getDisplayMetrics());
        }

        super.attachBaseContext(newBase);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
            PreferenceCategory category = findPreference("upnp_info_category");
            boolean enabled = getPreferenceManager().getSharedPreferences().getBoolean("enable_upnp_renderer", false);
            category.setVisible(enabled);
            if (enabled) {
                updateUpnpInfo();
            }

            // Populate media player preference
            ListPreference mediaPlayerPref = findPreference("media_player");
            if (mediaPlayerPref != null) {
                PackageManager pm = getActivity().getPackageManager();
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse("content://"), "video/*");
                List<ResolveInfo> videoApps = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
                intent.setDataAndType(Uri.parse("content://"), "audio/*");
                List<ResolveInfo> audioApps = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

                // Combine and remove duplicates
                List<ResolveInfo> allApps = new ArrayList<>(videoApps);
                for (ResolveInfo audioApp : audioApps) {
                    boolean exists = false;
                    for (ResolveInfo videoApp : videoApps) {
                        if (audioApp.activityInfo.packageName.equals(videoApp.activityInfo.packageName)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        allApps.add(audioApp);
                    }
                }

                CharSequence[] entries = new CharSequence[allApps.size() + 1];
                CharSequence[] entryValues = new CharSequence[allApps.size() + 1];
                entries[0] = "Default";
                entryValues[0] = "default";
                for (int i = 0; i < allApps.size(); i++) {
                    entries[i + 1] = allApps.get(i).loadLabel(pm);
                    entryValues[i + 1] = allApps.get(i).activityInfo.packageName;
                }
                mediaPlayerPref.setEntries(entries);
                mediaPlayerPref.setEntryValues(entryValues);
                mediaPlayerPref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
            }

            // Add summary provider for renderer name
            EditTextPreference rendererNamePref = findPreference("renderer_name");
            if (rendererNamePref != null) {
                rendererNamePref.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
            }

            // Add summary provider for http port
            EditTextPreference httpPortPref = findPreference("http_port");
            if (httpPortPref != null) {
                httpPortPref.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if ("enable_upnp_renderer".equals(key)) {
                boolean enabled = sharedPreferences.getBoolean(key, false);
                Log.i("SettingsActivity", "DLNA renderer setting changed to: " + enabled);
                PreferenceCategory category = findPreference("upnp_info_category");
                category.setVisible(enabled);
                if (enabled) {
                    updateUpnpInfo();
                }
                Intent serviceIntent = new Intent(getActivity(), UpnpAudioRendererService.class);
                if (enabled) {
                    Log.i("SettingsActivity", "Starting DLNA renderer service");
                    getActivity().startService(serviceIntent);
                } else {
                    Log.i("SettingsActivity", "Stopping DLNA renderer service");
                    getActivity().stopService(serviceIntent);
                }
            }
        }

        private void updateUpnpInfo() {
            Preference ipPref = findPreference("local_ip");
            Preference uuidPref = findPreference("device_uuid");
            ipPref.setSummary(getLocalIpAddress());
            uuidPref.setSummary("Generated on service start - check logs");
        }

        private String getLocalIpAddress() {
            try {
                Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
                while (networkInterfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = networkInterfaces.nextElement();
                    Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress address = addresses.nextElement();
                        if (!address.isLoopbackAddress() && address.getHostAddress().indexOf(':') == -1) {
                            return address.getHostAddress();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("Settings", "Error getting IP", e);
            }
            return "Not available";
        }
    }
}

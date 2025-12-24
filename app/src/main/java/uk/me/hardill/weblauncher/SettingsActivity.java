package uk.me.hardill.weblauncher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
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

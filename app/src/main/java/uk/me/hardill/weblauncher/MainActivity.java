package uk.me.hardill.weblauncher;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Context;
import android.os.Bundle;
import android.webkit.WebView;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

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
        setContentView(R.layout.activity_main);

        WebView wv = (WebView) findViewById(R.id.webView);

        String about = getResources().getString(R.string.about_html);

        wv.loadData(about,"text/html", null);
    }


}

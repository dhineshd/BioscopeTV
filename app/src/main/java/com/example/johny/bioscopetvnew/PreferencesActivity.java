package com.example.johny.bioscopetvnew;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;

import com.example.johny.bioscopetvnew.R;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PreferencesActivity extends AppCompatActivity {

    private SettingsFragment settingsFragment;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preferences);

        settingsFragment = new SettingsFragment();

        // Display the fragment as the main content.
        getFragmentManager().beginTransaction()
                .replace(R.id.settings_frag_contents, settingsFragment)
                .commit();


    }

    @Override
    public void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(settingsFragment);
    }

    @Override
    public void onPause() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(settingsFragment);
        super.onPause();
    }

    /**
     * This fragment shows the preferences for the first header.
     */
    public static class SettingsFragment extends PreferenceFragment
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private final int UNLOCK_COUNT = 10;

        private int currentCount = 0;

        private PreferenceCategory settingsPreferenceCategory;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Make sure default values are applied.  In a real app, you would
            // want this in a shared function that is used to retrieve the
            // SharedPreferences wherever they are needed.
            PreferenceManager.setDefaultValues(getActivity(), R.xml.preferences, false);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.preferences);

            settingsPreferenceCategory = (PreferenceCategory)findPreference
                    (getString(R.string.pref_cat_settings_key));

            final Preference allowEventCreationPref = findPreference(getString(R.string.pref_alloweventcreation_key));

            settingsPreferenceCategory.removePreference(allowEventCreationPref);

            Preference versionNumberPref = findPreference(getString(R.string.pref_version_number_key));
            versionNumberPref.setSummary(getVersionName());

            versionNumberPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    log.info("version number pressed");

                    if(currentCount == 0) {
                        new Handler().postDelayed(new Runnable() {

                            @Override
                            public void run() {
                                currentCount=0;
                            }
                        }, 10000);
                    }

                    if(++currentCount >= UNLOCK_COUNT) {
                        settingsPreferenceCategory.addPreference(allowEventCreationPref);
                    }

                    return false;
                }
            });

        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
             if (getString(R.string.pref_ga_display_features_key).equals(key)) {

                final SwitchPreference connectionPref = (SwitchPreference) findPreference(key);
                boolean isOn = connectionPref.isChecked();
                log.info("User changed the pref_ga_display_features_key Setting, current value is {}", isOn);

                if(!isOn) {
                    log.info("Asking user for confirmation when disabling OpenH264");
                    new AlertDialog.Builder(getActivity())
                            .setTitle("Google Analytics")
                            .setMessage("Do you really want disable Google Analytics to collect data about your traffic via anonymous identifiers.")
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton(android.R.string.yes, null)
                            .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int whichButton) {
                                    if (connectionPref != null) {
                                        connectionPref.setChecked(true);
                                    }
                                }
                            }).show();
                }

            } else if(getString(R.string.pref_alloweventcreation_key).equals(key)) {
                 final SwitchPreference connectionPref = (SwitchPreference) findPreference(key);
                 boolean isOn = connectionPref.isChecked();
                 log.info("User changed the pref_alloweventcreation_key Setting, current value is {}", isOn);

                 if(isOn) {
                     log.info("Asking user for confirmation when enabling event creation");

                     new AlertDialog.Builder(getActivity())
                             .setTitle("Event Creation")
                             .setMessage("Only Bioscope team should enable event creation. Do you want to create events?")
                             .setIcon(android.R.drawable.ic_dialog_alert)
                             .setPositiveButton(android.R.string.yes, null)
                             .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                 public void onClick(DialogInterface dialog, int whichButton) {
                                     if (connectionPref != null) {
                                         connectionPref.setChecked(false);
                                     }
                                 }
                             }).show();
                 }

                 log.info("Setting allow event creation to {}", connectionPref.isChecked());
                 SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getActivity());
                 SharedPreferences.Editor editor = sharedPref.edit();
                 editor.putBoolean(getString(R.string.pref_alloweventcreation_key), connectionPref.isChecked());
                 editor.commit();
             }

            else {
                log.info("Unknown preference change for key '{}'", key);
            }

            log.info("Preferences changed for {}", key);
        }

        private String getVersionName() {

            String versionName = "";
            try {
                PackageInfo pinfo = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0);
                versionName =  pinfo.versionName;
            } catch (PackageManager.NameNotFoundException e) {
                log.warn("Caught NameNotFoundException", e);
            }

            log.info("versionName is {}", versionName);
            return versionName;
        }



    }
}

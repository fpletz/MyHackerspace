/*
 * Copyright (C) 2012-2013 Aubort Jean-Baptiste (Rorist)
 * Licensed under GNU's GPL 2, see README
 */

package ch.fixme.status;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimationDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.util.Linkify;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

public class Main extends Activity {

    // API: http://hackerspaces.nl/spaceapi/
    // http://spaceapi.net

    protected static String TAG = "MyHackerspace";
    protected static final String PKG = "ch.fixme.status";
    protected static final String OPEN = "Open";
    protected static final String CLOSED = "Closed";
    protected static final String PREF_API_URL_WIDGET = "api_url_widget_";
    protected static final String PREF_INIT_WIDGET = "init_widget_";
    protected static final String PREF_LAST_WIDGET = "last_widget_";
    protected static final String PREF_FORCE_WIDGET = "force_widget_";
    protected static final String STATE_HS = "hs";
    protected static final String STATE_DIR = "dir";
    private static final String PREF_API_URL = "apiurl";
    private static final int DIALOG_LOADING = 0;
    private static final int DIALOG_LIST = 1;
    private static final String TWITTER = "https://twitter.com/#!/";
    private static final String FOURSQUARE = "https://foursquare.com/v/";
    private static final String MAP_SEARCH = "geo:0,0?q=";
    private static final String MAP_COORD = "geo:%s,%s?z=23&q=%s&";

    private SharedPreferences mPrefs;
    private String mResultHs;
    public String mResultDir;
    private String mApiUrl;
    private boolean finishApi = false;
    private boolean finishDir = false;

    private GetDirTask getDirTask;
    private GetApiTask getApiTask;
    private GetImage getImageTask;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(Main.this);
        Intent intent = getIntent();
        checkNetwork();
        getHsList(savedInstanceState);
        showHsInfo(intent, savedInstanceState);
    }

    @Override
    protected void onDestroy() {
        if (getApiTask != null) {
            getApiTask.cancel(true);
        }
        if (getDirTask != null) {
            getDirTask.cancel(true);
        }
        if (getImageTask != null) {
            getImageTask.cancel(true);
        }
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_refresh:
                checkNetwork();
                showHsInfo(getIntent(), null);
                return true;
            case R.id.menu_choose:
                showDialog(DIALOG_LIST);
                return true;
            case R.id.menu_prefs:
                startActivity(new Intent(Main.this, Prefs.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public Bundle onRetainNonConfigurationInstance() {
        Bundle data = new Bundle(2);
        data.putString(STATE_HS, mResultHs);
        data.putString(STATE_DIR, mResultDir);
        return data;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_HS, mResultHs);
        outState.putString(STATE_DIR, mResultDir);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        AlertDialog dialog = null;
        switch (id) {
            case DIALOG_LOADING:
                dialog = new ProgressDialog(this);
                dialog.setCancelable(false);
                dialog.setMessage(getString(R.string.msg_loading));
                dialog.setCancelable(true);
                ((ProgressDialog) dialog).setIndeterminate(true);
                break;
            case DIALOG_LIST:
                return createHsDialog();
        }
        return dialog;
    }

    @Override
    public void startActivity(Intent intent) {
        // http://stackoverflow.com/questions/13691241/autolink-not-working-on-htc-htclinkifydispatcher
        try {
            /* First attempt at fixing an HTC broken by evil Apple patents. */
            if (intent.getComponent() != null
                    && ".HtcLinkifyDispatcherActivity".equals(intent
                    .getComponent().getShortClassName()))
                intent.setComponent(null);
            super.startActivity(intent);
        } catch (ActivityNotFoundException e) {
			/*
			 * Probably an HTC broken by evil Apple patents. This is not
			 * perfect, but better than crashing the whole application.
			 */
            super.startActivity(Intent.createChooser(intent, null));
        }
    }

    private AlertDialog createHsDialog() {
        // Construct hackerspaces list
        try {
            JSONObject obj = new JSONObject(mResultDir);
            JSONArray arr = obj.names();
            int len = obj.length();
            String[] names = new String[len];
            final ArrayList<String> url = new ArrayList<String>(len);
            for (int i = 0; i < len; i++) {
                names[i] = arr.getString(i);
            }
            Arrays.sort(names, 0, len, String.CASE_INSENSITIVE_ORDER);
            for (int i = 0; i < len; i++) {
                url.add(i, obj.getString(names[i]));
            }

            // Create the dialog
            AlertDialog.Builder builder = new AlertDialog.Builder(Main.this);
            builder.setTitle(R.string.choose_hs).setItems(names,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            setIntent(null);
                            Editor edit = mPrefs.edit();
                            edit.putString(PREF_API_URL, url.get(which));
                            getApiTask = new GetApiTask();
                            getApiTask.execute(url.get(which));
                            edit.commit();
                        }
                    });
            return builder.create();
        } catch (Exception e) {
            e.printStackTrace();
            showError(e.getClass().getCanonicalName(), e.getLocalizedMessage() + getString(R.string.error_generic));
            return null;
        }
    }

    private void getHsList(Bundle savedInstanceState) {
        final Bundle data = (Bundle) getLastNonConfigurationInstance();
        if (data == null
                || (savedInstanceState == null && !savedInstanceState
                .containsKey(STATE_DIR))) {
            getDirTask = new GetDirTask();
            getDirTask.execute(ParseGeneric.API_DIRECTORY);
        } else {
            finishDir = true;
            mResultDir = data.getString(STATE_DIR);
        }
    }

    private void showHsInfo(Intent intent, Bundle savedInstanceState) {
        // Get hackerspace api url
        if (intent != null
                && intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
            mApiUrl = mPrefs.getString(
                    PREF_API_URL_WIDGET
                            + intent.getIntExtra(
                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                            AppWidgetManager.INVALID_APPWIDGET_ID),
                    ParseGeneric.API_DEFAULT);
        } else if (intent != null && intent.hasExtra(STATE_HS)) {
            mApiUrl = intent.getStringExtra(STATE_HS);
        } else {
            mApiUrl = mPrefs.getString(PREF_API_URL, ParseGeneric.API_DEFAULT);
        }
        // Get Data
        final Bundle data = (Bundle) getLastNonConfigurationInstance();
        if (data == null
                || (savedInstanceState == null && !savedInstanceState
                .containsKey(STATE_HS))) {
            getApiTask = new GetApiTask();
            getApiTask.execute(mApiUrl);
        } else {
            finishApi = true;
            mResultHs = data.getString(STATE_HS);
            populateDataHs();
        }
    }

    private boolean checkNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        if (netInfo == null || !netInfo.isConnected()) {
            showError(getString(R.string.error_network_title),
                    getString(R.string.error_network_msg));
            return false;
        }
        return true;
    }

    private void showError(String title, String msg) {
        if (title != null && msg != null) {
            // showDialog(DIALOG_ERROR);
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.error_title) + title)
                    .setMessage(msg)
                    .setNeutralButton(getString(R.string.ok), null).show();
        }
    }

    private void dismissLoading() {
        if (finishApi && finishDir) {
            try {
                removeDialog(DIALOG_LOADING);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, e.getMessage());
            }
        }
    }

    public class GetDirTask extends AsyncTask<String, Void, String> {

        private String mErrorTitle;
        private String mErrorMsg;

        @Override
        protected void onPreExecute() {
            showDialog(DIALOG_LOADING);
        }

        @Override
        protected String doInBackground(String... url) {
            try {
                return new Net(url[0]).getString();
            } catch (Exception e) {
                mErrorTitle = e.getClass().getCanonicalName();
                mErrorMsg = e.getLocalizedMessage();
                e.printStackTrace();
            }
            return "";
        }

        @Override
        protected void onPostExecute(String result) {
            finishDir = true;
            dismissLoading();
            if (mErrorMsg == null) {
                mResultDir = result;
            } else {
                showError(mErrorTitle, mErrorMsg + getString(R.string.error_generic));
            }
        }

        @Override
        protected void onCancelled() {
            finishDir = true;
            dismissLoading();
        }
    }

    private class GetApiTask extends AsyncTask<String, Void, String> {

        private String mErrorTitle;
        private String mErrorMsg;

        @Override
        protected void onPreExecute() {
            showDialog(DIALOG_LOADING);
            // Clean UI
            ((ScrollView) findViewById(R.id.scroll)).removeAllViews();
            ((TextView) findViewById(R.id.space_name))
                    .setText(getString(R.string.empty));
            ((TextView) findViewById(R.id.space_url))
                    .setText(getString(R.string.empty));
            ((ImageView) findViewById(R.id.space_image)).setImageBitmap(null);
        }

        @Override
        protected String doInBackground(String... url) {
            try {
                return new Net(url[0]).getString();
            } catch (Exception e) {
                mErrorTitle = e.getClass().getCanonicalName();
                mErrorMsg = e.getLocalizedMessage();
                e.printStackTrace();
            }
            return "";
        }

        @Override
        protected void onPostExecute(String result) {
            finishApi = true;
            dismissLoading();
            if (mErrorMsg == null) {
                mResultHs = result;
                populateDataHs();
            } else {
                showError(mErrorTitle, mErrorMsg + getString(R.string.error_generic));
            }
        }

        @Override
        protected void onCancelled() {
            finishApi = true;
            dismissLoading();
        }
    }

    private class GetImage extends AsyncTask<String, Void, Bitmap> {

        private int mId;
        private String mErrorTitle;
        private String mErrorMsg;

        public GetImage(int id) {
            mId = id;
        }

        @Override
        protected void onPreExecute() {
            ImageView img = (ImageView) findViewById(mId);
            img.setImageResource(android.R.drawable.ic_popup_sync);
            AnimationDrawable anim = (AnimationDrawable) img.getDrawable();
            anim.start();
        }

        @Override
        protected Bitmap doInBackground(String... url) {
            try {
                return new Net(url[0]).getBitmap();
            } catch (Exception e) {
                mErrorTitle = e.getClass().getCanonicalName();
                mErrorMsg = e.getLocalizedMessage();
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            if (mErrorMsg == null) {
                ((ImageView) findViewById(mId)).setImageBitmap(result);
            } else {
                showError(mErrorTitle, mErrorMsg + getString(R.string.error_generic));
                ((ImageView) findViewById(mId))
                        .setImageResource(android.R.drawable.ic_menu_report_image);
            }
        }

    }

    private void populateDataHs() {
        try {
            HashMap<String, Object> data = new ParseGeneric(mResultHs)
                    .getData();

            // Initialize views
            LayoutInflater inflater = getLayoutInflater();
            LinearLayout vg = (LinearLayout) inflater.inflate(R.layout.base,
                    null);
            ScrollView scroll = (ScrollView) findViewById(R.id.scroll);
            scroll.removeAllViews();
            scroll.addView(vg);

            // Mandatory fields
            ((TextView) findViewById(R.id.space_name)).setText((String) data
                    .get(ParseGeneric.API_NAME));
            ((TextView) findViewById(R.id.space_url)).setText((String) data
                    .get(ParseGeneric.API_URL));
            getImageTask = new GetImage(R.id.space_image);
            getImageTask.execute((String) data.get(ParseGeneric.API_LOGO));

            // Status text
            String status_txt = "";
            if ((Boolean) data.get(ParseGeneric.API_STATUS)) {
                status_txt = OPEN;
                ((TextView) findViewById(R.id.status_txt))
                        .setCompoundDrawablesWithIntrinsicBounds(
                                android.R.drawable.presence_online, 0, 0, 0);
            } else {
                status_txt = CLOSED;
                ((TextView) findViewById(R.id.status_txt))
                        .setCompoundDrawablesWithIntrinsicBounds(
                                android.R.drawable.presence_busy, 0, 0, 0);
            }
            if (data.containsKey(ParseGeneric.API_STATUS_TXT)) {
                status_txt += ": "
                        + (String) data.get(ParseGeneric.API_STATUS_TXT);
            }
            ((TextView) findViewById(R.id.status_txt)).setText(status_txt);

            // Status last change
            if (data.containsKey(ParseGeneric.API_LASTCHANGE)) {
                TextView tv = (TextView) inflater.inflate(R.layout.entry, null);
                tv.setAutoLinkMask(0);
                tv.setText(getString(R.string.api_lastchange) + " "
                        + (String) data.get(ParseGeneric.API_LASTCHANGE));
                vg.addView(tv);
            }

            // Status duration
            if (data.containsKey(ParseGeneric.API_EXT_DURATION)
                    && (Boolean) data.get(ParseGeneric.API_STATUS)) {
                TextView tv = (TextView) inflater.inflate(R.layout.entry, null);
                tv.setText(getString(R.string.api_duration) + " "
                        + (String) data.get(ParseGeneric.API_EXT_DURATION)
                        + getString(R.string.api_duration_hours));
                vg.addView(tv);
            }

            // Location
            Pattern ptn = Pattern.compile("^.*$", Pattern.DOTALL);
            if (data.containsKey(ParseGeneric.API_ADDRESS)
                    || data.containsKey(ParseGeneric.API_LON)) {
                TextView title = (TextView) inflater.inflate(R.layout.title,
                        null);
                title.setText(getString(R.string.api_location));
                vg.addView(title);
                inflater.inflate(R.layout.separator, vg);
                // Address
                if (data.containsKey(ParseGeneric.API_ADDRESS)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setAutoLinkMask(0);
                    tv.setText((String) data.get(ParseGeneric.API_ADDRESS));
                    Linkify.addLinks(tv, ptn, MAP_SEARCH);
                    vg.addView(tv);
                }
                // Lon/Lat
                if (data.containsKey(ParseGeneric.API_LON)
                        && data.containsKey(ParseGeneric.API_LAT)) {
                    String addr = (data.containsKey(ParseGeneric.API_ADDRESS)) ? (String) data
                            .get(ParseGeneric.API_ADDRESS)
                            : getString(R.string.empty);
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setAutoLinkMask(0);
                    tv.setText((String) data.get(ParseGeneric.API_LON) + ", "
                            + (String) data.get(ParseGeneric.API_LAT));
                    Linkify.addLinks(tv, ptn, String.format(MAP_COORD,
                            (String) data.get(ParseGeneric.API_LAT),
                            (String) data.get(ParseGeneric.API_LON), addr));
                    vg.addView(tv);
                }
            }

            // Contact
            if (data.containsKey(ParseGeneric.API_PHONE)
                    || data.containsKey(ParseGeneric.API_TWITTER)
                    || data.containsKey(ParseGeneric.API_IRC)
                    || data.containsKey(ParseGeneric.API_EMAIL)
                    || data.containsKey(ParseGeneric.API_ML)) {
                TextView title = (TextView) inflater.inflate(R.layout.title,
                        null);
                title.setText(R.string.api_contact);
                vg.addView(title);
                inflater.inflate(R.layout.separator, vg);

                // Phone
                if (data.containsKey(ParseGeneric.API_PHONE)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setText((String) data.get(ParseGeneric.API_PHONE));
                    vg.addView(tv);
                }
                // SIP
                if (data.containsKey(ParseGeneric.API_SIP)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setText((String) data.get(ParseGeneric.API_SIP));
                    vg.addView(tv);
                }
                // Twitter
                if (data.containsKey(ParseGeneric.API_TWITTER)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setText(TWITTER
                            + (String) data.get(ParseGeneric.API_TWITTER));
                    vg.addView(tv);
                }
                // Identica
                if (data.containsKey(ParseGeneric.API_IDENTICA)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setText((String) data.get(ParseGeneric.API_IDENTICA));
                    vg.addView(tv);
                }
                // Foursquare
                if (data.containsKey(ParseGeneric.API_FOURSQUARE)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setText(FOURSQUARE
                            + (String) data.get(ParseGeneric.API_FOURSQUARE));
                    vg.addView(tv);
                }
                // IRC
                if (data.containsKey(ParseGeneric.API_IRC)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setAutoLinkMask(0);
                    tv.setText((String) data.get(ParseGeneric.API_IRC));
                    vg.addView(tv);
                }
                // Email
                if (data.containsKey(ParseGeneric.API_EMAIL)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setText((String) data.get(ParseGeneric.API_EMAIL));
                    vg.addView(tv);
                }
                // Jabber
                if (data.containsKey(ParseGeneric.API_JABBER)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setText((String) data.get(ParseGeneric.API_JABBER));
                    vg.addView(tv);
                }
                // Mailing-List
                if (data.containsKey(ParseGeneric.API_ML)) {
                    TextView tv = (TextView) inflater.inflate(R.layout.entry,
                            null);
                    tv.setText((String) data.get(ParseGeneric.API_ML));
                    vg.addView(tv);
                }
            }

            // Sensors
            if (data.containsKey(ParseGeneric.API_SENSORS)) {
                // Title
                TextView title = (TextView) inflater.inflate(R.layout.title,
                        null);
                title.setText(getString(R.string.api_sensors));
                vg.addView(title);
                inflater.inflate(R.layout.separator, vg);

                HashMap<String, ArrayList<HashMap<String, String>>> sensors = (HashMap<String, ArrayList<HashMap<String, String>>>) data
                        .get(ParseGeneric.API_SENSORS);
                Set<String> names = sensors.keySet();
                Iterator<String> it = names.iterator();
                while (it.hasNext()) {
                    String name = it.next();
                    // Subtitle
                    String name_title = name.toLowerCase().replace("_", " ");
                    name_title = name_title.substring(0, 1).toUpperCase() + name_title.substring(1, name_title.length());
                    TextView subtitle = (TextView) inflater.inflate(
                            R.layout.subtitle, null);
                    subtitle.setText(name_title);
                    vg.addView(subtitle);
                    // Sensors data
                    ArrayList<HashMap<String, String>> sensorsData = sensors.get(name);
                    for (HashMap<String, String> elem : sensorsData) {
                        RelativeLayout rl = (RelativeLayout) inflater.inflate(R.layout.entry_sensor, null);
                        if (elem.containsKey(ParseGeneric.API_VALUE)) {
                            ((TextView) rl.findViewById(R.id.entry_value)).setText(elem.get(ParseGeneric.API_VALUE));
                        } else {
                            rl.findViewById(R.id.entry_value).setVisibility(View.GONE);
                        }
                        if (elem.containsKey(ParseGeneric.API_UNIT)) {
                            ((TextView) rl.findViewById(R.id.entry_unit)).setText(elem.get(ParseGeneric.API_UNIT));
                        } else {
                            rl.findViewById(R.id.entry_unit).setVisibility(View.GONE);
                        }
                        if (elem.containsKey(ParseGeneric.API_NAME2)) {
                            ((TextView) rl.findViewById(R.id.entry_name)).setText(elem.get(ParseGeneric.API_NAME2));
                        } else {
                            rl.findViewById(R.id.entry_name).setVisibility(View.GONE);
                        }
                        if (elem.containsKey(ParseGeneric.API_LOCATION2)) {
                            ((TextView) rl.findViewById(R.id.entry_location)).setText(elem.get(ParseGeneric.API_LOCATION2));
                        } else {
                            rl.findViewById(R.id.entry_location).setVisibility(View.GONE);
                        }
                        if (elem.containsKey(ParseGeneric.API_DESCRIPTION)) {
                            ((TextView) rl.findViewById(R.id.entry_description)).setText(elem.get(ParseGeneric.API_DESCRIPTION));
                        } else {
                            rl.findViewById(R.id.entry_description).setVisibility(View.GONE);
                        }
                        if (elem.containsKey(ParseGeneric.API_PROPERTIES)) {
                            ((TextView) rl.findViewById(R.id.entry_properties)).setText(elem.get(ParseGeneric.API_PROPERTIES));
                        } else {
                            rl.findViewById(R.id.entry_properties).setVisibility(View.GONE);
                        }
                        if (elem.containsKey(ParseGeneric.API_MACHINES)) {
                            ((TextView) rl.findViewById(R.id.entry_other)).setText(elem.get(ParseGeneric.API_MACHINES));
                        } else if (elem.containsKey(ParseGeneric.API_NAMES)) {
                            ((TextView) rl.findViewById(R.id.entry_other)).setText(elem.get(ParseGeneric.API_NAMES));
                        } else {
                            rl.findViewById(R.id.entry_other).setVisibility(View.GONE);
                        }
                        vg.addView(rl);
                    }
                }
            }

            // Stream and cam
            if (data.containsKey(ParseGeneric.API_STREAM)
                    || data.containsKey(ParseGeneric.API_CAM)) {
                TextView title = (TextView) inflater.inflate(R.layout.title,
                        null);
                title.setText(getString(R.string.api_stream));
                vg.addView(title);
                inflater.inflate(R.layout.separator, vg);
                // Stream
                if (data.containsKey(ParseGeneric.API_STREAM)) {
                    HashMap<String, String> stream = (HashMap<String, String>) data
                            .get(ParseGeneric.API_STREAM);
                    for (Entry<String, String> entry : stream.entrySet()) {
                        final String type = entry.getKey();
                        final String url = entry.getValue();
                        TextView tv = (TextView) inflater.inflate(
                                R.layout.entry, null);
                        tv.setText(url);
                        tv.setOnClickListener(new View.OnClickListener() {
                            public void onClick(View v) {
                                Intent i = new Intent(Intent.ACTION_VIEW);
                                i.setDataAndType(Uri.parse(url), type);
                                startActivity(i);
                            }
                        });
                        vg.addView(tv);
                    }
                }
                // Cam
                if (data.containsKey(ParseGeneric.API_CAM)) {
                    HashMap<String, String> cam = (HashMap<String, String>) data
                            .get(ParseGeneric.API_CAM);
                    for (String value : cam.values()) {
                        TextView tv = (TextView) inflater.inflate(
                                R.layout.entry, null);
                        tv.setText(value);
                        vg.addView(tv);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            showError(e.getClass().getCanonicalName(), e.getLocalizedMessage() + getString(R.string.error_generic));
        }
    }

}
